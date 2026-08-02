package com.dot.gallery.feature_node.data.util

import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore

/** Resolve legacy aggregate URIs without guessing a physical volume from a filesystem path. */
internal fun ContentResolver.resolveMediaStoreMutationUri(uri: Uri): Uri {
    val segments = uri.pathSegments
    require(uri.scheme == ContentResolver.SCHEME_CONTENT && uri.authority == MediaStore.AUTHORITY)
    require(segments.size == 4 && segments[1] in setOf("images", "video") && segments[2] == "media")
    require(segments[3].toLongOrNull()?.let { id -> id > 0 } == true)
    if (segments[0] != MediaStore.VOLUME_EXTERNAL) return uri
    val volume = query(
        uri,
        arrayOf(MediaStore.MediaColumns.VOLUME_NAME),
        null,
        null,
        null,
    )?.use { cursor ->
        when {
            cursor.moveToFirst() -> cursor.getString(0)
            else -> null
        }
    }
    require(!volume.isNullOrBlank() && volume != MediaStore.VOLUME_EXTERNAL)
    return uri.buildUpon().path(null).appendPath(volume)
        .appendPath(segments[1]).appendPath(segments[2]).appendPath(segments[3]).build()
}

internal fun mediaStoreItemUri(id: Long, mimeType: String, volumeName: String): Uri {
    val volume = volumeName.ifBlank { MediaStore.VOLUME_EXTERNAL }
    val collection = when {
        mimeType.startsWith("image/") -> MediaStore.Images.Media.getContentUri(volume)
        else -> MediaStore.Video.Media.getContentUri(volume)
    }
    return ContentUris.withAppendedId(collection, id)
}
