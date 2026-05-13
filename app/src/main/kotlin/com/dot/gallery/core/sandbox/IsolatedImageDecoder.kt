/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.core.sandbox

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.SharedMemory
import android.util.Size as AndroidSize
import com.dot.gallery.core.sandbox.IsolatedDecoderService.Companion.KEY_BYTE_COUNT
import com.dot.gallery.core.sandbox.IsolatedDecoderService.Companion.KEY_DECODED_BYTE_COUNT
import com.dot.gallery.core.sandbox.IsolatedDecoderService.Companion.KEY_DECODED_CONFIG
import com.dot.gallery.core.sandbox.IsolatedDecoderService.Companion.KEY_DECODED_HEIGHT
import com.dot.gallery.core.sandbox.IsolatedDecoderService.Companion.KEY_DECODED_WIDTH
import com.dot.gallery.core.sandbox.IsolatedDecoderService.Companion.KEY_ERROR
import com.dot.gallery.core.sandbox.IsolatedDecoderService.Companion.KEY_ERROR_MESSAGE
import com.dot.gallery.core.sandbox.IsolatedDecoderService.Companion.KEY_IMAGE_HEIGHT
import com.dot.gallery.core.sandbox.IsolatedDecoderService.Companion.KEY_IMAGE_WIDTH
import com.dot.gallery.core.sandbox.IsolatedDecoderService.Companion.KEY_INPUT_SHM
import com.dot.gallery.core.sandbox.IsolatedDecoderService.Companion.KEY_MIME_TYPE
import com.dot.gallery.core.sandbox.IsolatedDecoderService.Companion.KEY_OUTPUT_SHM
import com.dot.gallery.core.sandbox.IsolatedDecoderService.Companion.KEY_TARGET_HEIGHT
import com.dot.gallery.core.sandbox.IsolatedDecoderService.Companion.KEY_TARGET_WIDTH
import com.dot.gallery.core.sandbox.IsolatedDecoderService.Companion.MSG_DECODE
import com.dot.gallery.core.sandbox.IsolatedDecoderService.Companion.MSG_GET_SIZE
import com.dot.gallery.feature_node.presentation.util.printDebug
import com.dot.gallery.feature_node.presentation.util.printWarning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Client for [IsolatedDecoderService].
 */
class IsolatedImageDecoder(private val context: Context) {

    @Volatile
    private var serviceMessenger: Messenger? = null

    @Volatile
    private var bound = false

