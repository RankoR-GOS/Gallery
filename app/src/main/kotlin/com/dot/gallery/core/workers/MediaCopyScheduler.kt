package com.dot.gallery.core.workers

import android.util.Log
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject

internal interface MediaCopyScheduler {

    fun prepareBatch(requests: List<MediaCopyRequest>): MediaCopyBatch

    suspend fun enqueue(batch: MediaCopyBatch, requests: List<MediaCopyRequest>)

    fun observe(batch: MediaCopyBatch): Flow<MediaCopyBatchStatus>
}

internal class MediaCopySchedulerImpl @Inject constructor(
    private val workManager: WorkManager,
) : MediaCopyScheduler {

    override fun prepareBatch(requests: List<MediaCopyRequest>): MediaCopyBatch {
        require(requests.isNotEmpty()) { "At least one media copy request is required" }

        return MediaCopyBatch(
            tag = "${MEDIA_COPY_BATCH_TAG_PREFIX}${UUID.randomUUID()}",
            workRequestCount = calculateWorkRequestCount(itemCount = requests.size),
            itemCount = requests.size,
        )
    }

    override suspend fun enqueue(
        batch: MediaCopyBatch,
        requests: List<MediaCopyRequest>,
    ) {
        require(requests.isNotEmpty()) { "At least one media copy request is required" }
        require(batch.tag.isNotBlank()) { "Media copy batch tag must not be blank" }
        require(batch.itemCount == requests.size) { "Media copy batch item count does not match" }
        require(
            batch.workRequestCount == calculateWorkRequestCount(itemCount = requests.size),
        ) { "Media copy batch work request count does not match" }

        try {
            val workRequests = requests.chunked(MEDIA_PER_WORK_REQUEST).map { chunk ->
                OneTimeWorkRequestBuilder<MediaCopyWorker>()
                    .addTag(MEDIA_COPY_WORKER_TAG)
                    .addTag(batch.tag)
                    .setInputData(
                        workDataOf(
                            "uris" to chunk.map { request ->
                                request.sourceUri.toString()
                            }.toTypedArray(),
                            "paths" to chunk.map { request ->
                                request.destinationPath
                            }.toTypedArray(),
                        ),
                    )
                    .build()
            }
            workManager.enqueue(workRequests).await()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            Log.w(TAG, "Unable to confirm media copy batch enqueue", exception)
        }
    }

    override fun observe(batch: MediaCopyBatch): Flow<MediaCopyBatchStatus> {
        return workManager.getWorkInfosByTagFlow(batch.tag)
            .map { workInfos ->
                toStatus(
                    batch = batch,
                    workInfos = workInfos,
                )
            }
            .catch { exception ->
                when (exception) {
                    is CancellationException -> throw exception
                    is Exception -> emit(MediaCopyBatchStatus.Unavailable)
                    else -> throw exception
                }
            }
    }

    internal fun toStatus(
        batch: MediaCopyBatch,
        workInfos: List<WorkInfo>,
    ): MediaCopyBatchStatus {
        if (workInfos.size != batch.workRequestCount) {
            return MediaCopyBatchStatus.Unavailable
        }

        return when {
            workInfos.all { workInfo -> workInfo.state.isFinished } -> {
                terminalStatus(
                    batch = batch,
                    workInfos = workInfos,
                )
            }

            else -> MediaCopyBatchStatus.Copying(
                progress = averageProgress(workInfos = workInfos),
            )
        }
    }

    private fun terminalStatus(
        batch: MediaCopyBatch,
        workInfos: List<WorkInfo>,
    ): MediaCopyBatchStatus {
        val copiedCount = workInfos.sumOf { workInfo ->
            workInfo.outputData.getInt(MediaCopyWorker.MEDIA_COPY_SUCCESSFUL_COUNT_KEY, 0)
        }.coerceIn(0, batch.itemCount)

        val reportedFailedCount = workInfos.sumOf { workInfo ->
            workInfo.outputData.getInt(MediaCopyWorker.MEDIA_COPY_FAILED_COUNT_KEY, 0)
        }

        val failedCount = maxOf(batch.itemCount - copiedCount, reportedFailedCount)
            .coerceIn(0, batch.itemCount)

        val successful = copiedCount == batch.itemCount && workInfos.all { workInfo ->
            workInfo.state == WorkInfo.State.SUCCEEDED
        }

        return MediaCopyBatchStatus.Finished(
            copiedCount = copiedCount,
            failedCount = failedCount,
            successful = successful,
        )
    }

    private fun averageProgress(workInfos: List<WorkInfo>): Float {
        val progressTotal = workInfos.sumOf { workInfo ->
            when (workInfo.state) {
                WorkInfo.State.SUCCEEDED -> 100
                else -> {
                    workInfo
                        .progress
                        .getInt(MediaCopyWorker.MEDIA_COPY_PROGRESS_KEY, 0)
                }
            }
        }
        return (progressTotal.toFloat() / workInfos.size.toFloat())
            .coerceIn(0f, 100f) / 100f
    }

    private fun calculateWorkRequestCount(itemCount: Int): Int {
        return (itemCount + MEDIA_PER_WORK_REQUEST - 1) / MEDIA_PER_WORK_REQUEST
    }

    companion object {
        private const val MEDIA_COPY_BATCH_TAG_PREFIX = "MediaCopyBatch_"
        private const val MEDIA_COPY_WORKER_TAG = "MediaCopyWorker"
        private const val MEDIA_PER_WORK_REQUEST = 32
        private const val TAG = "MediaCopyScheduler"
    }
}
