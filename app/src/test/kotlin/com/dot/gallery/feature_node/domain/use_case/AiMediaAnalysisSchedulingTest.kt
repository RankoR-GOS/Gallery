package com.dot.gallery.feature_node.domain.use_case

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.WorkManagerTestInitHelper
import com.dot.gallery.core.workers.CategoryWorker
import com.dot.gallery.core.workers.SearchIndexerUpdaterWorker
import com.dot.gallery.feature_node.data.model.AiMediaAnalysisPreferences
import com.dot.gallery.feature_node.data.repository.AiMediaAnalysisRepository
import com.dot.gallery.feature_node.data.repository.MediaRepository
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@Config(application = Application::class)
@RunWith(RobolectricTestRunner::class)
internal class AiMediaAnalysisSchedulingTest {
    private lateinit var analysis: AiMediaAnalysis
    private lateinit var mediaRepository: MediaRepository
    private lateinit var repository: FakeRepository
    private lateinit var updateMediaDatabase: UpdateMediaDatabase
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        val context: Context = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        workManager = WorkManager.getInstance(context)
        repository = FakeRepository()
        analysis = AiMediaAnalysisImpl(
            repository = repository,
            scheduler = AiMediaAnalysisSchedulerImpl(workManager = workManager),
        )
        mediaRepository = mockk(relaxed = true)
        updateMediaDatabase = UpdateMediaDatabase(
            repository = mediaRepository,
            aiMediaAnalysis = analysis,
        )
    }

    @Test
    fun databaseUpdates_scheduleAnalysisOnlyWhileUserConsentIsEnabled() {
        runTest {
            updateMediaDatabase()

            assertTrue(analysisWorkInfos().isEmpty())

            analysis.setAnalysisEnabled(enabled = true)

            val enabledWorkInfos = analysisWorkInfos()
            assertEquals(
                1,
                enabledWorkInfos.count { workInfo ->
                    SearchIndexerUpdaterWorker.TAG in workInfo.tags
                },
            )

            updateMediaDatabase()

            assertEquals(
                1,
                analysisWorkInfos().count { workInfo ->
                    SearchIndexerUpdaterWorker.TAG in workInfo.tags
                },
            )

            analysis.setAnalysisEnabled(enabled = false)
            val knownWorkIds = analysisWorkInfos().map { workInfo -> workInfo.id }.toSet()
            val generatedDataClearCount = repository.generatedDataClearCount

            updateMediaDatabase()

            assertEquals(knownWorkIds, analysisWorkInfos().map { workInfo -> workInfo.id }.toSet())
            assertEquals(generatedDataClearCount, repository.generatedDataClearCount)
            coVerify(exactly = 3) {
                mediaRepository.updateInternalDatabase()
            }
        }
    }

    @Test
    fun cancellingCategoryWork_keepsActiveIndexingScheduled() {
        runTest {
            repository.setAnalysisEnabled(enabled = true)
            analysis.requestCategoryClassification()
            analysis.requestAnalysis()
            val originalIndexingIds = analysisWorkInfos().map { workInfo -> workInfo.id }.toSet()

            analysis.cancelCategoryClassification()

            val indexingWorkInfos = analysisWorkInfos()
            assertTrue(indexingWorkInfos.any { workInfo ->
                workInfo.id in originalIndexingIds &&
                    (workInfo.state == WorkInfo.State.ENQUEUED ||
                        workInfo.state == WorkInfo.State.RUNNING)
            })
        }
    }

    @Test
    fun enablingCategories_replacesActiveIndexingWithForcedCategoryChain() {
        runTest {
            repository.setAnalysisEnabled(enabled = true)
            repository.setCategoryClassificationEnabled(enabled = false)
            analysis.requestAnalysis()

            analysis.setCategoryClassificationEnabled(enabled = true)

            val categoryWorkInfos = categoryWorkInfos()
            assertEquals(1, categoryWorkInfos.size)
            assertEquals(WorkInfo.State.BLOCKED, categoryWorkInfos.single().state)
            assertTrue(categoryWorkInfos.single().forcesCategoryClassification())
            assertTrue(analysisWorkInfos().any { workInfo ->
                workInfo.state == WorkInfo.State.ENQUEUED ||
                    workInfo.state == WorkInfo.State.RUNNING
            })
        }
    }

    @Test
    fun explicitCategoryRequest_replacesPassiveChainWithOneForcedCategoryChain() {
        runTest {
            repository.setAnalysisEnabled(enabled = true)
            analysis.requestAnalysis()

            val queuedWorkState = analysis.workState.first { workState ->
                workState.isAnalysisActive
            }
            assertTrue(queuedWorkState.isCategoryActive)

            analysis.requestCategoryClassification()

            val activeCategoryWorkInfos = categoryWorkInfos()
                .filterNot { workInfo -> workInfo.state.isFinished }
            assertEquals(1, activeCategoryWorkInfos.size)
            assertEquals(WorkInfo.State.BLOCKED, activeCategoryWorkInfos.single().state)
            assertTrue(activeCategoryWorkInfos.single().forcesCategoryClassification())
            assertEquals(
                1,
                analysisWorkInfos().count { workInfo -> !workInfo.state.isFinished },
            )
        }
    }

    private fun analysisWorkInfos(): List<WorkInfo> {
        return workManager.getWorkInfosByTag(SearchIndexerUpdaterWorker.TAG).get()
    }

    private fun categoryWorkInfos(): List<WorkInfo> {
        return workManager.getWorkInfosByTag(CategoryWorker.TAG).get()
    }

    @SuppressLint("RestrictedApi")
    private fun WorkInfo.forcesCategoryClassification(): Boolean {
        val workManagerImpl = workManager as WorkManagerImpl
        val workSpec = workManagerImpl.workDatabase
            .workSpecDao()
            .getWorkSpec(id = id.toString())
        return workSpec?.input?.getBoolean(CategoryWorker.KEY_FORCE_CLASSIFICATION, false) == true
    }

    private class FakeRepository : AiMediaAnalysisRepository {
        private val preferencesFlow = MutableStateFlow(
            AiMediaAnalysisPreferences(
                analysisEnabled = false,
                categoryClassificationEnabled = true,
                analysisCleanupPending = false,
                categoryCleanupPending = false,
            ),
        )

        var generatedDataClearCount = 0
            private set

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
            preferencesFlow.value = preferencesFlow.value.copy(
                analysisEnabled = enabled,
                analysisCleanupPending = !enabled,
                categoryCleanupPending = !enabled,
            )
        }

        override suspend fun setCategoryClassificationEnabled(enabled: Boolean) {
            preferencesFlow.value = preferencesFlow.value.copy(
                categoryClassificationEnabled = enabled,
                categoryCleanupPending = !enabled,
            )
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
            generatedDataClearCount++
        }

        override suspend fun clearCategoryGeneratedData() {
        }
    }
}
