package com.dot.gallery.core.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.dot.gallery.core.ml.ImageEmbeddingGenerator
import com.dot.gallery.core.ml.ModelStatus
import com.dot.gallery.core.sandbox.MediaPreviewDecoder
import com.dot.gallery.feature_node.domain.model.ImageEmbedding
import com.dot.gallery.feature_node.domain.repository.AiMediaAnalysisRepository
import com.dot.gallery.feature_node.domain.repository.MediaRepository
import com.dot.gallery.feature_node.domain.util.getUri
import com.dot.gallery.feature_node.domain.util.isVideo
import com.dot.gallery.feature_node.presentation.util.printInfo
import com.dot.gallery.feature_node.presentation.util.printWarning
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
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

        val media = repository.getCompleteMedia().map { resource ->
            resource.data.orEmpty()
        }.firstOrNull().orEmpty()
        val records = repository.getImageEmbeddings().firstOrNull().orEmpty()
        val mediaById = media.associateBy { mediaItem -> mediaItem.id }
        val recordsById = records.associateBy { record -> record.id }
        val removedIds = recordsById.keys - mediaById.keys
        analysisRepository.removeMissingCategoryMappings(validMediaIds = mediaById.keys)
        val mediaToIndex = media.filter { mediaItem ->
            recordsById[mediaItem.id]?.date != mediaItem.timestamp
        }
        val changedIds = mediaToIndex.map { mediaItem -> mediaItem.id }.toSet()
        if (removedIds.isNotEmpty()) {
            analysisRepository.removeMediaData(mediaIds = removedIds)
        }
        if (changedIds.isNotEmpty()) {
            analysisRepository.invalidateGeneratedData(mediaIds = changedIds)
        }
        if (mediaToIndex.isEmpty()) {
            return Result.success(workDataOf(KEY_CHANGED_COUNT to removedIds.size))
        }

        embeddingGenerator.openSession().use { session ->
            mediaToIndex.forEachIndexed { index, mediaItem ->
                currentCoroutineContext().ensureActive()
                if (!analysisRepository.getPreferences().analysisEnabled) {
                    return@use
                }
                val progress = ((index.toFloat() / mediaToIndex.size.toFloat()) * 100f)
                    .coerceIn(0f, 99f)
                setProgress(workDataOf(KEY_PROGRESS to progress, KEY_STAGE to STAGE_INDEXING))
                val bitmap = previewDecoder.decode(
                    uri = mediaItem.getUri(),
                    mimeType = mediaItem.mimeType,
                    isVideo = mediaItem.isVideo,
                )
                bitmap?.let { previewBitmap ->
                    try {
                        val embedding = session.generate(bitmap = previewBitmap)
                        currentCoroutineContext().ensureActive()
                        if (analysisRepository.getPreferences().analysisEnabled) {
                            repository.addImageEmbedding(
                                ImageEmbedding(
                                    id = mediaItem.id,
                                    date = mediaItem.timestamp,
                                    embedding = embedding,
                                ),
                            )
                        }
                    } finally {
                        previewBitmap.recycle()
                    }
                }
                yield()
            }
        }
        setProgress(workDataOf(KEY_PROGRESS to 100f, KEY_STAGE to STAGE_INDEXING))
        return Result.success(
            workDataOf(KEY_CHANGED_COUNT to removedIds.size + changedIds.size),
        )
    }

    companion object {
        internal const val KEY_CHANGED_COUNT = "changed_count"
        internal const val KEY_PROGRESS = "progress"
        internal const val KEY_STAGE = "stage"
        internal const val STAGE_INDEXING = "indexing"
        internal const val TAG = "SearchIndexerUpdater"
    }
}
