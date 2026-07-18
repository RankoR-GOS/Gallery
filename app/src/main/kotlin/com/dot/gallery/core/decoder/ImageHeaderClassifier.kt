package com.dot.gallery.core.decoder

internal const val IMAGE_HEADER_BYTES = 4 * 1024

internal fun classifyImageHeader(header: ByteArray, length: Int): ImageFileFormat? {
    val availableBytes = length.coerceIn(minimumValue = 0, maximumValue = header.size)
    return when {
        isJxlCodestream(header = header, length = availableBytes) -> ImageFileFormat.JXL
        isJxlContainer(header = header, length = availableBytes) -> ImageFileFormat.JXL
        isJpeg(header = header, length = availableBytes) -> ImageFileFormat.JPEG
        isPng(header = header, length = availableBytes) -> ImageFileFormat.PNG
        isGif(header = header, length = availableBytes) -> ImageFileFormat.GIF
        isAnimatedWebp(header = header, length = availableBytes) -> ImageFileFormat.ANIMATED_WEBP
        isWebp(header = header, length = availableBytes) -> ImageFileFormat.WEBP
        isBmp(header = header, length = availableBytes) -> ImageFileFormat.BMP
        isTiff(header = header, length = availableBytes) -> ImageFileFormat.TIFF
        isSvg(header = header, length = availableBytes) -> ImageFileFormat.SVG
        else -> classifyIsoBaseMediaFormat(header = header, length = availableBytes)
    }
}

private fun isJxlCodestream(header: ByteArray, length: Int): Boolean {
    return length >= 2 && header[0] == 0xFF.toByte() && header[1] == 0x0A.toByte()
}

private fun isJxlContainer(header: ByteArray, length: Int): Boolean {
    return matchesBytes(
        header = header,
        length = length,
        offset = 0,
        expected = byteArrayOf(
            0x00,
            0x00,
            0x00,
            0x0C,
            0x4A,
            0x58,
            0x4C,
            0x20,
            0x0D,
            0x0A,
            0x87.toByte(),
            0x0A,
        ),
    )
}

private fun isJpeg(header: ByteArray, length: Int): Boolean {
    return matchesBytes(
        header = header,
        length = length,
        offset = 0,
        expected = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()),
    )
}

private fun isPng(header: ByteArray, length: Int): Boolean {
    return matchesBytes(
        header = header,
        length = length,
        offset = 0,
        expected = byteArrayOf(
            0x89.toByte(),
            0x50,
            0x4E,
            0x47,
            0x0D,
            0x0A,
            0x1A,
            0x0A,
        ),
    )
}

private fun isGif(header: ByteArray, length: Int): Boolean {
    return matchesAscii(header = header, length = length, offset = 0, expected = "GIF87a") ||
        matchesAscii(header = header, length = length, offset = 0, expected = "GIF89a")
}

private fun isWebp(header: ByteArray, length: Int): Boolean {
    return matchesAscii(header = header, length = length, offset = 0, expected = "RIFF") &&
        matchesAscii(header = header, length = length, offset = 8, expected = "WEBP")
}

private fun isAnimatedWebp(header: ByteArray, length: Int): Boolean {
    return isWebp(header = header, length = length) &&
        matchesAscii(header = header, length = length, offset = 12, expected = "VP8X") &&
        length > WEBP_EXTENDED_FLAGS_OFFSET &&
        header[WEBP_EXTENDED_FLAGS_OFFSET].toInt() and WEBP_ANIMATION_FLAG != 0
}

private fun isBmp(header: ByteArray, length: Int): Boolean {
    return matchesAscii(header = header, length = length, offset = 0, expected = "BM")
}

private fun isTiff(header: ByteArray, length: Int): Boolean {
    val littleEndian = byteArrayOf(0x49, 0x49, 0x2A, 0x00)
    val bigEndian = byteArrayOf(0x4D, 0x4D, 0x00, 0x2A)
    return matchesBytes(
        header = header,
        length = length,
        offset = 0,
        expected = littleEndian,
    ) || matchesBytes(
        header = header,
        length = length,
        offset = 0,
        expected = bigEndian,
    )
}

