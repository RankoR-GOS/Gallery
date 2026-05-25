package com.dot.gallery.feature_node.presentation.security

import android.app.KeyguardManager
import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS
import androidx.biometric.BiometricPrompt
import androidx.biometric.BiometricPrompt.PromptInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

@Composable
internal fun rememberBiometricManager(): BiometricManager {
    val context = LocalContext.current
    return remember(context) {
        BiometricManager.from(context)
    }
}

@Composable
internal fun rememberBiometricCallback(
    onSuccess: () -> Unit,
    onFailed: () -> Unit,
): BiometricPrompt.AuthenticationCallback {
    val currentOnSuccess by rememberUpdatedState(onSuccess)
    val currentOnFailed by rememberUpdatedState(onFailed)
    return remember {
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                currentOnFailed()
            }

            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                currentOnSuccess()
            }
        }
    }
}

@Composable
internal fun rememberBiometricState(
    title: String,
    subtitle: String,
    onSuccess: () -> Unit,
    onFailed: () -> Unit,
): BiometricState {
    val activity = LocalActivity.current as? FragmentActivity

    val biometricManager = rememberBiometricManager()
    val callback = rememberBiometricCallback(
        onSuccess = onSuccess,
        onFailed = onFailed,
    )

    return remember(activity, biometricManager, title, subtitle) {
        val promptInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            PromptInfo.Builder()
                .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
                .setTitle(title)
                .setSubtitle(subtitle)
                .build()
        } else {
            @Suppress("DEPRECATION")
            PromptInfo.Builder()
                .setDeviceCredentialAllowed(true)
                .setTitle(title)
                .setSubtitle(subtitle)
                .build()
        }
        BiometricState(
            activity = activity,
            biometricManager = biometricManager,
            promptInfo = promptInfo,
            callback = callback,
        )
    }
}

internal class BiometricState(
    private val activity: FragmentActivity?,
    biometricManager: BiometricManager,
    private val promptInfo: PromptInfo,
    private val callback: BiometricPrompt.AuthenticationCallback,
) {
    val isSupported by mutableStateOf(
        activity?.let { currentActivity ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                biometricManager.canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL) == BIOMETRIC_SUCCESS
            } else {
                val keyguardManager = currentActivity.getSystemService(KeyguardManager::class.java)
                keyguardManager?.isDeviceSecure == true
            }
        } == true
    )

    fun authenticate() {
        if (isSupported) {
            val currentActivity = activity ?: return

            val executor = ContextCompat.getMainExecutor(currentActivity)
            val prompt = BiometricPrompt(currentActivity, executor, callback)
            prompt.authenticate(promptInfo)
        }
    }
}
