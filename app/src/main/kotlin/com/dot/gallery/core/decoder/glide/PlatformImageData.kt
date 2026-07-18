package com.dot.gallery.core.decoder.glide

import com.dot.gallery.core.decoder.ImageFileFormat
import java.io.InputStream

internal data class PlatformImageData(
    val inputStream: InputStream,
    val format: ImageFileFormat,
) : GalleryMediaData {
    override fun close() {
        inputStream.close()
    }
}
