package com.dot.gallery.core.sandbox

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import android.util.Log
import android.util.Size
import androidx.core.graphics.createBitmap
import com.dot.gallery.core.sandbox.IsolatedDecoderService.Companion.KEY_ERROR
import com.dot.gallery.core.sandbox.IsolatedDecoderService.Companion.KEY_ERROR_MESSAGE
import com.dot.gallery.core.sandbox.IsolatedDecoderService.Companion.KEY_INPUT_PFD
import com.dot.gallery.core.sandbox.IsolatedDecoderService.Companion.KEY_IS_VIDEO
import com.dot.gallery.core.sandbox.IsolatedDecoderService.Companion.KEY_MAX_DECODED_BYTES
import com.dot.gallery.core.sandbox.IsolatedDecoderService.Companion.KEY_MIME_TYPE
import com.dot.gallery.core.sandbox.IsolatedDecoderService.Companion.KEY_OUTPUT_SHM
import com.dot.gallery.core.sandbox.IsolatedDecoderService.Companion.KEY_TARGET_HEIGHT
import com.dot.gallery.core.sandbox.IsolatedDecoderService.Companion.KEY_TARGET_WIDTH
import com.dot.gallery.core.sandbox.IsolatedDecoderService.Companion.KEY_USE_VIDEO_THUMBNAIL
import com.dot.gallery.core.sandbox.IsolatedDecoderService.Companion.MSG_DECODE_PREVIEW
import com.dot.gallery.core.util.runCancellableContentProviderOperation
import com.dot.gallery.injection.qualifier.IoDispatcher
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

