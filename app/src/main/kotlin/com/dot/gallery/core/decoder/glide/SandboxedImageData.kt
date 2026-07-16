package com.dot.gallery.core.decoder.glide

import java.io.InputStream

internal data class SandboxedImageData(
    val inputStream: InputStream,
    val mimeType: String,
) : GalleryMediaData {
    override fun close() {
        inputStream.close()
    }
}
