package com.dot.gallery.feature_node.presentation.mediaview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression tests for [PendingRotation].
 *
 * Grouped media share a single pager page, so rotation state used to survive a member switch and
 * apply the previewed angle of one photo to another one. The pending angle now carries the id of
 * the media it belongs to, and these cover the two rules that keep them apart.
 */
class PendingRotationTest {

    @Test
    fun normalizesIntoASingleTurn() {
        assertEquals(90, PendingRotation.of(1L, 90)?.degrees)
        assertEquals(270, PendingRotation.of(1L, 270)?.degrees)
        assertEquals(90, PendingRotation.of(1L, 450)?.degrees)
        assertEquals(270, PendingRotation.of(1L, -90)?.degrees)
    }

    @Test
    fun clearsItselfOnceBackToZero() {
        assertNull(PendingRotation.of(1L, 0))
        // Four long presses in a row return to the original orientation.
        assertNull(PendingRotation.of(1L, 360))
        assertNull(PendingRotation.of(1L, -360))
    }

    @Test
    fun appliesOnlyToTheMediaItWasMadeFor() {
        val pending = PendingRotation.of(1L, 90)!!
        assertEquals(90, pending.degreesFor(1L))
        assertEquals(0, pending.degreesFor(2L))
    }
}
