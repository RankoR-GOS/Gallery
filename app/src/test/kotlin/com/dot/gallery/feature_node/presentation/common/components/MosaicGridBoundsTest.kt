package com.dot.gallery.feature_node.presentation.common.components

import org.junit.Assert.assertEquals
import org.junit.Test

class MosaicGridBoundsTest {

    @Test
    fun `columns are clamped to supported bounds`() {
        assertEquals(3, safeMosaicColumns(Int.MIN_VALUE))
        assertEquals(3, safeMosaicColumns(0))
        assertEquals(3, safeMosaicColumns(3))
        assertEquals(4, safeMosaicColumns(4))
        assertEquals(5, safeMosaicColumns(5))
        assertEquals(6, safeMosaicColumns(6))
        assertEquals(6, safeMosaicColumns(Int.MAX_VALUE))
    }

    @Test
    fun `spans are always valid for effective column count`() {
        assertEquals(1, safeMosaicSpan(span = Int.MIN_VALUE, columns = Int.MIN_VALUE))
        assertEquals(1, safeMosaicSpan(span = 0, columns = 4))
        assertEquals(3, safeMosaicSpan(span = Int.MAX_VALUE, columns = 3))
        assertEquals(4, safeMosaicSpan(span = 4, columns = 4))
        assertEquals(6, safeMosaicSpan(span = Int.MAX_VALUE, columns = Int.MAX_VALUE))
    }
}
