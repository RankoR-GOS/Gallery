package com.dot.gallery.core.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.dot.gallery.core.ml.ImageEmbeddingGenerator
import com.dot.gallery.core.ml.ImageEmbeddingSession
import com.dot.gallery.core.ml.ModelStatus
import com.dot.gallery.core.sandbox.MediaPreviewDecoder
import com.dot.gallery.feature_node.data.model.ImageEmbedding
import com.dot.gallery.feature_node.data.model.Media
import com.dot.gallery.feature_node.data.repository.AiMediaAnalysisRepository
import com.dot.gallery.feature_node.data.repository.MediaRepository
import com.dot.gallery.feature_node.data.util.getUri
import com.dot.gallery.feature_node.data.util.isVideo
import com.dot.gallery.feature_node.presentation.util.printInfo
import com.dot.gallery.feature_node.presentation.util.printWarning
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.yield

@HiltWorker
class SearchIndexerUpdaterWorker @AssistedInject internal constructor(
    private val repository: MediaRepository,
    private val analysisRepository: AiMediaAnalysisRepository,
    private val embeddingGenerator: ImageEmbeddingGenerator,
    private val previewDecoder: MediaPreviewDecoder,
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            indexMedia()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            printWarning("SearchIndexerUpdaterWorker failed with exception: ${exception.message}")
            Result.failure()
        }
    }

    private suspend fun indexMedia(): Result {
        setProgress(workDataOf(KEY_PROGRESS to -1f, KEY_STAGE to STAGE_INDEXING))
        if (!analysisRepository.getPreferences().analysisEnabled) {
            return Result.success(workDataOf(KEY_CHANGED_COUNT to 0))
        }
        val modelStatus = embeddingGenerator.status.first { status -> status != ModelStatus.CHECKING }
        if (modelStatus != ModelStatus.READY) {
            printInfo("ML models unavailable, skipping indexing")
            return Result.success(workDataOf(KEY_CHANGED_COUNT to 0))
        }

        val mappingReconciliation = removeMissingCategoryMappings()
        val indexedChangeCount = reconcileAndIndexMedia(
            totalMediaCount = mappingReconciliation.totalMediaCount,
        )
        setProgress(workDataOf(KEY_PROGRESS to 100f, KEY_STAGE to STAGE_INDEXING))
        return Result.success(
            workDataOf(
                KEY_CHANGED_COUNT to mappingReconciliation.removedCount + indexedChangeCount,
            ),
        )
    }

    private suspend fun removeMissingCategoryMappings(): CategoryMappingReconciliation {
        val mediaReader = mediaPageReader()
        val classifiedIdReader = PagedItemReader(
            pageSize = DATABASE_PAGE_SIZE,
            getId = { mediaId -> mediaId },
            loadPage = analysisRepository::getClassifiedMediaIdPage,
        )
        var mediaItem = mediaReader.next()
        var classifiedMediaId = classifiedIdReader.next()
        val missingMediaIds = mutableSetOf<Long>()
        var removedCount = 0
        var totalMediaCount = 0
        while (classifiedMediaId != null) {
            currentCoroutineContext().ensureActive()
            while (mediaItem != null && mediaItem.id < classifiedMediaId) {
                mediaItem = mediaReader.next()
                totalMediaCount++
            }
            when {
                mediaItem?.id == classifiedMediaId -> {
                    mediaItem = mediaReader.next()
                    totalMediaCount++
                    classifiedMediaId = classifiedIdReader.next()
                }

                else -> {
                    missingMediaIds += classifiedMediaId
                    removedCount++
                    classifiedMediaId = classifiedIdReader.next()
                    if (missingMediaIds.size == DATABASE_PAGE_SIZE) {
                        analysisRepository.removeMediaData(mediaIds = missingMediaIds.toSet())
                        missingMediaIds.clear()
                    }
                }
            }
        }
        if (missingMediaIds.isNotEmpty()) {
            analysisRepository.removeMediaData(mediaIds = missingMediaIds.toSet())
        }
        while (mediaItem != null) {
            mediaItem = mediaReader.next()
            totalMediaCount++
        }
        return CategoryMappingReconciliation(
            removedCount = removedCount,
            totalMediaCount = totalMediaCount,
        )
    }

    private suspend fun reconcileAndIndexMedia(totalMediaCount: Int): Int {
        val mediaReader = mediaPageReader()
        val stampReader = PagedItemReader(
            pageSize = DATABASE_PAGE_SIZE,
            getId = { stamp -> stamp.id },
            loadPage = repository::getImageEmbeddingStampPage,
        )
        var mediaItem = mediaReader.next()
        var stamp = stampReader.next()
        val removedIds = mutableSetOf<Long>()
        val pendingMedia = mutableListOf<PendingMedia>()
        var embeddingSession: ImageEmbeddingSession? = null
        var changedCount = 0
        var scannedMediaCount = 0

        suspend fun flushRemovedIds() {
            if (removedIds.isNotEmpty()) {
                analysisRepository.removeMediaData(mediaIds = removedIds.toSet())
                removedIds.clear()
            }
        }

        fun getOrOpenEmbeddingSession(): ImageEmbeddingSession {
            val currentSession = embeddingSession
            if (currentSession != null) {
                return currentSession
            }
            return embeddingGenerator.openSession().also { openedSession ->
                embeddingSession = openedSession
            }
        }

        suspend fun flushPendingMedia(): Boolean {
            if (pendingMedia.isEmpty()) {
                return true
            }
            val changedIds = pendingMedia
                .filterTo(mutableListOf()) { item -> item.invalidatesExistingData }
                .mapTo(mutableSetOf()) { item -> item.media.id }
            if (changedIds.isNotEmpty()) {
                analysisRepository.invalidateGeneratedData(mediaIds = changedIds)
            }
            for (item in pendingMedia) {
                val indexed = indexMediaItem(
                    mediaItem = item.media,
                    getSession = ::getOrOpenEmbeddingSession,
                )
                if (!indexed) {
                    pendingMedia.clear()
                    return false
                }
            }
            pendingMedia.clear()
            return true
        }

        try {
            while (mediaItem != null || stamp != null) {
                currentCoroutineContext().ensureActive()
                when {
                    mediaItem == null -> {
                        removedIds += requireNotNull(stamp).id
                        changedCount++
                        stamp = stampReader.next()
                    }

                    stamp == null || mediaItem.id < stamp.id -> {
                        pendingMedia += PendingMedia(
                            media = mediaItem,
                            invalidatesExistingData = false,
                        )
                        changedCount++
                        mediaItem = mediaReader.next()
                        scannedMediaCount++
                    }

                    stamp.id < mediaItem.id -> {
                        removedIds += stamp.id
                        changedCount++
                        stamp = stampReader.next()
                    }

                    else -> {
                        if (mediaItem.timestamp != stamp.date) {
                            pendingMedia += PendingMedia(
                                media = mediaItem,
                                invalidatesExistingData = true,
                            )
                            changedCount++
                        }
                        mediaItem = mediaReader.next()
                        stamp = stampReader.next()
                        scannedMediaCount++
                    }
                }
                if (scannedMediaCount > 0 && scannedMediaCount % MEDIA_PAGE_SIZE == 0) {
                    val progress = when {
                        totalMediaCount == 0 -> 99f
                        else -> (scannedMediaCount.toFloat() / totalMediaCount.toFloat() * 99f)
                            .coerceIn(0f, 99f)
                    }
                    setProgress(workDataOf(KEY_PROGRESS to progress, KEY_STAGE to STAGE_INDEXING))
                }
                if (removedIds.size == DATABASE_PAGE_SIZE) {
                    flushRemovedIds()
                }
                if (pendingMedia.size == INDEXING_PAGE_SIZE && !flushPendingMedia()) {
                    flushRemovedIds()
                    return changedCount
                }
            }
            flushRemovedIds()
            flushPendingMedia()
            return changedCount
        } finally {
            embeddingSession?.close()
        }
    }

    private suspend fun indexMediaItem(
        mediaItem: Media.UriMedia,
        getSession: () -> ImageEmbeddingSession,
    ): Boolean {
        currentCoroutineContext().ensureActive()
        if (!analysisRepository.getPreferences().analysisEnabled) {
            return false
        }
        val bitmap = previewDecoder.decode(
            uri = mediaItem.getUri(),
            mimeType = mediaItem.mimeType,
            isVideo = mediaItem.isVideo,
        ) ?: return true
        try {
            currentCoroutineContext().ensureActive()
            if (!analysisRepository.getPreferences().analysisEnabled) {
                return false
            }
            val embedding = getSession().generate(bitmap = bitmap)
            currentCoroutineContext().ensureActive()
            if (!analysisRepository.getPreferences().analysisEnabled) {
                return false
            }
            repository.addImageEmbedding(
                imageEmbedding = ImageEmbedding(
                    id = mediaItem.id,
                    date = mediaItem.timestamp,
                    embedding = embedding,
                ),
            )
        } finally {
            bitmap.recycle()
        }
        yield()
        return true
    }

    private fun mediaPageReader(): PagedItemReader<Media.UriMedia> {
        return PagedItemReader(
            pageSize = MEDIA_PAGE_SIZE,
            getId = { mediaItem -> mediaItem.id },
            loadPage = repository::getCompleteMediaPage,
        )
    }

    companion object {
        internal const val KEY_CHANGED_COUNT = "changed_count"
        internal const val KEY_PROGRESS = "progress"
        internal const val KEY_STAGE = "stage"
        internal const val STAGE_INDEXING = "indexing"
        internal const val TAG = "SearchIndexerUpdater"
        private const val DATABASE_PAGE_SIZE = 900
        private const val INDEXING_PAGE_SIZE = 32
        private const val MEDIA_PAGE_SIZE = 256
    }

    private data class PendingMedia(
        val media: Media.UriMedia,
        val invalidatesExistingData: Boolean,
    )

    private data class CategoryMappingReconciliation(
        val removedCount: Int,
        val totalMediaCount: Int,
    )

    private class PagedItemReader<T>(
        private val pageSize: Int,
        private val getId: (T) -> Long,
        private val loadPage: suspend (afterId: Long, limit: Int) -> List<T>,
    ) {
        private var afterId = Long.MIN_VALUE
        private var currentPage = emptyList<T>()
        private var currentIndex = 0
        private var exhausted = false

        suspend fun next(): T? {
            if (currentIndex == currentPage.size && !loadNextPage()) {
                return null
            }
            return currentPage[currentIndex++]
        }

        private suspend fun loadNextPage(): Boolean {
            if (exhausted) {
                return false
            }
            currentPage = loadPage(afterId, pageSize)
            currentIndex = 0
            if (currentPage.isEmpty()) {
                exhausted = true
                return false
            }
            val lastId = getId(currentPage.last())
            check(lastId > afterId) { "Keyset page did not advance" }
            afterId = lastId
            return true
        }
    }
}
