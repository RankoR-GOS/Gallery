/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.core.workers

import android.content.Context
import androidx.compose.ui.util.fastForEach
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.dot.gallery.core.ml.ModelManager
import com.dot.gallery.core.ml.ModelStatus
import com.dot.gallery.core.util.ProgressThrottler
import com.dot.gallery.feature_node.data.data_source.InternalDatabase
import com.dot.gallery.feature_node.data.model.Category
import com.dot.gallery.feature_node.data.model.MediaCategory
import com.dot.gallery.feature_node.data.repository.AiMediaAnalysisRepository
import com.dot.gallery.feature_node.presentation.search.helpers.SearchVisionHelper
import com.dot.gallery.feature_node.presentation.search.util.dot
import com.dot.gallery.feature_node.presentation.util.printInfo
import com.dot.gallery.feature_node.presentation.util.printWarning
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull

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

        // Get all image embeddings
        val imageEmbeddings = embeddingDao.getRecords().firstOrNull() ?: emptyList()
        if (imageEmbeddings.isEmpty()) {
            printInfo("CategoryWorker: No image embeddings to classify")
            return Result.success()
        }

        printInfo("CategoryWorker: Processing ${categories.size} categories and ${imageEmbeddings.size} images")

        // Set up text session for generating category embeddings
        val textSession = visionHelper.setupTextSession()

        val throttler = ProgressThrottler()
        val totalSteps = categories.size

        textSession.use { session ->
            for ((categoryIndex, category) in categories.withIndex()) {
                currentCoroutineContext().ensureActive()
                if (isStopped || !isClassificationEnabled()) {
                    return Result.success()
                }

                val pct = ((categoryIndex.toFloat() / totalSteps.toFloat()) * 100f).coerceIn(0f, 99f)
                throttler.emit(pct.toInt()) {
                    setProgress(
                        workDataOf(
                            KEY_PROGRESS to it.toFloat(),
                            KEY_STATUS to "Processing ${category.name}...",
                            KEY_CURRENT_CATEGORY to category.name,
                        ),
                    )
                }

                // Generate text embedding for the category's search terms (if any)
                val categoryEmbedding = when {
                    category.searchTerms.isBlank() -> null
                    category.embedding != null -> category.embedding
                    else -> {
                        val embedding = visionHelper.getTextEmbedding(
                            session = session,
                            text = category.searchTerms,
                        )
                        currentCoroutineContext().ensureActive()
                        if (!isClassificationEnabled()) {
                            return Result.success()
                        }
                        categoryDao.updateCategory(
                            category.copy(
                                embedding = embedding,
                                updatedAt = System.currentTimeMillis(),
                            ),
                        )
                        embedding
                    }
                }

                // Collect reference image embeddings for image-to-image matching
                val refIdSet = category.referenceImageIds.toSet()
                val refEmbeddings = when {
                    refIdSet.isNotEmpty() -> imageEmbeddings.filter { it.id in refIdSet }
                    else -> emptyList()
                }

                if (categoryEmbedding == null && refEmbeddings.isEmpty()) {
                    printInfo("CategoryWorker: Category '${category.name}' has no text or reference images, skipping")
                    continue
                }

                // Find matching media
                val matchingMedia = mutableListOf<MediaCategory>()

                imageEmbeddings.fastForEach { imageEmbedding ->
                    // Skip reference images themselves
                    if (imageEmbedding.id in refIdSet) return@fastForEach

                    var bestScore = 0f

                    // Text-to-image similarity
                    if (categoryEmbedding != null) {
                        bestScore = maxOf(bestScore, categoryEmbedding.dot(imageEmbedding.embedding))
                    }

                    // Image-to-image similarity (against each reference)
                    refEmbeddings.fastForEach { referenceEmbedding ->
                        bestScore = maxOf(
                            bestScore,
                            referenceEmbedding.embedding.dot(imageEmbedding.embedding),
                        )
                    }

                    if (bestScore >= category.threshold) {
                        matchingMedia.add(
                            MediaCategory(
                                mediaId = imageEmbedding.id,
                                categoryId = category.id,
                                similarityScore = bestScore,
                            ),
                        )
                    }
                }

                printInfo("CategoryWorker: Category '${category.name}' matched ${matchingMedia.size} media items")

                // Update the database with the matches
                currentCoroutineContext().ensureActive()
                if (!isClassificationEnabled()) {
                    return Result.success()
                }
                categoryDao.reclassifyMediaForCategory(
                    categoryId = category.id,
                    mediaCategories = matchingMedia,
                )
            }
        }

        setProgress(workDataOf(KEY_PROGRESS to 100f, KEY_STATUS to "Complete"))
        printInfo("CategoryWorker: Classification complete")

        return Result.success()
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
    }
}