private fun isSvg(header: ByteArray, length: Int): Boolean {
    val searchLimit = minOf(length, SVG_SEARCH_BYTES)
    if (searchLimit == 0) {
        return false
    }

    val headerText = header.decodeToString(
        startIndex = 0,
        endIndex = searchLimit,
        throwOnInvalidSequence = false,
    )
    return SVG_TAG_REGEX.containsMatchIn(input = headerText)
}

private fun classifyIsoBaseMediaFormat(header: ByteArray, length: Int): ImageFileFormat? {
    var offset = 0
    while (offset + ISO_BOX_HEADER_BYTES <= length) {
        val boxSize = readUnsignedInt(header = header, offset = offset)
        if (boxSize < ISO_BOX_HEADER_BYTES.toLong() || boxSize > Int.MAX_VALUE.toLong()) {
            return null
        }

        val boxEnd = offset + boxSize.toInt()
        if (boxEnd > length) {
            return null
        }

        if (
            boxSize >= MINIMUM_FTYP_BOX_BYTES &&
            matchesAscii(header = header, length = length, offset = offset + 4, expected = "ftyp")
        ) {
            return classifyFtypBox(
                header = header,
                boxStart = offset,
                boxEnd = boxEnd,
            )
        }
        offset = boxEnd
    }
    return null
}

private fun classifyFtypBox(header: ByteArray, boxStart: Int, boxEnd: Int): ImageFileFormat? {
    val brandsStart = boxStart + ISO_BOX_HEADER_BYTES
    if (brandsStart + BRAND_BYTES > boxEnd) {
        return null
    }

    val primaryBrand = classifyBrand(header = header, offset = brandsStart)
    var hasHeifBrand = primaryBrand == ImageFileFormat.HEIF
    if (primaryBrand == ImageFileFormat.AVIF) {
        return ImageFileFormat.AVIF
    }

    var brandOffset = brandsStart + MAJOR_BRAND_AND_MINOR_VERSION_BYTES
    while (brandOffset + BRAND_BYTES <= boxEnd) {
        when (classifyBrand(header = header, offset = brandOffset)) {
            ImageFileFormat.AVIF -> return ImageFileFormat.AVIF
            ImageFileFormat.HEIF -> hasHeifBrand = true
            else -> Unit
        }
        brandOffset += BRAND_BYTES
    }
    return ImageFileFormat.HEIF.takeIf { hasHeifBrand }
}

private fun classifyBrand(header: ByteArray, offset: Int): ImageFileFormat? {
    val brand = String(header, offset, BRAND_BYTES, Charsets.ISO_8859_1)
    return when (brand) {
        "avif", "avis" -> ImageFileFormat.AVIF
        in HEIF_BRANDS -> ImageFileFormat.HEIF
        else -> null
    }
}

private fun matchesAscii(
    header: ByteArray,
    length: Int,
    offset: Int,
    expected: String,
): Boolean {
    return matchesBytes(
        header = header,
        length = length,
        offset = offset,
        expected = expected.toByteArray(Charsets.ISO_8859_1),
    )
}

private fun matchesBytes(
    header: ByteArray,
    length: Int,
    offset: Int,
    expected: ByteArray,
): Boolean {
    if (offset < 0 || offset + expected.size > length) {
        return false
    }
    return expected.indices.all { index ->
        header[offset + index] == expected[index]
    }
}

private fun readUnsignedInt(header: ByteArray, offset: Int): Long {
    return ((header[offset].toLong() and 0xFFL) shl 24) or
        ((header[offset + 1].toLong() and 0xFFL) shl 16) or
        ((header[offset + 2].toLong() and 0xFFL) shl 8) or
        (header[offset + 3].toLong() and 0xFFL)
}

private const val BRAND_BYTES = 4
private const val ISO_BOX_HEADER_BYTES = 8
private const val MAJOR_BRAND_AND_MINOR_VERSION_BYTES = 8
private const val MINIMUM_FTYP_BOX_BYTES = 16L
private const val WEBP_ANIMATION_FLAG = 1 shl 1
private const val WEBP_EXTENDED_FLAGS_OFFSET = 20
private const val SVG_SEARCH_BYTES = 1024

private val SVG_TAG_REGEX = Regex(pattern = "<svg(?:\\s|>)")

private val HEIF_BRANDS = setOf(
    "heic",
    "heif",
    "heim",
    "heis",
    "heix",
    "hevc",
    "hevm",
    "hevs",
    "hevx",
    "mif1",
    "msf1",
)
