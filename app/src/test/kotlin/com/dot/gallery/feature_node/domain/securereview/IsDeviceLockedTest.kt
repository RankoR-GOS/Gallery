package com.dot.gallery.feature_node.domain.securereview

import android.app.KeyguardManager
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
internal class IsDeviceLockedTest {

    @Test
    fun invoke_returnsKeyguardDeviceLockedState() {
        val keyguardManager = mockk<KeyguardManager>()
        val isDeviceLocked = IsDeviceLockedImpl(
            keyguardManager = keyguardManager,
        )

        every { keyguardManager.isDeviceLocked } returns true
        assertTrue(isDeviceLocked())

        every { keyguardManager.isDeviceLocked } returns false
        assertFalse(isDeviceLocked())
    }
}
