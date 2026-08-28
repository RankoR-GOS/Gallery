package com.dot.gallery.feature_node.data.util

import android.content.ContentResolver
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import java.io.File
import kotlinx.coroutines.CancellationException

private const val TAG = "MediaTimestamp"
private const val MILLIS_PER_SECOND = 1000L

/** Set the pending copy's file time before publication triggers MediaStore's scan. */
internal fun ContentResolver.preserveMediaTimestamp(sourceUri: Uri, destinationUri: Uri) {
    try {
        val modifiedSeconds = query(
            sourceUri,
            arrayOf(MediaStore.MediaColumns.DATE_MODIFIED),
            null,
            null,
            null,
        )?.use { cursor ->
            when {
                cursor.moveToFirst() && !cursor.isNull(0) -> cursor.getLong(0)
                else -> null
            }
        } ?: return
        if (modifiedSeconds <= 0L || modifiedSeconds > Long.MAX_VALUE / MILLIS_PER_SECOND) return
        val path = query(
            destinationUri,
            arrayOf(MediaStore.MediaColumns.DATA),
            null,
            null,
            null,
        )?.use { cursor ->
            when {
                cursor.moveToFirst() && !cursor.isNull(0) -> cursor.getString(0)
                else -> null
            }
        } ?: return
        if (!File(path).setLastModified(modifiedSeconds * MILLIS_PER_SECOND)) {
            Log.w(TAG, "Could not preserve the copied file timestamp")
        }
    } catch (exception: CancellationException) {
        throw exception
    } catch (exception: Exception) {
        // A provider without timestamp/path support must not discard an otherwise valid copy.
        Log.w(TAG, "Could not preserve the copied file timestamp")
    }
}
