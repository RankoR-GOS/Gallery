package com.dot.gallery.core.decoder.glide

internal enum class ImageFileFormat(
    val sandboxMimeType: String? = null,
) {
    ANIMATED_WEBP,
    AVIF(sandboxMimeType = "image/avif"),
    BMP,
    GIF,
    HEIF(sandboxMimeType = "image/heif"),
    JPEG,
    JXL(sandboxMimeType = "image/jxl"),
    PNG,
    TIFF,
    WEBP;
}
