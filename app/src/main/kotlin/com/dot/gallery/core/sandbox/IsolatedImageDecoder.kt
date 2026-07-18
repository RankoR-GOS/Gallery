package com.dot.gallery.core.sandbox

import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import android.util.Log
import android.util.Size as AndroidSize
import androidx.core.graphics.createBitmap
import com.dot.gallery.core.sandbox.IsolatedDecoderService.Companion.KEY_DECODED_BYTE_COUNT
import com.dot.gallery.core.sandbox.IsolatedDecoderService.Companion.KEY_DECODED_CONFIG
import com.dot.gallery.core.sandbox.IsolatedDecoderService.Companion.KEY_DECODED_HEIGHT
import com.dot.gallery.core.sandbox.IsolatedDecoderService.Companion.KEY_DECODED_WIDTH
import com.dot.gallery.core.sandbox.IsolatedDecoderService.Companion.KEY_ERROR
import com.dot.gallery.core.sandbox.IsolatedDecoderService.Companion.KEY_ERROR_MESSAGE
import com.dot.gallery.core.sandbox.IsolatedDecoderService.Companion.KEY_IMAGE_HEIGHT
import com.dot.gallery.core.sandbox.IsolatedDecoderService.Companion.KEY_IMAGE_WIDTH
import com.dot.gallery.core.sandbox.IsolatedDecoderService.Companion.KEY_INPUT_PFD
import com.dot.gallery.core.sandbox.IsolatedDecoderService.Companion.KEY_MAX_DECODED_BYTES
import com.dot.gallery.core.sandbox.IsolatedDecoderService.Companion.KEY_MIME_TYPE
import com.dot.gallery.core.sandbox.IsolatedDecoderService.Companion.KEY_ORIGINAL_HEIGHT
import com.dot.gallery.core.sandbox.IsolatedDecoderService.Companion.KEY_ORIGINAL_WIDTH
import com.dot.gallery.core.sandbox.IsolatedDecoderService.Companion.KEY_OUTPUT_SHM
import com.dot.gallery.core.sandbox.IsolatedDecoderService.Companion.KEY_TARGET_HEIGHT
import com.dot.gallery.core.sandbox.IsolatedDecoderService.Companion.KEY_TARGET_WIDTH
import com.dot.gallery.core.sandbox.IsolatedDecoderService.Companion.MSG_DECODE
import com.dot.gallery.core.sandbox.IsolatedDecoderService.Companion.MSG_GET_SIZE
import com.dot.gallery.injection.qualifier.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.math.min

