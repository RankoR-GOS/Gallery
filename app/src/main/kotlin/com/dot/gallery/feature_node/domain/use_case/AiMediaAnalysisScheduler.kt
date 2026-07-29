package com.dot.gallery.feature_node.domain.use_case

import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.workDataOf
import com.dot.gallery.core.workers.AiMediaAnalysisCleanupWorker
import com.dot.gallery.core.workers.CategoryWorker
import com.dot.gallery.core.workers.SearchIndexerUpdaterWorker
import com.dot.gallery.feature_node.domain.model.AiMediaAnalysisWorkState
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal interface AiMediaAnalysisScheduler {

    val workState: Flow<AiMediaAnalysisWorkState>

    suspend fun scheduleAnalysis(includeCategories: Boolean, replaceExisting: Boolean)

    suspend fun scheduleAnalysisWithForcedCategoryClassification()

    suspend fun scheduleGeneratedDataCleanup()

    suspend fun cancelAll()

    suspend fun cancelCategoryClassification()

    suspend fun cancelGeneratedDataCleanup()
}

internal class AiMediaAnalysisSchedulerImpl @Inject constructor(
    private val workManager: WorkManager,
) : AiMediaAnalysisScheduler {

    override val workState: Flow<AiMediaAnalysisWorkState> = workManager
        .getWorkInfosForUniqueWorkFlow(AI_MEDIA_ANALYSIS_WORK_NAME)
        .map { workInfos -> workInfos.toAnalysisWorkState() }

    override suspend fun scheduleAnalysis(includeCategories: Boolean, replaceExisting: Boolean) {
        enqueueAnalysis(
            includeCategories = includeCategories,
            forceCategoryClassification = false,
            existingWorkPolicy = when {
                replaceExisting -> ExistingWorkPolicy.REPLACE
                else -> ExistingWorkPolicy.KEEP
            },
        )
    }

    override suspend fun scheduleAnalysisWithForcedCategoryClassification() {
        enqueueAnalysis(
            includeCategories = true,
            forceCategoryClassification = true,
            existingWorkPolicy = ExistingWorkPolicy.REPLACE,
        )
    }

    override suspend fun scheduleGeneratedDataCleanup() {
        val request = OneTimeWorkRequestBuilder<AiMediaAnalysisCleanupWorker>()
            .addTag(AI_MEDIA_ANALYSIS_CLEANUP_TAG)
            .build()
        workManager.enqueueUniqueWork(
            AI_MEDIA_ANALYSIS_CLEANUP_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        ).await()
    }

    override suspend fun cancelAll() {
        workManager.cancelUniqueWork(AI_MEDIA_ANALYSIS_WORK_NAME).await()
    }

    override suspend fun cancelCategoryClassification() {
        workManager.cancelAllWorkByTag(CategoryWorker.TAG).await()
    }

    override suspend fun cancelGeneratedDataCleanup() {
        workManager.cancelUniqueWork(AI_MEDIA_ANALYSIS_CLEANUP_WORK_NAME).await()
    }

    private suspend fun enqueueAnalysis(
        includeCategories: Boolean,
        forceCategoryClassification: Boolean,
        existingWorkPolicy: ExistingWorkPolicy,
    ) {
        val indexingRequest = OneTimeWorkRequestBuilder<SearchIndexerUpdaterWorker>()
            .setConstraints(analysisConstraints())
            .addTag(AI_MEDIA_ANALYSIS_TAG)
            .addTag(SearchIndexerUpdaterWorker.TAG)
            .build()
        val continuation = workManager.beginUniqueWork(
            AI_MEDIA_ANALYSIS_WORK_NAME,
            existingWorkPolicy,
            indexingRequest,
        )

        val operation = when {
            includeCategories -> continuation.then(
                categoryRequest(forceClassification = forceCategoryClassification),
            ).enqueue()
            else -> continuation.enqueue()
        }
        operation.await()
    }

    private fun categoryRequest(forceClassification: Boolean): OneTimeWorkRequest {
        return OneTimeWorkRequestBuilder<CategoryWorker>()
            .setConstraints(analysisConstraints())
            .setInputData(
                workDataOf(
                    CategoryWorker.KEY_FORCE_CLASSIFICATION to forceClassification,
                ),
            )
            .addTag(AI_MEDIA_ANALYSIS_TAG)
            .addTag(CategoryWorker.TAG)
            .build()
    }

    private fun analysisConstraints(): Constraints {
        return Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .build()
    }

    private fun List<WorkInfo>.toAnalysisWorkState(): AiMediaAnalysisWorkState {
        val runningIndexer = firstOrNull { workInfo ->
            SearchIndexerUpdaterWorker.TAG in workInfo.tags &&
                workInfo.state == WorkInfo.State.RUNNING
        }
        val runningCategoryWorker = firstOrNull { workInfo ->
            CategoryWorker.TAG in workInfo.tags && workInfo.state == WorkInfo.State.RUNNING
        }
        return AiMediaAnalysisWorkState(
            analysisProgress = runningIndexer
                ?.progress
                ?.getFloat(SearchIndexerUpdaterWorker.KEY_PROGRESS, -1f)
                ?.takeIf { progress -> progress >= 0f },
            isAnalysisActive = any { workInfo ->
                workInfo.state == WorkInfo.State.RUNNING ||
                    workInfo.state == WorkInfo.State.ENQUEUED
            },
            categoryProgress = runningCategoryWorker
                ?.progress
                ?.getFloat(CategoryWorker.KEY_PROGRESS, 0f) ?: 0f,
            categoryStatus = runningCategoryWorker
                ?.progress
                ?.getString(CategoryWorker.KEY_STATUS).orEmpty(),
            isCategoryActive = any { workInfo ->
                CategoryWorker.TAG in workInfo.tags &&
                    (workInfo.state == WorkInfo.State.RUNNING ||
                        workInfo.state == WorkInfo.State.ENQUEUED ||
                        workInfo.state == WorkInfo.State.BLOCKED)
            },
        )
    }

    companion object {
        private const val AI_MEDIA_ANALYSIS_CLEANUP_TAG = "AiMediaAnalysisCleanup"
        private const val AI_MEDIA_ANALYSIS_CLEANUP_WORK_NAME = "AiMediaAnalysisCleanup"
        private const val AI_MEDIA_ANALYSIS_TAG = "AiMediaAnalysis"
        private const val AI_MEDIA_ANALYSIS_WORK_NAME = "AiMediaAnalysis"
    }
}
