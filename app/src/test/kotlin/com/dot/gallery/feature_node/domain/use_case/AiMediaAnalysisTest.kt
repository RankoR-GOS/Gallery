package com.dot.gallery.feature_node.domain.use_case

import com.dot.gallery.feature_node.data.model.AiMediaAnalysisPreferences
import com.dot.gallery.feature_node.domain.model.AiMediaAnalysisWorkState
import com.dot.gallery.feature_node.data.repository.AiMediaAnalysisRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

internal class AiMediaAnalysisTest {

    @Test
    fun initialize_migratesToDisabledAfterCancellingAndClearingGeneratedData() {
        runTest {
            val events = mutableListOf<String>()
            val repository = FakeRepository(needsMigration = true, events = events)
            val scheduler = FakeScheduler(events = events)
            val analysis = AiMediaAnalysisImpl(repository = repository, scheduler = scheduler)

            analysis.initialize()

            assertFalse(repository.currentPreferences.analysisEnabled)
            assertEquals(
                listOf(
                    "set_analysis_false",
                    "cancel_all",
                    "complete_migration",
                    "schedule_cleanup",
                ),
                events,
            )
        }
    }

    @Test
    fun enable_schedulesIndexAndCategories() {
        runTest {
            val repository = FakeRepository(needsMigration = false)
            val scheduler = FakeScheduler()
            val analysis = AiMediaAnalysisImpl(repository = repository, scheduler = scheduler)

            analysis.setAnalysisEnabled(enabled = true)

            assertTrue(repository.currentPreferences.analysisEnabled)
            assertEquals(listOf(true to true), scheduler.analysisRequests)
        }
    }

    @Test
    fun disable_persistsConsentBeforeCancellingAndDeleting() {
        runTest {
            val events = mutableListOf<String>()
            val repository = FakeRepository(
                needsMigration = false,
                initialPreferences = preferences(analysisEnabled = true),
                events = events,
            )
            val scheduler = FakeScheduler(events = events)
            val analysis = AiMediaAnalysisImpl(repository = repository, scheduler = scheduler)

            analysis.setAnalysisEnabled(enabled = false)

            assertEquals(
                listOf("set_analysis_false", "cancel_all", "schedule_cleanup"),
                events,
            )
            assertTrue(repository.currentPreferences.analysisCleanupPending)
        }
    }

    @Test
    fun disablingCategories_preservesSemanticEmbeddingsAndClearsCategoryData() {
        runTest {
            val events = mutableListOf<String>()
            val repository = FakeRepository(
                needsMigration = false,
                initialPreferences = preferences(analysisEnabled = true),
                events = events,
            )
            val scheduler = FakeScheduler(events = events)
            val analysis = AiMediaAnalysisImpl(repository = repository, scheduler = scheduler)

            analysis.setCategoryClassificationEnabled(enabled = false)

            assertEquals(
                listOf("set_categories_false", "cancel_categories", "schedule_cleanup"),
                events,
            )
            assertFalse(repository.allGeneratedDataCleared)
            assertFalse(repository.categoryGeneratedDataCleared)
            assertTrue(repository.currentPreferences.categoryCleanupPending)
            assertTrue(scheduler.analysisRequests.isEmpty())
        }
    }

    @Test
    fun enablingCategories_schedulesForcedClassificationAfterFreshIndexing() {
        runTest {
            val repository = FakeRepository(
                needsMigration = false,
                initialPreferences = preferences(
                    analysisEnabled = true,
                    categoryClassificationEnabled = false,
                ),
            )
            val scheduler = FakeScheduler()
            val analysis = AiMediaAnalysisImpl(repository = repository, scheduler = scheduler)

            analysis.setCategoryClassificationEnabled(enabled = true)

            assertEquals(1, scheduler.forcedCategoryAnalysisRequests)
            assertTrue(scheduler.analysisRequests.isEmpty())
        }
    }

    @Test
    fun cancelCategories_doesNotReplaceIndependentIndexingWork() {
        runTest {
            val repository = FakeRepository(
                needsMigration = false,
                initialPreferences = preferences(analysisEnabled = true),
            )
            val scheduler = FakeScheduler()
            val analysis = AiMediaAnalysisImpl(repository = repository, scheduler = scheduler)

            analysis.cancelCategoryClassification()

            assertTrue(scheduler.analysisRequests.isEmpty())
        }
    }

    @Test
    fun initialize_reschedulesCleanupLeftPendingByCancelledSettingOperation() {
        runTest {
            val repository = FakeRepository(
                needsMigration = false,
                initialPreferences = preferences(analysisEnabled = true),
            )
            val cancellingScheduler = FakeScheduler(cancelCleanupScheduling = true)
            val cancelledAnalysis = AiMediaAnalysisImpl(
                repository = repository,
                scheduler = cancellingScheduler,
            )

            runCatching {
                cancelledAnalysis.setAnalysisEnabled(enabled = false)
            }

            assertTrue(repository.currentPreferences.analysisCleanupPending)

            val recoveredScheduler = FakeScheduler()
            val recreatedAnalysis = AiMediaAnalysisImpl(
                repository = repository,
                scheduler = recoveredScheduler,
            )
            recreatedAnalysis.initialize()

            assertEquals(1, recoveredScheduler.cleanupRequests)
        }
    }

