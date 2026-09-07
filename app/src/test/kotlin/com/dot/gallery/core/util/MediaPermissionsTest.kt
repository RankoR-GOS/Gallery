package com.dot.gallery.core.util

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Test

internal class MediaPermissionsTest {
    @Test
    fun mediaAccess_acceptsFullSingleTypeAndSelectedAccessWithoutRequiringLocation() {
        val permissions = listOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            Manifest.permission.ACCESS_MEDIA_LOCATION,
        )
        for (mask in 0 until 16) {
            val granted = permissions.filterIndexed { index, _ ->
                mask and (1 shl index) != 0
            }.toSet()
            assertEquals("Library access: $granted", mask and 7 != 0, hasMediaAccess(granted))
            assertEquals("Cleanup allowed: $granted", mask and 3 == 3, hasFullMediaAccess(granted))
        }
    }
}
