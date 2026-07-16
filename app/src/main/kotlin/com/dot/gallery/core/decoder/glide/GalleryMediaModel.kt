package com.dot.gallery.core.decoder.glide

import android.net.Uri
import java.util.Locale

internal data class GalleryMediaModel(
    val uri: Uri,
    val declaredMimeType: String?,
)

internal fun galleryMediaModel(uri: Uri, mimeType: String?): Any {
    return when {
        uri == Uri.EMPTY -> uri
        else -> GalleryMediaModel(
            uri = uri,
            declaredMimeType = normalizeMimeType(mimeType = mimeType),
        )
    }
}

internal fun normalizeMimeType(mimeType: String?): String? {
    return mimeType
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase(Locale.ROOT)
        ?.takeIf { normalizedMimeType -> normalizedMimeType.isNotEmpty() }
}
