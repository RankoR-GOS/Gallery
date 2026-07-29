package com.dot.gallery.feature_node.data.util

import androidx.room.TypeConverter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Stores an embedding as a little-endian blob of `[magic][dimensions][floats]`. The magic number and
 * the length prefix let a row written by a different build be recognised as unreadable instead of
 * being decoded into plausible-looking noise.
 */
object EmbeddingBlobConverter {

    private const val MAGIC = 0x31424D45
    private const val HEADER_SIZE_BYTES = Int.SIZE_BYTES * 2
    private const val MAX_DIMENSIONS = 16_384

    @TypeConverter
    fun encode(embedding: FloatArray): ByteArray {
        require(embedding.isNotEmpty()) { "Embedding must not be empty" }
        require(embedding.size <= MAX_DIMENSIONS) {
            "Embedding contains too many dimensions: ${embedding.size}"
        }
        require(embedding.all { value -> value.isFinite() }) {
            "Embedding contains a non-finite value"
        }
        return ByteBuffer.allocate(HEADER_SIZE_BYTES + embedding.size * Float.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                putInt(MAGIC)
                putInt(embedding.size)
                embedding.forEach { value -> putFloat(value) }
            }
            .array()
    }

    @TypeConverter
    fun decode(data: ByteArray): FloatArray {
        require(data.size >= HEADER_SIZE_BYTES) { "Embedding data is truncated" }
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        require(buffer.int == MAGIC) { "Embedding data has an unsupported format" }

        val dimensions = buffer.int
        require(dimensions in 1..MAX_DIMENSIONS) {
            "Invalid embedding dimension count: ${dimensions}"
        }
        val expectedSize = HEADER_SIZE_BYTES.toLong() + dimensions.toLong() * Float.SIZE_BYTES
        require(data.size.toLong() == expectedSize) { "Embedding data has an invalid size" }
        return FloatArray(dimensions) { buffer.float }.also { embedding ->
            require(embedding.all { value -> value.isFinite() }) {
                "Embedding contains a non-finite value"
            }
        }
    }
}