    private val bindMutex = Mutex()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            serviceMessenger = Messenger(binder)
            bound = true
            printDebug("IsolatedImageDecoder: service connected")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceMessenger = null
            bound = false
            printWarning("IsolatedImageDecoder: service disconnected")
        }
    }

    suspend fun ensureBound() {
        if (bound && serviceMessenger != null) return
        bindMutex.withLock {
            if (bound && serviceMessenger != null) return
            withContext(Dispatchers.Main) {
                val intent = Intent(context, IsolatedDecoderService::class.java)
                context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            }
            var attempts = 0
            while (serviceMessenger == null && attempts < 50) {
                kotlinx.coroutines.delay(100)
                attempts++
            }
            if (serviceMessenger == null) {
                printWarning("IsolatedImageDecoder: bind timed out after 5s")
            }
        }
    }

    fun unbind() {
        if (bound) {
            runCatching {
                context.unbindService(connection)
            }
            bound = false
            serviceMessenger = null
        }
    }

    suspend fun getSize(encodedBytes: ByteArray, mimeType: String): AndroidSize? {
        return withContext(Dispatchers.IO) {
            ensureBound()
            val messenger = serviceMessenger ?: return@withContext null
            val inputSharedMemory = writeInputBytes(encodedBytes) ?: return@withContext null
            val resultBundle = try {
                sendAndReceive(
                    messenger = messenger,
                    what = MSG_GET_SIZE,
                    data = Bundle().apply {
                        putParcelable(KEY_INPUT_SHM, inputSharedMemory)
                        putString(KEY_MIME_TYPE, mimeType)
                        putInt(KEY_BYTE_COUNT, encodedBytes.size)
                    },
                )
            } finally {
                inputSharedMemory.close()
            }

            if (resultBundle == null || resultBundle.getBoolean(KEY_ERROR, false)) {
                val errorMessage = resultBundle?.getString(KEY_ERROR_MESSAGE, "Unknown error") ?: "Timeout"
                printWarning("IsolatedImageDecoder: size read failed: $errorMessage")
                return@withContext null
            }

            val width = resultBundle.getInt(KEY_IMAGE_WIDTH, 0)
            val height = resultBundle.getInt(KEY_IMAGE_HEIGHT, 0)
            if (width <= 0 || height <= 0) {
                null
            } else {
                AndroidSize(width, height)
            }
        }
    }

    suspend fun decode(
        encodedBytes: ByteArray,
        mimeType: String,
        targetWidth: Int = 0,
        targetHeight: Int = 0,
    ): Bitmap? {
        return withContext(Dispatchers.IO) {
            val startNs = System.nanoTime()
            ensureBound()
            val messenger = serviceMessenger ?: return@withContext null
            val inputSharedMemory = writeInputBytes(encodedBytes) ?: return@withContext null

            val resultBundle = try {
                sendAndReceive(
                    messenger = messenger,
                    what = MSG_DECODE,
                    data = Bundle().apply {
                        putParcelable(KEY_INPUT_SHM, inputSharedMemory)
                        putString(KEY_MIME_TYPE, mimeType)
                        putInt(KEY_TARGET_WIDTH, targetWidth)
                        putInt(KEY_TARGET_HEIGHT, targetHeight)
                        putInt(KEY_BYTE_COUNT, encodedBytes.size)
                    },
                )
            } finally {
                inputSharedMemory.close()
            }

            if (resultBundle == null || resultBundle.getBoolean(KEY_ERROR, false)) {
                val errorMessage = resultBundle?.getString(KEY_ERROR_MESSAGE, "Unknown error") ?: "Timeout"
                printWarning("IsolatedImageDecoder: decode failed: $errorMessage")
                return@withContext null
            }

            val bitmap = readBitmapFromResult(resultBundle)
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
            printDebug("IsolatedImageDecoder: decode took ${elapsedMs}ms (${bitmap?.width}x${bitmap?.height})")
            bitmap
        }
    }

    private fun writeInputBytes(encodedBytes: ByteArray): SharedMemory? {
        val inputSharedMemory = try {
            SharedMemory.create("encoded_input", encodedBytes.size)
        } catch (exception: Exception) {
            printWarning("IsolatedImageDecoder: failed to create SharedMemory: ${exception.message}")
            return null
        }

        return try {
            val inputBuffer = inputSharedMemory.mapReadWrite()
            inputBuffer.put(encodedBytes)
            SharedMemory.unmap(inputBuffer)
            inputSharedMemory
        } catch (exception: Exception) {
            inputSharedMemory.close()
            printWarning("IsolatedImageDecoder: failed to write SharedMemory: ${exception.message}")
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun readBitmapFromResult(bundle: Bundle): Bitmap? {
        bundle.classLoader = SharedMemory::class.java.classLoader
        val outputSharedMemory = bundle.getParcelable<SharedMemory>(KEY_OUTPUT_SHM) ?: return null
        val width = bundle.getInt(KEY_DECODED_WIDTH, 0)
        val height = bundle.getInt(KEY_DECODED_HEIGHT, 0)
        val byteCount = bundle.getInt(KEY_DECODED_BYTE_COUNT, 0)
        val configName = bundle.getString(KEY_DECODED_CONFIG, "ARGB_8888")

        if (width <= 0 || height <= 0 || byteCount <= 0) {
            outputSharedMemory.close()
            return null
        }

        return try {
            val config = runCatching {
                Bitmap.Config.valueOf(configName)
            }.getOrDefault(Bitmap.Config.ARGB_8888)
            val bitmap = Bitmap.createBitmap(width, height, config)
            val outputBuffer = outputSharedMemory.mapReadOnly()
            bitmap.copyPixelsFromBuffer(outputBuffer)
            SharedMemory.unmap(outputBuffer)
            bitmap
        } catch (exception: Exception) {
            printWarning("IsolatedImageDecoder: failed to reconstruct bitmap: ${exception.message}")
            null
        } finally {
            outputSharedMemory.close()
        }
    }

    private suspend fun sendAndReceive(messenger: Messenger, what: Int, data: Bundle): Bundle? {
        return withTimeoutOrNull(SERVICE_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val replyHandler = Handler(Looper.getMainLooper()) { msg ->
                    val result = msg.data
                    result.classLoader = SharedMemory::class.java.classLoader
                    if (continuation.isActive) {
                        continuation.resume(result)
                    }
                    true
                }

                val message = Message.obtain().apply {
                    this.what = what
                    this.data = data
                    this.replyTo = Messenger(replyHandler)
                }

                try {
                    messenger.send(message)
                } catch (exception: Exception) {
                    printWarning("IsolatedImageDecoder: send failed: ${exception.message}")
                    if (continuation.isActive) {
                        continuation.resume(null)
                    }
                }

                continuation.invokeOnCancellation { }
            }
        }
    }

    companion object {
        private const val SERVICE_TIMEOUT_MS = 30_000L
    }
}
