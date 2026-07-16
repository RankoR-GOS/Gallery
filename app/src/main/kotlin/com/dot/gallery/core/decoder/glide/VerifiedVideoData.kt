package com.dot.gallery.core.decoder.glide

import android.os.ParcelFileDescriptor

internal data class VerifiedVideoData(
    val descriptor: ParcelFileDescriptor,
) : GalleryMediaData {

    override fun close() {
        descriptor.close()
    }
}
