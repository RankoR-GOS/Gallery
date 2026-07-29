package com.dot.gallery.feature_node.presentation.search.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

internal class VectorUtilTest {

    @Test
    fun dot_returnsZeroForMismatchedDimensions() {
        val result = floatArrayOf(1f, 2f) dot floatArrayOf(1f)

        assertEquals(0f, result)
    }

    @Test
    fun normalizeL2_returnsZeroVectorForInvalidInput() {
        assertArrayEquals(
            floatArrayOf(0f, 0f),
            normalizeL2(inputArray = floatArrayOf(0f, 0f)),
            0f,
        )
        assertArrayEquals(
            floatArrayOf(0f),
            normalizeL2(inputArray = floatArrayOf(Float.NaN)),
            0f,
        )
    }
}
