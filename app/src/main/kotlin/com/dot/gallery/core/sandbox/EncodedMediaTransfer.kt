package com.dot.gallery.core.sandbox

import com.dot.gallery.core.util.MAX_ENCODED_MEDIA_BYTES
import com.dot.gallery.core.util.SizeLimitedInputStream
import java.io.InputStream
import java.io.OutputStream

internal fun transferEncodedMedia(
    inputStream: InputStream,
    outputStream: OutputStream,
    maximumBytes: Long = MAX_ENCODED_MEDIA_BYTES,
): Long {
    return SizeLimitedInputStream(
        inputStream = inputStream,
        maximumBytes = maximumBytes,
    ).copyTo(
        out = outputStream,
        bufferSize = TRANSFER_BUFFER_BYTES,
    )
}

private const val TRANSFER_BUFFER_BYTES = 64 * 1024