    @Test
    fun disable_finishesDurableSchedulingAfterCallerIsCancelled() {
        runTest {
            val repository = FakeRepository(
                needsMigration = false,
                initialPreferences = preferences(analysisEnabled = true),
            )
            val cancellationStarted = CompletableDeferred<Unit>()
            val allowCancellationToFinish = CompletableDeferred<Unit>()
            val scheduler = FakeScheduler(
                cancellationStarted = cancellationStarted,
                allowCancellationToFinish = allowCancellationToFinish,
            )
            val analysis = AiMediaAnalysisImpl(repository = repository, scheduler = scheduler)

            val settingJob = launch {
                analysis.setAnalysisEnabled(enabled = false)
            }
            cancellationStarted.await()
            settingJob.cancel()
            allowCancellationToFinish.complete(Unit)
            settingJob.join()

            assertEquals(1, scheduler.cleanupRequests)
            assertTrue(repository.currentPreferences.analysisCleanupPending)
        }
    }

    @Test
    fun reenableAnalysis_preservesAndSchedulesPendingCategoryCleanup() {
        runTest {
            val repository = FakeRepository(
                needsMigration = false,
                initialPreferences = preferences(
                    analysisEnabled = true,
                    categoryClassificationEnabled = true,
                ),
            )
            val scheduler = FakeScheduler()
            val analysis = AiMediaAnalysisImpl(repository = repository, scheduler = scheduler)

            analysis.setCategoryClassificationEnabled(enabled = false)
            analysis.setAnalysisEnabled(enabled = false)
            analysis.setAnalysisEnabled(enabled = true)

            assertTrue(repository.currentPreferences.analysisEnabled)
            assertFalse(repository.currentPreferences.categoryClassificationEnabled)
            assertFalse(repository.currentPreferences.analysisCleanupPending)
            assertTrue(repository.currentPreferences.categoryCleanupPending)
            assertEquals(3, scheduler.cleanupRequests)
            assertEquals(1, scheduler.cancelCleanupRequests)
            assertEquals(
                listOf(false to true),
                scheduler.analysisRequests,
            )
        }
    }

    @Test
    fun disableAnalysis_schedulesCleanupWhenCancelledAtRepositoryReturnBoundary() {
        runTest {
            val settingPersisted = CompletableDeferred<Unit>()
            val allowRepositoryReturn = CompletableDeferred<Unit>()
            val repository = FakeRepository(
                needsMigration = false,
                initialPreferences = preferences(analysisEnabled = true),
                analysisSettingPersisted = settingPersisted,
                allowAnalysisSettingReturn = allowRepositoryReturn,
            )
            val scheduler = FakeScheduler()
            val analysis = AiMediaAnalysisImpl(repository = repository, scheduler = scheduler)

            val settingJob = launch {
                analysis.setAnalysisEnabled(enabled = false)
            }
            settingPersisted.await()
            settingJob.cancel()
            allowRepositoryReturn.complete(Unit)
            settingJob.join()

            assertTrue(repository.currentPreferences.analysisCleanupPending)
            assertEquals(1, scheduler.cleanupRequests)
        }
    }

    @Test
    fun disableCategories_schedulesCleanupWhenCancelledAtRepositoryReturnBoundary() {
        runTest {
            val settingPersisted = CompletableDeferred<Unit>()
            val allowRepositoryReturn = CompletableDeferred<Unit>()
            val repository = FakeRepository(
                needsMigration = false,
                initialPreferences = preferences(analysisEnabled = true),
                categorySettingPersisted = settingPersisted,
                allowCategorySettingReturn = allowRepositoryReturn,
            )
            val scheduler = FakeScheduler()
            val analysis = AiMediaAnalysisImpl(repository = repository, scheduler = scheduler)

            val settingJob = launch {
                analysis.setCategoryClassificationEnabled(enabled = false)
            }
            settingPersisted.await()
            settingJob.cancel()
            allowRepositoryReturn.complete(Unit)
            settingJob.join()

            assertTrue(repository.currentPreferences.categoryCleanupPending)
            assertEquals(1, scheduler.cleanupRequests)
        }
    }

