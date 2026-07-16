package com.dot.gallery.feature_node.presentation.util

import android.net.Uri
import com.dot.gallery.core.decoder.glide.galleryMediaModel
import com.dot.gallery.feature_node.domain.model.Album
import com.dot.gallery.feature_node.domain.model.Media
import com.dot.gallery.feature_node.domain.util.getUri

internal fun Media.toGlideModel(): Any {
    return galleryMediaModel(uri = getUri(), mimeType = mimeType)
}

internal fun Album.toGlideModel(): Any {
    return galleryMediaModel(uri = uri, mimeType = null)
}

internal fun Uri.toGlideModel(mimeType: String? = null): Any {
    return galleryMediaModel(uri = this, mimeType = mimeType)
}
