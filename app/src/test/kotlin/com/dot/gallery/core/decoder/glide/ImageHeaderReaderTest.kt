package com.dot.gallery.core.decoder.glide

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

internal class ImageHeaderReaderTest {
    @Test
    fun readImageHeader_fillsHeaderFromOneByteReads() {
        val expected = avifHeader()
        val inputStream = OneByteInputStream(bytes = expected)

        val header = inputStream.readImageHeader()

        assertArrayEquals(expected, header)
        assertEquals(
            ImageFileFormat.AVIF,
            classifyImageHeader(header = header, length = header.size),
        )
    }

    @Test
    fun readImageHeader_stopsAtHeaderLimit() {
        val input = ByteArray(size = IMAGE_HEADER_BYTES + 10) { index -> index.toByte() }

        val header = ByteArrayInputStream(input).readImageHeader()

        assertEquals(IMAGE_HEADER_BYTES, header.size)
        assertArrayEquals(input.copyOf(newSize = IMAGE_HEADER_BYTES), header)
    }

    @Test
    fun readImageHeader_accumulatesSyntheticShortPreads() {
        val expected = avifHeader()
        var sourceOffset = 0

        val header = readImageHeader { destination, offset, length ->
            if (sourceOffset == expected.size) {
                0
            } else {
                val readBytes = minOf(2, length, expected.size - sourceOffset)
                expected.copyInto(
                    destination = destination,
                    destinationOffset = offset,
                    startIndex = sourceOffset,
                    endIndex = sourceOffset + readBytes,
                )
                sourceOffset += readBytes
                readBytes
            }
        }

        assertArrayEquals(expected, header)
        assertEquals(
            ImageFileFormat.AVIF,
            classifyImageHeader(header = header, length = header.size),
        )
    }

    private fun avifHeader(): ByteArray {
        return byteArrayOf(
            0x00,
            0x00,
            0x00,
            0x14,
            0x66,
            0x74,
            0x79,
            0x70,
            0x61,
            0x76,
            0x69,
            0x66,
            0x00,
            0x00,
            0x00,
            0x00,
            0x6D,
            0x69,
            0x66,
            0x31,
        )
    }
}

private class OneByteInputStream(
    private val bytes: ByteArray,
) : InputStream() {
    private var offset = 0

    override fun read(): Int {
        return when {
            offset == bytes.size -> -1
            else -> bytes[offset++].toInt() and 0xFF
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (this.offset == bytes.size) {
            return -1
        }
        buffer[offset] = bytes[this.offset++]
        return 1
    }
}
