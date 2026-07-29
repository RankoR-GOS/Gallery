package com.dot.gallery.core.sandbox

import com.dot.gallery.core.util.SizeLimitExceededException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

internal class EncodedMediaTransferTest {
    @Test
    fun transferEncodedMedia_streamsInputWithoutMaterializingSource() {
        val inputStream = GeneratedInputStream(byteCount = 1_000_000L)
        val outputStream = CountingOutputStream()

        val transferredBytes = transferEncodedMedia(
            inputStream = inputStream,
            outputStream = outputStream,
            maximumBytes = 1_000_000L,
        )

        assertEquals(1_000_000L, transferredBytes)
        assertEquals(1_000_000L, outputStream.byteCount)
    }

    @Test
    fun transferEncodedMedia_rejectsLazyInputBeyondLimit() {
        val inputStream = GeneratedInputStream(byteCount = 1_000_001L)

        assertThrows(SizeLimitExceededException::class.java) {
            transferEncodedMedia(
                inputStream = inputStream,
                outputStream = CountingOutputStream(),
                maximumBytes = 1_000_000L,
            )
        }
    }

    @Test
    fun transferEncodedMedia_stopsReadingAfterEarlyOutputFailure() {
        val inputStream = GeneratedInputStream(byteCount = 1_000_000L)

        assertThrows(IOException::class.java) {
            transferEncodedMedia(
                inputStream = inputStream,
                outputStream = FailingOutputStream(),
                maximumBytes = 1_000_000L,
            )
        }

        assertTrue(inputStream.bytesRead <= EXPECTED_TRANSFER_BUFFER_BYTES)
    }
}

private class GeneratedInputStream(
    private val byteCount: Long,
) : InputStream() {
    private var position = 0L

    val bytesRead: Long
        get() = position

    override fun read(): Int {
        return when {
            position == byteCount -> -1
            else -> {
                position++
                0
            }
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val remainingBytes = byteCount - position
        if (remainingBytes == 0L) {
            return -1
        }
        val readBytes = minOf(length.toLong(), remainingBytes).toInt()
        position += readBytes
        return readBytes
    }
}

private class FailingOutputStream : OutputStream() {
    override fun write(value: Int) {
        throw IOException("Synthetic output failure")
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        throw IOException("Synthetic output failure")
    }
}

private class CountingOutputStream : OutputStream() {
    var byteCount = 0L
        private set

    override fun write(value: Int) {
        byteCount++
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        byteCount += length
    }
}

private const val EXPECTED_TRANSFER_BUFFER_BYTES = 64L * 1024L
