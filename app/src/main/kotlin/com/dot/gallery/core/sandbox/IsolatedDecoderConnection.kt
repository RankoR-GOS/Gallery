package com.dot.gallery.core.sandbox

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Messenger
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

@Singleton
internal class IsolatedDecoderConnection @Inject constructor(
    @ApplicationContext context: Context,
) : IsolatedServiceConnection(context = context, instanceName = null)

@Singleton
internal class MediaAnalysisDecoderConnection @Inject constructor(
    @ApplicationContext context: Context,
) : IsolatedServiceConnection(context = context, instanceName = MEDIA_ANALYSIS_INSTANCE_NAME) {

    companion object {
        private const val MEDIA_ANALYSIS_INSTANCE_NAME = "media_analysis"
    }
}

internal open class IsolatedServiceConnection(
    private val context: Context,
    private val instanceName: String?,
) {
    @Volatile
    private var bindingRegistered = false

    private val connectionMutex = Mutex()
    private val serviceMessenger = MutableStateFlow<Messenger?>(null)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            serviceMessenger.value = binder?.let(::Messenger)
            Log.d(TAG, "Service connected")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceMessenger.value = null
            Log.w(TAG, "Service disconnected")
        }

        override fun onBindingDied(name: ComponentName?) {
            releaseBinding()
            Log.w(TAG, "Service binding died")
        }

        override fun onNullBinding(name: ComponentName?) {
            releaseBinding()
            Log.w(TAG, "Service returned a null binding")
        }
    }

    suspend fun getMessenger(): Messenger? {
        serviceMessenger.value?.let { messenger ->
            return messenger
        }

        return connectionMutex.withLock {
            serviceMessenger.value?.let { messenger ->
                return@withLock messenger
            }

            val registered = withContext(Dispatchers.Main.immediate) {
                registerBindingIfNeeded()
            }
            if (!registered) {
                return@withLock null
            }

            val messenger = withTimeoutOrNull(timeMillis = BIND_TIMEOUT_MILLIS) {
                serviceMessenger.filterNotNull().first()
            }
            if (messenger == null) {
                withContext(Dispatchers.Main.immediate) {
                    if (serviceMessenger.value == null) {
                        releaseBinding()
                    }
                }
                Log.w(TAG, "Bind timed out")
            }
            messenger
        }
    }

    fun unbind() {
        releaseBinding()
    }

    private fun registerBindingIfNeeded(): Boolean {
        if (bindingRegistered) {
            return true
        }

        val intent = Intent(context, IsolatedDecoderService::class.java)
        val bindingStarted = when (instanceName) {
            null -> context.bindService(
                intent,
                serviceConnection,
                Context.BIND_AUTO_CREATE,
            )

            else -> context.bindIsolatedService(
                intent,
                Context.BIND_AUTO_CREATE,
                instanceName,
                context.mainExecutor,
                serviceConnection,
            )
        }
        bindingRegistered = bindingStarted
        if (!bindingStarted) {
            Log.w(TAG, "bindService returned false")
        }
        return bindingStarted
    }

    private fun releaseBinding() {
        serviceMessenger.value = null
        if (!bindingRegistered) {
            return
        }

        bindingRegistered = false
        runCatching {
            context.unbindService(serviceConnection)
        }
    }

    companion object {
        private const val TAG = "IsolatedDecoderConnection"
        private const val BIND_TIMEOUT_MILLIS = 5_000L
    }
}
