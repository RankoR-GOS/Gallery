package com.dot.gallery.core.util

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

internal const val MAX_ENCODED_MEDIA_BYTES = 128L * 1024L * 1024L

internal class SizeLimitExceededException(maximumBytes: Long) : IOException(
    "Input exceeds the $maximumBytes byte limit",
)

internal class SizeLimitedInputStream(
    inputStream: InputStream,
    private val maximumBytes: Long,
) : FilterInputStream(inputStream) {

    private var consumedBytes = 0L

    init {
        require(maximumBytes >= 0L) { "Maximum byte count must not be negative" }
    }

    override fun read(): Int {
        val value = super.read()
        if (value != -1) {
            recordBytes(byteCount = 1L)
        }
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val readBytes = super.read(buffer, offset, length)
        if (readBytes > 0) {
            recordBytes(byteCount = readBytes.toLong())
        }
        return readBytes
    }

    override fun skip(byteCount: Long): Long {
        val skippedBytes = super.skip(byteCount)
        if (skippedBytes > 0L) {
            recordBytes(byteCount = skippedBytes)
        }
        return skippedBytes
    }

    private fun recordBytes(byteCount: Long) {
        if (byteCount > maximumBytes - consumedBytes) {
            throw SizeLimitExceededException(maximumBytes = maximumBytes)
        }
        consumedBytes += byteCount
    }
}
