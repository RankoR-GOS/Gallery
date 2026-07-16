package com.dot.gallery.core.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

class SizeLimitedInputStreamTest {

    @Test
    fun `read permits input exactly at limit`() {
        val bytes = ByteArray(16) { index -> index.toByte() }
        val result = SizeLimitedInputStream(
            inputStream = ByteArrayInputStream(bytes),
            maximumBytes = bytes.size.toLong(),
        ).use { inputStream ->
            inputStream.readBytes()
        }

        assertArrayEquals(bytes, result)
    }

    @Test
    fun `read rejects input beyond limit`() {
        val inputStream = SizeLimitedInputStream(
            inputStream = ByteArrayInputStream(ByteArray(17)),
            maximumBytes = 16L,
        )

        assertThrows(IOException::class.java) {
            inputStream.use { stream -> stream.readBytes() }
        }
    }

    @Test
    fun `skip contributes to consumed byte count`() {
        val inputStream = SizeLimitedInputStream(
            inputStream = ByteArrayInputStream(ByteArray(17)),
            maximumBytes = 16L,
        )

        assertEquals(12L, inputStream.skip(12L))
        assertThrows(IOException::class.java) {
            inputStream.read(ByteArray(5))
        }
    }
}