@Singleton
internal class MediaPreviewDecoder @Inject constructor(
    private val connection: MediaAnalysisDecoderConnection,
    private val contentResolver: ContentResolver,
    private val imageDecoder: IsolatedImageDecoder,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    private val requestMutex = Mutex()

    suspend fun decode(uri: Uri, mimeType: String?, isVideo: Boolean): Bitmap? {
        return requestMutex.withLock {
            var requestCompleted = false
            val result = withTimeoutOrNull(timeMillis = SERVICE_TIMEOUT_MILLIS) {
                val decodedBitmap = withContext(context = ioDispatcher) {
                    decodeLocked(
                        uri = uri,
                        mimeType = mimeType,
                        isVideo = isVideo,
                    )
                }
                requestCompleted = true
                decodedBitmap
            }
            if (!requestCompleted) {
                Log.w(TAG, "Media preview decoding timed out")
                connection.unbind()
            }
            result
        }
    }

    private suspend fun decodeLocked(uri: Uri, mimeType: String?, isVideo: Boolean): Bitmap? {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) {
            return null
        }

        val response = requestIsolatedPreview(
            uri = uri,
            mimeType = mimeType,
            isVideo = isVideo,
        ) ?: return null

        return when {
            response.getBoolean(KEY_USE_VIDEO_THUMBNAIL, false) -> {
                loadVideoThumbnail(uri = uri)
            }

            response.getBoolean(KEY_ERROR, false) -> {
                Log.w(
                    TAG,
                    "Isolated media preview failed: ${response.getString(KEY_ERROR_MESSAGE)}",
                )
                null
            }

            else -> {
                imageDecoder.readDecodeResult(bundle = response)?.bitmap
            }
        }
    }

    private suspend fun requestIsolatedPreview(
        uri: Uri,
        mimeType: String?,
        isVideo: Boolean,
    ): Bundle? {
        val descriptor = try {
            contentResolver.openFileDescriptorCancellable(uri = uri, mode = "r")
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            Log.w(TAG, "Unable to open media preview descriptor", failure)
            null
        } ?: return null

        return descriptor.use { inputDescriptor ->
            val encodedSize = inputDescriptor.statSize
            if (encodedSize == 0L) {
                return@use null
            }
            val messenger = connection.getMessenger() ?: return@use null
            sendAndReceive(
                messenger = messenger,
                data = Bundle().apply {
                    putParcelable(KEY_INPUT_PFD, inputDescriptor)
                    putString(KEY_MIME_TYPE, mimeType ?: contentResolver.getType(uri).orEmpty())
                    putBoolean(KEY_IS_VIDEO, isVideo)
                    putInt(KEY_TARGET_WIDTH, PREVIEW_SIZE_PIXELS)
                    putInt(KEY_TARGET_HEIGHT, PREVIEW_SIZE_PIXELS)
                    putLong(KEY_MAX_DECODED_BYTES, PREVIEW_DECODED_BYTES)
                },
            )
        }
    }

    private suspend fun loadVideoThumbnail(uri: Uri): Bitmap? {
        return try {
            val thumbnail = contentResolver.loadThumbnailCancellable(
                uri = uri,
                size = Size(PREVIEW_SIZE_PIXELS, PREVIEW_SIZE_PIXELS),
            )
            normalizeThumbnail(bitmap = thumbnail)
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            Log.w(TAG, "Unable to load system video thumbnail", failure)
            null
        }
    }

    private fun normalizeThumbnail(bitmap: Bitmap): Bitmap {
        val sourceAspectRatio = bitmap.width.toDouble() / bitmap.height.toDouble()
        val targetAspectRatio = 1.0
        val sourceRect = when {
            sourceAspectRatio > targetAspectRatio -> {
                val cropWidth = bitmap.height
                val left = (bitmap.width - cropWidth) / 2
                Rect(left, 0, left + cropWidth, bitmap.height)
            }

            else -> {
                val cropHeight = bitmap.width
                val top = (bitmap.height - cropHeight) / 2
                Rect(0, top, bitmap.width, top + cropHeight)
            }
        }
        return try {
            createBitmap(
                width = PREVIEW_SIZE_PIXELS,
                height = PREVIEW_SIZE_PIXELS,
                config = Bitmap.Config.ARGB_8888,
            ).apply {
                Canvas(this).drawBitmap(
                    bitmap,
                    sourceRect,
                    Rect(0, 0, PREVIEW_SIZE_PIXELS, PREVIEW_SIZE_PIXELS),
                    Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
                )
            }
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun sendAndReceive(messenger: Messenger, data: Bundle): Bundle? {
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
            val request = Message.obtain().apply {
                what = MSG_DECODE_PREVIEW
                this.data = data
                replyTo = Messenger(replyHandler)
            }
            runCatching {
                messenger.send(request)
            }.onFailure { failure ->
                Log.w(TAG, "Unable to request isolated media preview", failure)
                if (continuation.isActive) {
                    continuation.resume(null)
                }
            }
        }
    }

    private fun closeOutputSharedMemory(bundle: Bundle) {
        bundle.classLoader = SharedMemory::class.java.classLoader
        bundle.getParcelable(KEY_OUTPUT_SHM, SharedMemory::class.java)?.close()
    }

    companion object {
        private const val PREVIEW_SIZE_PIXELS = 224
        private const val PREVIEW_DECODED_BYTES =
            PREVIEW_SIZE_PIXELS.toLong() * PREVIEW_SIZE_PIXELS.toLong() * 4L
        private const val SERVICE_TIMEOUT_MILLIS = 30_000L
        private const val TAG = "MediaPreviewDecoder"
    }
}

internal suspend fun ContentResolver.loadThumbnailCancellable(uri: Uri, size: Size): Bitmap {
    return runCancellableContentProviderOperation(
        disposeResult = { bitmap -> bitmap.recycle() },
    ) { cancellationSignal ->
        loadThumbnail(uri, size, cancellationSignal)
    }
}

internal suspend fun ContentResolver.openFileDescriptorCancellable(
    uri: Uri,
    mode: String,
): ParcelFileDescriptor? {
    return runCancellableContentProviderOperation(
        disposeResult = { descriptor -> descriptor?.close() },
    ) { cancellationSignal ->
        openFileDescriptor(uri, mode, cancellationSignal)
    }
}
