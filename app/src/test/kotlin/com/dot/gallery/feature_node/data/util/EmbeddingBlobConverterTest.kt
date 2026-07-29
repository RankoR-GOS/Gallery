package com.dot.gallery.feature_node.data.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

internal class EmbeddingBlobConverterTest {

    private val converter = EmbeddingBlobConverter

    @Test
    fun encodeAndDecode_preservesEmbedding() {
        val embedding = floatArrayOf(-1.25f, 0f, 3.5f)

        val decoded = converter.decode(data = converter.encode(embedding = embedding))

        assertArrayEquals(embedding, decoded, 0f)
    }

    @Test
    fun decode_rejectsTruncatedData() {
        val encoded = converter.encode(embedding = floatArrayOf(1f, 2f))

        assertThrows(IllegalArgumentException::class.java) {
            converter.decode(data = encoded.copyOf(encoded.size - 1))
        }
    }

    @Test
    fun decode_rejectsUnknownFormat() {
        val encoded = converter.encode(embedding = floatArrayOf(1f)).apply {
            this[0] = 0
        }

        assertThrows(IllegalArgumentException::class.java) {
            converter.decode(data = encoded)
        }
    }

    @Test
    fun encode_rejectsEmptyAndNonFiniteEmbeddings() {
        assertThrows(IllegalArgumentException::class.java) {
            converter.encode(embedding = floatArrayOf())
        }
        assertThrows(IllegalArgumentException::class.java) {
            converter.encode(embedding = floatArrayOf(Float.NaN))
        }
        assertThrows(IllegalArgumentException::class.java) {
            converter.encode(embedding = floatArrayOf(Float.POSITIVE_INFINITY))
        }
    }
}