@Singleton
internal class IsolatedImageDecoder @Inject constructor(
    private val connection: IsolatedDecoderConnection,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    private val requestMutex = Mutex()

    fun unbind() {
        connection.unbind()
    }

    suspend fun getSize(inputStream: InputStream, mimeType: String): AndroidSize? {
        return requestMutex.withLock {
            val resultBundle = request(
                inputStream = inputStream,
                mimeType = mimeType,
                messageType = MSG_GET_SIZE,
                targetWidth = 0,
                targetHeight = 0,
            ) ?: return@withLock null

            val width = resultBundle.getInt(KEY_IMAGE_WIDTH, 0)
            val height = resultBundle.getInt(KEY_IMAGE_HEIGHT, 0)
            when {
                width <= 0 || height <= 0 -> null
                else -> AndroidSize(width, height)
            }
        }
    }

    suspend fun decode(
        inputStream: InputStream,
        mimeType: String,
        targetWidth: Int = 0,
        targetHeight: Int = 0,
    ): IsolatedImageDecodeResult? {
        return requestMutex.withLock {
            val startNanoseconds = System.nanoTime()
            val resultBundle = request(
                inputStream = inputStream,
                mimeType = mimeType,
                messageType = MSG_DECODE,
                targetWidth = targetWidth,
                targetHeight = targetHeight,
            ) ?: return@withLock null

            val result = readDecodeResult(bundle = resultBundle) ?: return@withLock null
            val elapsedMilliseconds = (System.nanoTime() - startNanoseconds) / 1_000_000
            Log.d(
                TAG,
                "Decode took ${elapsedMilliseconds}ms " +
                    "(${result.bitmap.width}x${result.bitmap.height})",
            )
            result
        }
    }

    private suspend fun request(
        inputStream: InputStream,
        mimeType: String,
        messageType: Int,
        targetWidth: Int,
        targetHeight: Int,
    ): Bundle? {
        return withTimeoutOrNull(timeMillis = SERVICE_TIMEOUT_MILLIS) {
            withContext(context = ioDispatcher) {
                val messenger = connection.getMessenger() ?: return@withContext null
                transferAndSend(
                    messenger = messenger,
                    inputStream = inputStream,
                    mimeType = mimeType,
                    messageType = messageType,
                    targetWidth = targetWidth,
                    targetHeight = targetHeight,
                )
            }
        }
    }

    private suspend fun transferAndSend(
        messenger: Messenger,
        inputStream: InputStream,
        mimeType: String,
        messageType: Int,
        targetWidth: Int,
        targetHeight: Int,
    ): Bundle? {
        return coroutineScope {
            val (readDescriptor, writeDescriptor) = ParcelFileDescriptor.createReliablePipe()
            val writerJob = async(context = ioDispatcher) {
                writeInput(
                    inputStream = inputStream,
                    writeDescriptor = writeDescriptor,
                )
            }

            try {
                val result = try {
                    sendAndReceive(
                        messenger = messenger,
                        what = messageType,
                        data = Bundle().apply {
                            putParcelable(KEY_INPUT_PFD, readDescriptor)
                            putString(KEY_MIME_TYPE, mimeType)
                            putInt(KEY_TARGET_WIDTH, targetWidth)
                            putInt(KEY_TARGET_HEIGHT, targetHeight)
                            putLong(KEY_MAX_DECODED_BYTES, decodedBitmapBudgetBytes())
                        },
                    )
                } finally {
                    closeQuietly(readDescriptor)
                }
                writerJob.await()?.let { failure ->
                    Log.w(TAG, "Input transfer failed: ${failure.message}")
                    return@coroutineScope null
                }
                result
            } finally {
                if (!writerJob.isCompleted) {
                    closeQuietly(inputStream)
                }
                closeQuietly(writeDescriptor)
                cancelWriter(job = writerJob)
            }
        }
    }

    private fun writeInput(
        inputStream: InputStream,
        writeDescriptor: ParcelFileDescriptor,
    ): Exception? {
        val outputStream = ParcelFileDescriptor.AutoCloseOutputStream(writeDescriptor)
        return try {
            transferEncodedMedia(
                inputStream = inputStream,
                outputStream = outputStream,
            )
            outputStream.flush()
            outputStream.close()
            null
        } catch (failure: Exception) {
            runCatching {
                writeDescriptor.closeWithError(failure.message ?: "Input transfer failed")
            }
            runCatching {
                outputStream.close()
            }
            failure
        }
    }

    private suspend fun sendAndReceive(messenger: Messenger, what: Int, data: Bundle): Bundle? {
        return suspendCancellableCoroutine { continuation ->
            val replyHandler = Handler(Looper.getMainLooper()) { message ->
                val result = message.data
                result.classLoader = SharedMemory::class.java.classLoader
                if (continuation.isActive) {
                    continuation.resume(result)
                } else {
                    closeOutputSharedMemory(bundle = result)
                }
                true
            }
            val message = Message.obtain().apply {
                this.what = what
                this.data = data
                replyTo = Messenger(replyHandler)
            }

            try {
                messenger.send(message)
            } catch (failure: Exception) {
                Log.w(TAG, "Send failed: ${failure.message}")
                if (continuation.isActive) {
                    continuation.resume(null)
                }
            }
        }?.takeUnless { result ->
            val hasError = result.getBoolean(KEY_ERROR, false)
            if (hasError) {
                val message = result.getString(KEY_ERROR_MESSAGE, "Unknown error")
                Log.w(TAG, "Request failed: $message")
                closeOutputSharedMemory(bundle = result)
            }
            hasError
        }
    }

    internal fun readDecodeResult(bundle: Bundle): IsolatedImageDecodeResult? {
        bundle.classLoader = SharedMemory::class.java.classLoader
        val outputSharedMemory = bundle
            .getParcelable(KEY_OUTPUT_SHM, SharedMemory::class.java)
            ?: return null

        val width = bundle.getInt(KEY_DECODED_WIDTH, 0)
        val height = bundle.getInt(KEY_DECODED_HEIGHT, 0)
        val originalWidth = bundle.getInt(KEY_ORIGINAL_WIDTH, 0)
        val originalHeight = bundle.getInt(KEY_ORIGINAL_HEIGHT, 0)
        val byteCount = bundle.getInt(KEY_DECODED_BYTE_COUNT, 0)
        val configName = bundle.getString(KEY_DECODED_CONFIG, Bitmap.Config.ARGB_8888.name)
        val config = runCatching {
            Bitmap.Config.valueOf(configName)
        }.getOrNull()

        val validResult = isValidDecodeResult(
            width = width,
            height = height,
            originalWidth = originalWidth,
            originalHeight = originalHeight,
            byteCount = byteCount,
            config = config,
        )
        if (!validResult) {
            outputSharedMemory.close()
            return null
        }

        return try {
            val bitmap = createBitmap(
                width = width,
                height = height,
                config = requireNotNull(config),
            )
            val outputBuffer = outputSharedMemory.mapReadOnly()
            try {
                bitmap.copyPixelsFromBuffer(outputBuffer)
            } finally {
                SharedMemory.unmap(outputBuffer)
            }
            IsolatedImageDecodeResult(
                bitmap = bitmap,
                originalSize = AndroidSize(originalWidth, originalHeight),
            )
        } catch (_: OutOfMemoryError) {
            Log.w(TAG, "Decoded bitmap exceeds available heap")
            null
        } catch (failure: Exception) {
            Log.w(TAG, "Failed to reconstruct bitmap: ${failure.message}")
            null
        } finally {
            outputSharedMemory.close()
        }
    }

    private fun isValidDecodeResult(
        width: Int,
        height: Int,
        originalWidth: Int,
        originalHeight: Int,
        byteCount: Int,
        config: Bitmap.Config?,
    ): Boolean {
        if (
            width <= 0 || height <= 0 ||
            originalWidth <= 0 || originalHeight <= 0 ||
            byteCount <= 0 || config == null || config == Bitmap.Config.HARDWARE
        ) {
            return false
        }

        val expectedByteCount = width.toLong() * height.toLong() * bytesPerPixel(config).toLong()
        val budget = decodedBitmapBudgetBytes()
        return expectedByteCount == byteCount.toLong() && expectedByteCount <= budget
    }

    private fun bytesPerPixel(config: Bitmap.Config): Int {
        return when (config) {
            Bitmap.Config.ALPHA_8 -> 1
            Bitmap.Config.RGB_565, Bitmap.Config.ARGB_4444 -> 2
            Bitmap.Config.RGBA_F16 -> 8
            else -> 4
        }
    }

    private fun decodedBitmapBudgetBytes(): Long {
        return min(MAX_DECODED_BITMAP_BYTES, Runtime.getRuntime().maxMemory() / HEAP_BUDGET_DIVISOR)
    }

    private fun closeOutputSharedMemory(bundle: Bundle) {
        bundle.classLoader = SharedMemory::class.java.classLoader
        bundle.getParcelable(KEY_OUTPUT_SHM, SharedMemory::class.java)?.close()
    }

    private fun closeQuietly(closeable: AutoCloseable) {
        runCatching {
            closeable.close()
        }
    }

    private suspend fun cancelWriter(job: Job) {
        job.cancel()
        job.join()
    }

    companion object {
        internal const val MAX_DECODED_BITMAP_BYTES = 96L * 1024L * 1024L

        private const val TAG = "IsolatedImageDecoder"
        private const val HEAP_BUDGET_DIVISOR = 4L
        private const val SERVICE_TIMEOUT_MILLIS = 30_000L
    }
}
