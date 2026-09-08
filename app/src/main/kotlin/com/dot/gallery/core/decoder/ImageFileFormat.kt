package com.dot.gallery.core.decoder

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
    SVG(sandboxMimeType = "image/svg+xml"),
    TIFF,
    WEBP;
}
