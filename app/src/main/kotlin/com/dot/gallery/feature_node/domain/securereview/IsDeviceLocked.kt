package com.dot.gallery.feature_node.domain.securereview

import android.app.KeyguardManager
import javax.inject.Inject

internal fun interface IsDeviceLocked {
    operator fun invoke(): Boolean
}

internal class IsDeviceLockedImpl @Inject constructor(
    private val keyguardManager: KeyguardManager,
) : IsDeviceLocked {

    override operator fun invoke(): Boolean {
        return keyguardManager.isDeviceLocked
    }
}
