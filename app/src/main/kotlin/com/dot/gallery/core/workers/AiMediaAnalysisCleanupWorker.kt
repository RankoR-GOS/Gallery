package com.dot.gallery.core.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.dot.gallery.feature_node.domain.repository.AiMediaAnalysisRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

@HiltWorker
class AiMediaAnalysisCleanupWorker @AssistedInject internal constructor(
    private val repository: AiMediaAnalysisRepository,
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            cleanPendingGeneratedData()
            Result.success()
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private suspend fun cleanPendingGeneratedData() {
        val preferences = repository.getPreferences()
        when {
            preferences.analysisCleanupPending -> {
                repository.clearAllGeneratedData()
                repository.completeAnalysisCleanup()
            }

            preferences.categoryCleanupPending -> {
                repository.clearCategoryGeneratedData()
                repository.completeCategoryCleanup()
            }
        }
    }
}
