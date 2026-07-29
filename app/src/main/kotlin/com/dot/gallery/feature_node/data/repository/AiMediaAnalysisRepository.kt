package com.dot.gallery.feature_node.data.repository

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.room.withTransaction
import com.dot.gallery.core.dataStore
import com.dot.gallery.feature_node.data.data_source.InternalDatabase
import com.dot.gallery.feature_node.data.model.AiMediaAnalysisPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject

internal interface AiMediaAnalysisRepository {

    val preferences: Flow<AiMediaAnalysisPreferences>

    suspend fun getPreferences(): AiMediaAnalysisPreferences

    suspend fun needsMigration(): Boolean

    suspend fun completeMigration()

    suspend fun setAnalysisEnabled(enabled: Boolean)

    suspend fun setCategoryClassificationEnabled(enabled: Boolean)

    suspend fun completeAnalysisCleanup()

    suspend fun completeCategoryCleanup()

    suspend fun invalidateGeneratedData(mediaIds: Set<Long>)

    suspend fun removeMediaData(mediaIds: Set<Long>)

    suspend fun removeMissingCategoryMappings(validMediaIds: Set<Long>)

    suspend fun clearAllGeneratedData()

    suspend fun clearCategoryGeneratedData()
}

internal class AiMediaAnalysisRepositoryImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val database: InternalDatabase,
) : AiMediaAnalysisRepository {

    override val preferences: Flow<AiMediaAnalysisPreferences> = context.dataStore.data.map { values ->
        AiMediaAnalysisPreferences(
            analysisEnabled = values[AI_MEDIA_ANALYSIS_ENABLED] ?: false,
            categoryClassificationEnabled = resolveCategoryClassificationEnabled(
                preferences = values,
            ),
            analysisCleanupPending = values[AI_ANALYSIS_CLEANUP_PENDING] ?: false,
            categoryCleanupPending = values[AI_CATEGORY_CLEANUP_PENDING] ?: false,
        )
    }

    override suspend fun getPreferences(): AiMediaAnalysisPreferences {
        return preferences.first()
    }

    override suspend fun needsMigration(): Boolean {
        val migrationVersion = context.dataStore.data
            .first()[AI_MEDIA_ANALYSIS_MIGRATION_VERSION] ?: 0
        return migrationVersion < CURRENT_MIGRATION_VERSION
    }

    override suspend fun completeMigration() {
        context.dataStore.edit { values ->
            val categoriesEnabled = resolveCategoryClassificationEnabled(preferences = values)
            values[AI_MEDIA_ANALYSIS_ENABLED] = values[AI_MEDIA_ANALYSIS_ENABLED] ?: false
            values[AI_CATEGORY_CLASSIFICATION_ENABLED] = categoriesEnabled
            values.remove(LEGACY_NO_CLASSIFICATION)
            values[AI_MEDIA_ANALYSIS_MIGRATION_VERSION] = CURRENT_MIGRATION_VERSION
        }
    }

    override suspend fun setAnalysisEnabled(enabled: Boolean) {
        context.dataStore.edit { values ->
            values[AI_MEDIA_ANALYSIS_ENABLED] = enabled
            when {
                enabled -> {
                    values[AI_ANALYSIS_CLEANUP_PENDING] = false
                    val categoriesEnabled = resolveCategoryClassificationEnabled(
                        preferences = values,
                    )

                    if (categoriesEnabled) {
                        values[AI_CATEGORY_CLEANUP_PENDING] = false
                    }
                }

                else -> {
                    values[AI_ANALYSIS_CLEANUP_PENDING] = true
                    values[AI_CATEGORY_CLEANUP_PENDING] = true
                }
            }
        }
    }

    override suspend fun setCategoryClassificationEnabled(enabled: Boolean) {
        context.dataStore.edit { values ->
            values[AI_CATEGORY_CLASSIFICATION_ENABLED] = enabled
            values[AI_CATEGORY_CLEANUP_PENDING] = !enabled
        }
    }

    override suspend fun completeAnalysisCleanup() {
        context.dataStore.edit { values ->
            values[AI_ANALYSIS_CLEANUP_PENDING] = false
            val categoriesEnabled = resolveCategoryClassificationEnabled(preferences = values)
            if (categoriesEnabled) {
                values[AI_CATEGORY_CLEANUP_PENDING] = false
            }
        }
    }

    override suspend fun completeCategoryCleanup() {
        context.dataStore.edit { values ->
            values[AI_CATEGORY_CLEANUP_PENDING] = false
        }
    }

    override suspend fun invalidateGeneratedData(mediaIds: Set<Long>) {
        database.withTransaction {
            database.getImageEmbeddingDao().deleteByIds(ids = mediaIds)
            database.getCategoryDao().removeGeneratedMediaFromAllCategories(mediaIds = mediaIds)
        }
    }

    override suspend fun removeMediaData(mediaIds: Set<Long>) {
        database.withTransaction {
            database.getImageEmbeddingDao().deleteByIds(ids = mediaIds)
            database.getCategoryDao().removeMediaFromAllCategories(mediaIds = mediaIds)
        }
    }

    override suspend fun removeMissingCategoryMappings(validMediaIds: Set<Long>) {
        val missingMediaIds = database.getCategoryDao()
            .getAllClassifiedMediaIds()
            .filterNotTo(mutableSetOf()) { mediaId -> mediaId in validMediaIds }
        if (missingMediaIds.isNotEmpty()) {
            database.getCategoryDao().removeMediaFromAllCategories(mediaIds = missingMediaIds)
        }
    }

    override suspend fun clearAllGeneratedData() {
        database.withTransaction {
            database.getImageEmbeddingDao().deleteAll()
            database.getCategoryDao().deleteAllGeneratedMediaCategories()
            database.getCategoryDao().clearCategoryEmbeddings()
        }
    }

    override suspend fun clearCategoryGeneratedData() {
        database.withTransaction {
            database.getCategoryDao().deleteAllGeneratedMediaCategories()
            database.getCategoryDao().clearCategoryEmbeddings()
        }
    }

    private fun resolveCategoryClassificationEnabled(preferences: Preferences): Boolean {
        val legacyClassificationDisabled = preferences[LEGACY_NO_CLASSIFICATION] ?: false
        return preferences[AI_CATEGORY_CLASSIFICATION_ENABLED] ?: !legacyClassificationDisabled
    }

    companion object {
        private const val CURRENT_MIGRATION_VERSION = 1

        private val AI_ANALYSIS_CLEANUP_PENDING =
            booleanPreferencesKey("ai_analysis_cleanup_pending")
        private val AI_CATEGORY_CLASSIFICATION_ENABLED =
            booleanPreferencesKey("ai_category_classification_enabled")
        private val AI_CATEGORY_CLEANUP_PENDING =
            booleanPreferencesKey("ai_category_cleanup_pending")
        private val AI_MEDIA_ANALYSIS_ENABLED = booleanPreferencesKey("ai_media_analysis_enabled")
        private val AI_MEDIA_ANALYSIS_MIGRATION_VERSION =
            intPreferencesKey("ai_media_analysis_migration_version")
        private val LEGACY_NO_CLASSIFICATION = booleanPreferencesKey("no_classification")
    }
}
