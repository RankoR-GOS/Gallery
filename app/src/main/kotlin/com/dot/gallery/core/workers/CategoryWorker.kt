/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.core.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.dot.gallery.core.ml.ModelManager
import com.dot.gallery.core.ml.ModelStatus
import com.dot.gallery.core.util.ProgressThrottler
import com.dot.gallery.feature_node.data.data_source.GeneratedMediaCategoryStaging
import com.dot.gallery.feature_node.data.data_source.InternalDatabase
import com.dot.gallery.feature_node.data.data_source.flatMapIdChunks
import com.dot.gallery.feature_node.data.model.Category
import com.dot.gallery.feature_node.data.model.ImageEmbedding
import com.dot.gallery.feature_node.data.repository.AiMediaAnalysisRepository
import com.dot.gallery.feature_node.presentation.search.helpers.SearchVisionHelper
import com.dot.gallery.feature_node.presentation.search.util.dot
import com.dot.gallery.feature_node.presentation.util.printInfo
import com.dot.gallery.feature_node.presentation.util.printWarning
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Worker that classifies media into categories using CLIP embeddings.
 * 
 * This worker:
 * 1. Loads all categories and their search terms
 * 2. Generates text embeddings for category search terms
 * 3. Compares image embeddings with category embeddings
 * 4. Associates media with categories based on similarity threshold
 */
