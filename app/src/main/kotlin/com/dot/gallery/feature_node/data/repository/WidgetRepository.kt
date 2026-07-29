package com.dot.gallery.feature_node.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import com.dot.gallery.feature_node.data.model.WidgetData
import com.dot.gallery.feature_node.data.model.WidgetType
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json

interface WidgetRepository {
    fun getWidgetData(widgetId: Int): WidgetData?
    fun replaceWidgetData(widgetId: Int, type: WidgetType, uris: List<Uri>): Boolean
    fun deleteWidgetData(widgetId: Int): Boolean
    fun getMediaUris(widgetId: Int): List<Uri>
}

@Singleton
internal class WidgetRepositoryImpl @Inject constructor(
    @ApplicationContext context: Context,
) : WidgetRepository {

    private val contentResolver = context.contentResolver
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    override fun getWidgetData(widgetId: Int): WidgetData? {
        val encodedData = preferences.getString(widgetKey(widgetId = widgetId), null) ?: return null
        return runCatching {
            json.decodeFromString<WidgetData>(encodedData)
        }.getOrNull()
    }

    override fun replaceWidgetData(widgetId: Int, type: WidgetType, uris: List<Uri>): Boolean {
        return synchronized(uriOwnershipLock) {
            replaceWidgetDataLocked(widgetId = widgetId, type = type, uris = uris)
        }
    }

    private fun replaceWidgetDataLocked(widgetId: Int, type: WidgetType, uris: List<Uri>): Boolean {
        val previousData = getWidgetData(widgetId = widgetId)
        val distinctUris = uris.distinct()
        val previouslyPersistedUris = contentResolver.persistedUriPermissions
            .asSequence()
            .filter { permission -> permission.isReadPermission }
            .map { permission -> permission.uri }
            .toSet()

        distinctUris.forEach { uri ->
            takeReadPermission(uri = uri)
        }

        val widgetData = WidgetData(
            widgetId = widgetId,
            type = type,
            mediaUris = distinctUris.map { uri -> uri.toString() },
        )
        val saved = preferences.edit()
            .putString(widgetKey(widgetId = widgetId), json.encodeToString(widgetData))
            .commit()

        if (!saved) {
            distinctUris
                .filterNot { uri -> uri in previouslyPersistedUris }
                .forEach { uri -> releaseReadPermissionIfUnused(uri = uri) }
            return false
        }

        previousData?.mediaUris
            ?.asSequence()
            ?.map { encodedUri -> encodedUri.toUri() }
            ?.filterNot { uri -> uri in distinctUris }
            ?.forEach { uri -> releaseReadPermissionIfUnused(uri = uri) }
        return true
    }

    override fun deleteWidgetData(widgetId: Int): Boolean {
        return synchronized(uriOwnershipLock) {
            deleteWidgetDataLocked(widgetId = widgetId)
        }
    }

    private fun deleteWidgetDataLocked(widgetId: Int): Boolean {
        val previousData = getWidgetData(widgetId = widgetId)
        val deleted = preferences.edit()
            .remove(widgetKey(widgetId = widgetId))
            .commit()

        if (deleted) {
            previousData?.mediaUris
                ?.asSequence()
                ?.map { encodedUri -> encodedUri.toUri() }
                ?.forEach { uri -> releaseReadPermissionIfUnused(uri = uri) }
        }
        return deleted
    }

    override fun getMediaUris(widgetId: Int): List<Uri> {
        return getWidgetData(widgetId = widgetId)
            ?.mediaUris
            ?.map { encodedUri -> encodedUri.toUri() }
            .orEmpty()
    }

    private fun takeReadPermission(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (exception: SecurityException) {
            Log.w(TAG, "The provider did not grant persistent read access to ${uri}", exception)
        } catch (exception: IllegalArgumentException) {
            Log.w(TAG, "The provider did not grant persistent read access to ${uri}", exception)
        }
    }

    private fun releaseReadPermissionIfUnused(uri: Uri) {
        val isStillReferenced = storedWidgetData().any { widgetData ->
            widgetData.mediaUris.any { encodedUri -> encodedUri == uri.toString() }
        }
        if (isStillReferenced) {
            return
        }

        val hasPersistedReadPermission = contentResolver.persistedUriPermissions.any { permission ->
            permission.isReadPermission && permission.uri == uri
        }
        if (!hasPersistedReadPermission) {
            return
        }

        try {
            contentResolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (exception: SecurityException) {
            Log.w(TAG, "Unable to release persistent read access to ${uri}", exception)
        } catch (exception: IllegalArgumentException) {
            Log.w(TAG, "Unable to release persistent read access to ${uri}", exception)
        }
    }

    private fun storedWidgetData(): Sequence<WidgetData> {
        return preferences.all
            .asSequence()
            .filter { (key, value) -> key.startsWith(KEY_PREFIX) && value is String }
            .mapNotNull { (_, value) ->
                runCatching {
                    json.decodeFromString<WidgetData>(value as String)
                }.getOrNull()
            }
    }

    private fun widgetKey(widgetId: Int): String {
        return "${KEY_PREFIX}${widgetId}"
    }

    private companion object {
        private const val TAG = "WidgetRepository"
        private const val PREFERENCES_NAME = "gallery_widget_prefs"
        private const val KEY_PREFIX = "widget_"
        private val uriOwnershipLock = Any()
    }
}
