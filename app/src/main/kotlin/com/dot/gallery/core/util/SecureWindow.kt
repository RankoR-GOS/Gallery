package com.dot.gallery.core.util

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.dot.gallery.core.Settings.Misc.getSecureMode
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

internal fun ComponentActivity.enforceSecureMode() {
    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    lifecycleScope.launch {
        getSecureMode(this@enforceSecureMode).collectLatest { enabled ->
            when {
                enabled -> window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                else -> window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }
}