    private class FakeRepository(
        private var needsMigration: Boolean,
        initialPreferences: AiMediaAnalysisPreferences = preferences(),
        private val events: MutableList<String> = mutableListOf(),
        private val analysisSettingPersisted: CompletableDeferred<Unit>? = null,
        private val allowAnalysisSettingReturn: CompletableDeferred<Unit>? = null,
        private val categorySettingPersisted: CompletableDeferred<Unit>? = null,
        private val allowCategorySettingReturn: CompletableDeferred<Unit>? = null,
    ) : AiMediaAnalysisRepository {
        private val preferencesFlow = MutableStateFlow(initialPreferences)
        var allGeneratedDataCleared = false
        var categoryGeneratedDataCleared = false

        val currentPreferences: AiMediaAnalysisPreferences
            get() = preferencesFlow.value

        override val preferences: Flow<AiMediaAnalysisPreferences> = preferencesFlow

        override suspend fun getPreferences(): AiMediaAnalysisPreferences {
            return preferencesFlow.value
        }

        override suspend fun needsMigration(): Boolean {
            return needsMigration
        }

        override suspend fun completeMigration() {
            events += "complete_migration"
            needsMigration = false
        }

        override suspend fun setAnalysisEnabled(enabled: Boolean) {
            events += "set_analysis_$enabled"
            val currentPreferences = preferencesFlow.value
            preferencesFlow.value = when {
                enabled -> currentPreferences.copy(
                    analysisEnabled = true,
                    analysisCleanupPending = false,
                    categoryCleanupPending = currentPreferences.categoryCleanupPending &&
                        !currentPreferences.categoryClassificationEnabled,
                )

                else -> currentPreferences.copy(
                    analysisEnabled = false,
                    analysisCleanupPending = true,
                    categoryCleanupPending = true,
                )
            }
            analysisSettingPersisted?.complete(Unit)
            allowAnalysisSettingReturn?.await()
        }

        override suspend fun setCategoryClassificationEnabled(enabled: Boolean) {
            events += "set_categories_$enabled"
            preferencesFlow.value = preferencesFlow.value.copy(
                categoryClassificationEnabled = enabled,
                categoryCleanupPending = !enabled,
            )
            categorySettingPersisted?.complete(Unit)
            allowCategorySettingReturn?.await()
        }

        override suspend fun completeAnalysisCleanup() {
            val currentPreferences = preferencesFlow.value
            preferencesFlow.value = currentPreferences.copy(
                analysisCleanupPending = false,
                categoryCleanupPending = currentPreferences.categoryCleanupPending &&
                    !currentPreferences.categoryClassificationEnabled,
            )
        }

        override suspend fun completeCategoryCleanup() {
            preferencesFlow.value = preferencesFlow.value.copy(categoryCleanupPending = false)
        }

        override suspend fun invalidateGeneratedData(mediaIds: Set<Long>) {
        }

        override suspend fun removeMediaData(mediaIds: Set<Long>) {
        }

        override suspend fun removeMissingCategoryMappings(validMediaIds: Set<Long>) {
        }

        override suspend fun clearAllGeneratedData() {
            events += "clear_all"
            allGeneratedDataCleared = true
        }

        override suspend fun clearCategoryGeneratedData() {
            events += "clear_categories"
            categoryGeneratedDataCleared = true
        }
    }

    private class FakeScheduler(
        private val events: MutableList<String> = mutableListOf(),
        private val cancelCleanupScheduling: Boolean = false,
        private val cancellationStarted: CompletableDeferred<Unit>? = null,
        private val allowCancellationToFinish: CompletableDeferred<Unit>? = null,
    ) : AiMediaAnalysisScheduler {
        val analysisRequests = mutableListOf<Pair<Boolean, Boolean>>()
        var forcedCategoryAnalysisRequests = 0
        var cleanupRequests = 0
        var cancelCleanupRequests = 0

        override val workState: Flow<AiMediaAnalysisWorkState> = emptyFlow()

        override suspend fun scheduleAnalysis(
            includeCategories: Boolean,
            replaceExisting: Boolean,
        ) {
            analysisRequests += includeCategories to replaceExisting
        }

        override suspend fun scheduleAnalysisWithForcedCategoryClassification() {
            forcedCategoryAnalysisRequests++
        }

        override suspend fun scheduleGeneratedDataCleanup() {
            events += "schedule_cleanup"
            if (cancelCleanupScheduling) {
                throw kotlinx.coroutines.CancellationException("setting operation cancelled")
            }
            cleanupRequests++
        }

        override suspend fun cancelAll() {
            events += "cancel_all"
            cancellationStarted?.complete(Unit)
            allowCancellationToFinish?.await()
        }

        override suspend fun cancelCategoryClassification() {
            events += "cancel_categories"
        }

        override suspend fun cancelGeneratedDataCleanup() {
            cancelCleanupRequests++
        }
    }

    companion object {
        private fun preferences(
            analysisEnabled: Boolean = false,
            categoryClassificationEnabled: Boolean = true,
            analysisCleanupPending: Boolean = false,
            categoryCleanupPending: Boolean = false,
        ): AiMediaAnalysisPreferences {
            return AiMediaAnalysisPreferences(
                analysisEnabled = analysisEnabled,
                categoryClassificationEnabled = categoryClassificationEnabled,
                analysisCleanupPending = analysisCleanupPending,
                categoryCleanupPending = categoryCleanupPending,
            )
        }
    }
}
