package com.dot.gallery.core.workers

import android.app.Application
import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.dot.gallery.feature_node.data.model.AiMediaAnalysisPreferences
import com.dot.gallery.feature_node.data.repository.AiMediaAnalysisRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@Config(application = Application::class)
@RunWith(RobolectricTestRunner::class)
internal class AiMediaAnalysisCleanupWorkerTest {

    @Test
    fun pendingAnalysisCleanup_clearsAllGeneratedDataAndCompletesPendingState() {
        runTest {
            val repository = FakeRepository(
                initialPreferences = preferences(analysisCleanupPending = true),
            )

            val result = buildWorker(repository = repository).doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            assertEquals(1, repository.allCleanupCount)
            assertEquals(0, repository.categoryCleanupCount)
            assertEquals(false, repository.currentPreferences.analysisCleanupPending)
            assertEquals(false, repository.currentPreferences.categoryCleanupPending)
        }
    }

    @Test
    fun pendingCategoryCleanup_clearsOnlyGeneratedCategoryData() {
        runTest {
            val repository = FakeRepository(
                initialPreferences = preferences(categoryCleanupPending = true),
            )

            val result = buildWorker(repository = repository).doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            assertEquals(0, repository.allCleanupCount)
            assertEquals(1, repository.categoryCleanupCount)
            assertEquals(false, repository.currentPreferences.categoryCleanupPending)
        }
    }

    @Test
    fun cleanupFailure_retriesAndKeepsPendingState() {
        runTest {
            val repository = FakeRepository(
                initialPreferences = preferences(analysisCleanupPending = true),
                failCleanup = true,
            )

            val result = buildWorker(repository = repository).doWork()

            assertEquals(ListenableWorker.Result.retry(), result)
            assertEquals(true, repository.currentPreferences.analysisCleanupPending)
        }
    }

    private fun buildWorker(repository: AiMediaAnalysisRepository): AiMediaAnalysisCleanupWorker {
        val context: Context = RuntimeEnvironment.getApplication()
        return TestListenableWorkerBuilder<AiMediaAnalysisCleanupWorker>(context = context)
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ): ListenableWorker {
                        return AiMediaAnalysisCleanupWorker(
                            repository = repository,
                            appContext = appContext,
                            workerParams = workerParameters,
                        )
                    }
                },
            )
            .build()
    }

    private class FakeRepository(
        initialPreferences: AiMediaAnalysisPreferences,
        private val failCleanup: Boolean = false,
    ) : AiMediaAnalysisRepository {
        private val preferencesFlow = MutableStateFlow(initialPreferences)
        var allCleanupCount = 0
        var categoryCleanupCount = 0

        val currentPreferences: AiMediaAnalysisPreferences
            get() = preferencesFlow.value

        override val preferences: Flow<AiMediaAnalysisPreferences> = preferencesFlow

        override suspend fun getPreferences(): AiMediaAnalysisPreferences {
            return preferencesFlow.value
        }

        override suspend fun needsMigration(): Boolean {
            return false
        }

        override suspend fun completeMigration() {
        }

        override suspend fun setAnalysisEnabled(enabled: Boolean) {
        }

        override suspend fun setCategoryClassificationEnabled(enabled: Boolean) {
        }

        override suspend fun completeAnalysisCleanup() {
            preferencesFlow.value = preferencesFlow.value.copy(
                analysisCleanupPending = false,
                categoryCleanupPending = false,
            )
        }

        override suspend fun completeCategoryCleanup() {
            preferencesFlow.value = preferencesFlow.value.copy(categoryCleanupPending = false)
        }

        override suspend fun invalidateGeneratedData(mediaIds: Set<Long>) {
        }

        override suspend fun removeMediaData(mediaIds: Set<Long>) {
        }

        override suspend fun getClassifiedMediaIdPage(afterId: Long, limit: Int): List<Long> {
            return emptyList()
        }

        override suspend fun clearAllGeneratedData() {
            if (failCleanup) {
                error("cleanup failed")
            }
            allCleanupCount++
        }

        override suspend fun clearCategoryGeneratedData() {
            categoryCleanupCount++
        }
    }

    companion object {
        private fun preferences(
            analysisCleanupPending: Boolean = false,
            categoryCleanupPending: Boolean = false,
        ): AiMediaAnalysisPreferences {
            return AiMediaAnalysisPreferences(
                analysisEnabled = false,
                categoryClassificationEnabled = true,
                analysisCleanupPending = analysisCleanupPending,
                categoryCleanupPending = categoryCleanupPending,
            )
        }
    }
}
