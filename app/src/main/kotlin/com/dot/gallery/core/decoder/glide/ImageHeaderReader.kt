package com.dot.gallery.core.decoder.glide

import android.system.Os
import java.io.FileDescriptor
import java.io.IOException
import java.io.InputStream

internal fun InputStream.readImageHeader(): ByteArray {
    return readImageHeader { header, offset, length ->
        read(header, offset, length)
    }
}

internal fun FileDescriptor.preadImageHeader(): ByteArray {
    return readImageHeader { header, offset, length ->
        Os.pread(
            this,
            header,
            offset,
            length,
            offset.toLong(),
        )
    }
}

internal fun readImageHeader(
    readChunk: (header: ByteArray, offset: Int, length: Int) -> Int,
): ByteArray {
    val header = ByteArray(IMAGE_HEADER_BYTES)
    var totalBytes = 0
    while (totalBytes < header.size) {
        val remainingBytes = header.size - totalBytes
        val readBytes = readChunk(header, totalBytes, remainingBytes)
        when {
            readBytes <= 0 -> break
            readBytes > remainingBytes -> throw IOException("Header reader returned too many bytes")
            else -> totalBytes += readBytes
        }
    }
    return header.copyOf(newSize = totalBytes)
}
