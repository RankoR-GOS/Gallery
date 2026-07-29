package com.dot.gallery.feature_node.domain.use_case

import com.dot.gallery.feature_node.data.repository.AiMediaAnalysisRepository
import com.dot.gallery.feature_node.domain.model.AiMediaAnalysisWorkState
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class AiMediaAnalysisSettings(
    val analysisEnabled: Boolean,
    val categoryClassificationEnabled: Boolean,
)

internal interface AiMediaAnalysis {

    val settings: Flow<AiMediaAnalysisSettings>

    val workState: Flow<AiMediaAnalysisWorkState>

    suspend fun initialize()

    suspend fun setAnalysisEnabled(enabled: Boolean)

    suspend fun setCategoryClassificationEnabled(enabled: Boolean)

    suspend fun requestAnalysis()

    suspend fun requestCategoryClassification()

    suspend fun cancelCategoryClassification()
}

@Singleton
internal class AiMediaAnalysisImpl @Inject constructor(
    private val repository: AiMediaAnalysisRepository,
    private val scheduler: AiMediaAnalysisScheduler,
) : AiMediaAnalysis {
    private val mutationMutex = Mutex()
    private var initialized = false

    override val settings: Flow<AiMediaAnalysisSettings> = repository.preferences.map { preferences ->
        AiMediaAnalysisSettings(
            analysisEnabled = preferences.analysisEnabled,
            categoryClassificationEnabled = preferences.categoryClassificationEnabled,
        )
    }

    override val workState: Flow<AiMediaAnalysisWorkState> = scheduler.workState

    override suspend fun initialize() {
        mutationMutex.withLock {
            initializeLocked()
        }
    }

    override suspend fun setAnalysisEnabled(enabled: Boolean) {
        mutationMutex.withLock {
            initializeLocked()
            withContext(NonCancellable) {
                repository.setAnalysisEnabled(enabled = enabled)
                when {
                    enabled -> {
                        scheduler.cancelGeneratedDataCleanup()
                        val preferences = repository.getPreferences()
                        if (preferences.categoryCleanupPending) {
                            scheduler.scheduleGeneratedDataCleanup()
                        }
                        scheduler.scheduleAnalysis(
                            includeCategories = preferences.categoryClassificationEnabled,
                            replaceExisting = true,
                        )
                    }

                    else -> {
                        scheduler.cancelAll()
                        scheduler.scheduleGeneratedDataCleanup()
                    }
                }
            }
        }
    }

    override suspend fun setCategoryClassificationEnabled(enabled: Boolean) {
        mutationMutex.withLock {
            initializeLocked()
            withContext(NonCancellable) {
                repository.setCategoryClassificationEnabled(enabled = enabled)
                when {
                    enabled && repository.getPreferences().analysisEnabled -> {
                        scheduler.cancelGeneratedDataCleanup()
                        scheduler.scheduleAnalysisWithForcedCategoryClassification()
                    }

                    !enabled -> {
                        cancelCategoryClassificationLocked()
                        scheduler.scheduleGeneratedDataCleanup()
                    }
                }
            }
        }
    }

    override suspend fun requestAnalysis() {
        mutationMutex.withLock {
            initializeLocked()
            val preferences = repository.getPreferences()
            if (preferences.analysisEnabled) {
                scheduler.scheduleAnalysis(
                    includeCategories = preferences.categoryClassificationEnabled,
                    replaceExisting = false,
                )
            }
        }
    }

    override suspend fun requestCategoryClassification() {
        mutationMutex.withLock {
            initializeLocked()
            val preferences = repository.getPreferences()
            if (preferences.analysisEnabled && preferences.categoryClassificationEnabled) {
                scheduler.scheduleAnalysisWithForcedCategoryClassification()
            }
        }
    }

    override suspend fun cancelCategoryClassification() {
        mutationMutex.withLock {
            initializeLocked()
            cancelCategoryClassificationLocked()
        }
    }

    private suspend fun cancelCategoryClassificationLocked() {
        scheduler.cancelCategoryClassification()
    }

    private suspend fun initializeLocked() {
        if (initialized) {
            return
        }

        if (repository.needsMigration()) {
            repository.setAnalysisEnabled(enabled = false)
            scheduler.cancelAll()
            repository.completeMigration()
        }

        val preferences = repository.getPreferences()
        if (preferences.analysisCleanupPending || preferences.categoryCleanupPending) {
            scheduler.scheduleGeneratedDataCleanup()
        }

        initialized = true
    }
}