@HiltWorker
class CategoryWorker @AssistedInject internal constructor(
    private val database: InternalDatabase,
    private val analysisRepository: AiMediaAnalysisRepository,
    private val modelManager: ModelManager,
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    private val visionHelper by lazy { SearchVisionHelper(modelManager) }

    override suspend fun doWork(): Result {
        return try {
            classifyMedia()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            printWarning("CategoryWorker failed: ${exception.message}")
            Result.failure()
        }
    }

    private suspend fun classifyMedia(): Result {
        printInfo("CategoryWorker starting classification")

        val analysisPreferences = analysisRepository.getPreferences()
        if (!analysisPreferences.analysisEnabled || !analysisPreferences.categoryClassificationEnabled) {
            printInfo("CategoryWorker: Classification is disabled")
            return Result.success()
        }

        val forceClassification = inputData.getBoolean(KEY_FORCE_CLASSIFICATION, false)
        val changedCount = inputData.getInt(SearchIndexerUpdaterWorker.KEY_CHANGED_COUNT, 0)
        if (!forceClassification && changedCount == 0) {
            printInfo("CategoryWorker: No changed media to classify")
            return Result.success()
        }

        val modelStatus = modelManager.status.first { status -> status != ModelStatus.CHECKING }
        if (modelStatus != ModelStatus.READY) {
            printInfo("CategoryWorker: ML models unavailable, skipping")
            return Result.success()
        }

        setProgress(workDataOf(KEY_PROGRESS to 0f, KEY_STATUS to "Initializing..."))

        val categoryDao = database.getCategoryDao()
        val embeddingDao = database.getImageEmbeddingDao()

        // Get all categories
        var categories = categoryDao.getAllCategoriesAsync()

        // If no categories exist, initialize with default categories
        if (categories.isEmpty()) {
            printInfo("CategoryWorker: No categories found, initializing defaults")
            categoryDao.insertCategories(categories = Category.DEFAULT_CATEGORIES)
            categories = categoryDao.getAllCategoriesAsync()
        }

        if (categories.isEmpty()) {
            printInfo("CategoryWorker: Still no categories, aborting")
            return Result.success()
        }

        val preparedCategories = prepareCategories(categories = categories)
        val imageCount = embeddingDao.getCount()
        printInfo(
            "CategoryWorker: Processing ${preparedCategories.size} categories and ${imageCount} images",
        )

        val generationId = id.toString()
        currentCoroutineContext().ensureActive()
        if (isStopped || !isClassificationEnabled()) {
            return Result.success()
        }
        categoryDao.beginGeneratedMediaCategoryStaging(generationId = generationId)
        var published = false
        try {
            val throttler = ProgressThrottler()
            var processedImages = 0
            var afterId = Long.MIN_VALUE
            while (true) {
                currentCoroutineContext().ensureActive()
                if (isStopped || !isClassificationEnabled()) {
                    return Result.success()
                }

                val page = embeddingDao.getPage(afterId = afterId, limit = EMBEDDING_PAGE_SIZE)

                if (page.isEmpty()) {
                    break
                }

                val matches = classifyPage(
                    imageEmbeddings = page,
                    categories = preparedCategories,
                    generationId = generationId,
                )

                if (matches.isNotEmpty()) {
                    val staged = categoryDao.stageGeneratedMediaCategories(
                        generationId = generationId,
                        mediaCategories = matches,
                    )
                    if (!staged) {
                        printInfo("CategoryWorker: Classification was superseded")
                        return Result.success()
                    }
                }
                processedImages += page.size

                val progress = when {
                    imageCount == 0 -> 99
                    else -> {
                        ((processedImages.toFloat() / imageCount.toFloat()) * 99f)
                            .toInt()
                            .coerceIn(0, 99)
                    }
                }

                throttler.emit(progress) { percentage ->
                    setProgress(
                        workDataOf(
                            KEY_PROGRESS to percentage.toFloat(),
                            KEY_STATUS to "Classifying media...",
                        ),
                    )
                }
                afterId = page.last().id
            }

            currentCoroutineContext().ensureActive()
            if (!isClassificationEnabled()) {
                return Result.success()
            }

            published = categoryDao.replaceGeneratedMediaCategoriesFromStaging(
                generationId = generationId,
            )

            if (!published) {
                printInfo("CategoryWorker: Classification was superseded")
                return Result.success()
            }

            setProgress(workDataOf(KEY_PROGRESS to 100f, KEY_STATUS to "Complete"))
            printInfo("CategoryWorker: Classification complete")

            return Result.success()
        } finally {
            if (!published) {
                withContext(NonCancellable) {
                    categoryDao.abandonGeneratedMediaCategoryStaging(generationId = generationId)
                }
            }
        }
    }

    private suspend fun prepareCategories(categories: List<Category>): List<PreparedCategory> {
        val categoryDao = database.getCategoryDao()
        val embeddingDao = database.getImageEmbeddingDao()
        val referenceIds = categories
            .flatMapTo(mutableSetOf()) { category -> category.referenceImageIds }
        val referenceEmbeddings = when {
            referenceIds.isEmpty() -> emptyMap()
            else -> flatMapIdChunks(ids = referenceIds) { idChunk ->
                embeddingDao.getByIds(ids = idChunk)
            }.associateBy { embedding -> embedding.id }
        }

        return visionHelper.setupTextSession().use { session ->
            categories.mapNotNull { category ->
                currentCoroutineContext().ensureActive()
                if (!isClassificationEnabled()) {
                    return@use emptyList()
                }
                val textEmbedding = when {
                    category.searchTerms.isBlank() -> null
                    category.embedding != null -> category.embedding
                    else -> {
                        val generatedEmbedding = visionHelper.getTextEmbedding(
                            session = session,
                            text = category.searchTerms,
                        )
                        currentCoroutineContext().ensureActive()
                        if (!isClassificationEnabled()) {
                            return@use emptyList()
                        }
                        categoryDao.updateCategory(
                            category.copy(
                                embedding = generatedEmbedding,
                                updatedAt = System.currentTimeMillis(),
                            ),
                        )
                        generatedEmbedding
                    }
                }

                val categoryReferenceEmbeddings = category.referenceImageIds.mapNotNull { id ->
                    referenceEmbeddings[id]
                }

                when {
                    textEmbedding == null && categoryReferenceEmbeddings.isEmpty() -> null
                    else -> {
                        PreparedCategory(
                            category = category,
                            textEmbedding = textEmbedding,
                            referenceIds = category.referenceImageIds.toSet(),
                            referenceEmbeddings = categoryReferenceEmbeddings,
                        )
                    }
                }
            }
        }
    }

    private fun classifyPage(
        imageEmbeddings: List<ImageEmbedding>,
        categories: List<PreparedCategory>,
        generationId: String,
    ): List<GeneratedMediaCategoryStaging> {
        val addedAt = System.currentTimeMillis()
        return buildList {
            imageEmbeddings.forEach { imageEmbedding ->
                for (preparedCategory in categories) {
                    val category = preparedCategory.category
                    if (imageEmbedding.id in preparedCategory.referenceIds) {
                        continue
                    }
                    var bestScore = preparedCategory.textEmbedding?.dot(imageEmbedding.embedding) ?: 0f
                    preparedCategory.referenceEmbeddings.forEach { referenceEmbedding ->
                        bestScore = maxOf(
                            bestScore,
                            referenceEmbedding.embedding.dot(imageEmbedding.embedding),
                        )
                    }
                    if (bestScore >= category.threshold) {
                        add(
                            GeneratedMediaCategoryStaging(
                                generationId = generationId,
                                mediaId = imageEmbedding.id,
                                categoryId = category.id,
                                similarityScore = bestScore,
                                addedAt = addedAt,
                            ),
                        )
                    }
                }
            }
        }
    }

    private suspend fun isClassificationEnabled(): Boolean {
        val preferences = analysisRepository.getPreferences()
        return preferences.analysisEnabled && preferences.categoryClassificationEnabled
    }

    companion object {
        const val KEY_FORCE_CLASSIFICATION = "force_classification"
        const val KEY_PROGRESS = "progress"
        const val KEY_STATUS = "status"
        const val KEY_CURRENT_CATEGORY = "current_category"
        const val TAG = "CategoryClassifier"
        private const val EMBEDDING_PAGE_SIZE = 256
    }

    private class PreparedCategory(
        val category: Category,
        val textEmbedding: FloatArray?,
        val referenceIds: Set<Long>,
        val referenceEmbeddings: List<ImageEmbedding>,
    )
}
