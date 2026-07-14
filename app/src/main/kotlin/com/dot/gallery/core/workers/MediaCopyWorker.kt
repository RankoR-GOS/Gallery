package com.dot.gallery.core.workers

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.dot.gallery.core.util.ProgressThrottler
import com.dot.gallery.feature_node.data.repository.MediaCopyRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

@HiltWorker
internal class MediaCopyWorker @AssistedInject constructor(
    private val mediaCopyRepository: MediaCopyRepository,
    @Assisted appContext: Context,
    @Assisted private val params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val copyRequests = getCopyRequests()
            ?: return mediaCopyWorkResult(
                successfulCount = 0,
                failedCount = getSuppliedItemCount(),
            )
        val sourceUris = copyRequests.map { copyRequest -> copyRequest.first }
        val totalBytes = getTotalBytes(sourceUris = sourceUris)
        val copyResults = copyMedia(
            copyRequests = copyRequests,
            totalBytes = totalBytes,
        )

        if (copyResults.any { uri -> uri != null }) {
            mediaCopyRepository.notifyMediaChanged()
        }

        if (copyResults.allCopiesSucceeded() && currentCoroutineContext().isActive) {
            setProgress(workDataOf(MEDIA_COPY_PROGRESS_KEY to 100))
        }

        return copyResults.toMediaCopyWorkResult()
    }

    private fun getCopyRequests(): List<Pair<Uri, String>>? {
        val uriStrings = params.inputData.getStringArray("uris")
        val destinationPaths = params.inputData.getStringArray("paths")

        return when {
            uriStrings == null || destinationPaths == null -> null
            uriStrings.isEmpty() || uriStrings.size != destinationPaths.size -> null
            else -> uriStrings.map { uriString -> uriString.toUri() }.zip(destinationPaths)
        }
    }

    private fun getSuppliedItemCount(): Int {
        val uriCount = params.inputData.getStringArray("uris")?.size ?: 0
        val pathCount = params.inputData.getStringArray("paths")?.size ?: 0
        return maxOf(uriCount, pathCount)
    }

    private suspend fun getTotalBytes(sourceUris: List<Uri>): Long {
        return sourceUris.sumOf { uri ->
            mediaCopyRepository.getMediaSize(uri = uri) ?: 0L
        }
    }

    private suspend fun copyMedia(
        copyRequests: List<Pair<Uri, String>>,
        totalBytes: Long,
    ): List<Uri?> {
        val totalMedia = copyRequests.size
        val completedMedia = AtomicInteger(0)
        val copiedBytes = AtomicLong(0)
        val progressThrottler = ProgressThrottler()
        val semaphore = Semaphore(MAX_CONCURRENT_COPIES)

        return coroutineScope {
            copyRequests.map { (sourceUri, destinationPath) ->
                async {
                    semaphore.withPermit {
                        if (!currentCoroutineContext().isActive || isStopped) {
                            return@withPermit null
                        }

                        val destinationUri = mediaCopyRepository.copyMedia(
                            sourceUri = sourceUri,
                            destinationPath = destinationPath,
                        ) { bytesCopied ->
                            updateByteProgress(
                                bytesCopied = bytesCopied,
                                copiedBytes = copiedBytes,
                                totalBytes = totalBytes,
                                progressThrottler = progressThrottler,
                            )
                        }

                        val completedCount = completedMedia.incrementAndGet()

                        if (totalBytes == 0L) {
                            updateItemProgress(
                                completedMedia = completedCount,
                                totalMedia = totalMedia,
                                progressThrottler = progressThrottler,
                            )
                        }

                        destinationUri
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun updateByteProgress(
        bytesCopied: Int,
        copiedBytes: AtomicLong,
        totalBytes: Long,
        progressThrottler: ProgressThrottler,
    ) {
        if (totalBytes <= 0L) {
            return
        }

        val copiedByteCount = copiedBytes.addAndGet(bytesCopied.toLong())
        val progress = ((copiedByteCount.toFloat() / totalBytes.toFloat()) * 100f)
            .toInt()
            .coerceIn(0, 100)

        progressThrottler.emit(progress) { value ->
            setProgress(workDataOf(MEDIA_COPY_PROGRESS_KEY to value))
        }
    }

    private suspend fun updateItemProgress(
        completedMedia: Int,
        totalMedia: Int,
        progressThrottler: ProgressThrottler,
    ) {
        val progress = ((completedMedia.toFloat() / totalMedia.toFloat()) * 100f)
            .toInt()
            .coerceIn(0, 100)

        progressThrottler.emit(progress) { value ->
            setProgress(workDataOf(MEDIA_COPY_PROGRESS_KEY to value))
        }
    }

    companion object {
        internal const val MEDIA_COPY_FAILED_COUNT_KEY = "failed_count"
        internal const val MEDIA_COPY_PROGRESS_KEY = "progress"
        internal const val MEDIA_COPY_SUCCESSFUL_COUNT_KEY = "successful_count"

        private const val MAX_CONCURRENT_COPIES = 4
    }
}

internal fun List<Uri?>.toMediaCopyWorkResult(): ListenableWorker.Result {
    val successfulCount = count { uri -> uri != null }
    return mediaCopyWorkResult(
        successfulCount = successfulCount,
        failedCount = size - successfulCount,
    )
}

private fun List<Uri?>.allCopiesSucceeded(): Boolean {
    return isNotEmpty() && all { uri -> uri != null }
}

private fun mediaCopyWorkResult(
    successfulCount: Int,
    failedCount: Int,
): ListenableWorker.Result {
    val outputData = workDataOf(
        MediaCopyWorker.MEDIA_COPY_SUCCESSFUL_COUNT_KEY to successfulCount,
        MediaCopyWorker.MEDIA_COPY_FAILED_COUNT_KEY to failedCount,
    )

    return when {
        failedCount == 0 && successfulCount > 0 -> ListenableWorker.Result.success(outputData)
        else -> ListenableWorker.Result.failure(outputData)
    }
}
