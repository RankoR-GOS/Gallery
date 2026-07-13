package com.dot.gallery.feature_node.presentation.securereview

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.content.ContextCompat
import com.dot.gallery.feature_node.domain.securereview.AuthorizeSecureReviewRequest
import com.dot.gallery.feature_node.domain.securereview.IsDeviceLocked
import com.dot.gallery.feature_node.presentation.util.LocalHazeState
import com.dot.gallery.feature_node.presentation.util.toggleOrientation
import com.dot.gallery.ui.theme.GalleryTheme
import dagger.hilt.android.AndroidEntryPoint
import dev.chrisbanes.haze.rememberHazeState
import javax.inject.Inject

@AndroidEntryPoint
class SecureReviewActivity : ComponentActivity() {

    @Inject
    internal lateinit var authorizeSecureReviewRequest: AuthorizeSecureReviewRequest

    @Inject
    internal lateinit var isDeviceLocked: IsDeviceLocked

    private var isReceiverRegistered = false
    private val sessionEndReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF,
                Intent.ACTION_USER_PRESENT,
                -> {
                    finishSecureReview()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val request = authorizeSecureReviewRequest(
            intent = intent,
            caller = initialCaller,
        )
        if (request == null || !isDeviceLocked()) {
            finishSecureReview()
            return
        }

        configureSecureWindow()
        registerSessionEndReceiver()
        enableEdgeToEdge()

        setContent {
            GalleryTheme {
                val hazeState = rememberHazeState()
                CompositionLocalProvider(LocalHazeState provides hazeState) {
                    SecureReviewScreen(
                        request = request,
                        onFinish = ::finishSecureReview,
                        onToggleOrientation = ::toggleOrientation,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        finishSecureReview()
    }

    override fun onResume() {
        super.onResume()

        if (!isFinishing && !isDeviceLocked()) {
            finishSecureReview()
        }
    }

    override fun onDestroy() {
        if (isReceiverRegistered) {
            unregisterReceiver(sessionEndReceiver)
            isReceiverRegistered = false
        }

        super.onDestroy()
    }

    private fun registerSessionEndReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }

        ContextCompat.registerReceiver(
            this,
            sessionEndReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED,
        )

        isReceiverRegistered = true
    }

    private fun configureSecureWindow() {
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setRecentsScreenshotEnabled(false)
        setShowWhenLocked(true)
    }

    private fun finishSecureReview() {
        setShowWhenLocked(false)
        if (!isFinishing) {
            finishAndRemoveTask()
        }
    }
}
