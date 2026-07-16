package com.dot.gallery.core.sandbox

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import android.system.OsConstants
import android.util.Size
import com.awxkee.jxlcoder.JxlCoder
import com.dot.gallery.core.util.MAX_ENCODED_MEDIA_BYTES
import com.dot.gallery.core.util.SizeLimitedInputStream
import com.radzivon.bartoshyk.avif.coder.HeifCoder
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

class IsolatedDecoderService : Service() {
    private lateinit var messenger: Messenger
    private val heifCoder = HeifCoder()

    override fun onCreate() {
        super.onCreate()
        messenger = Messenger(IncomingHandler(Looper.getMainLooper()))
    }

    override fun onBind(intent: Intent?): IBinder {
        return messenger.binder
    }

    private inner class IncomingHandler(looper: Looper) : Handler(looper) {
        override fun handleMessage(message: Message) {
            val replyTo = message.replyTo ?: return
            val request = message.data
            request.classLoader = ParcelFileDescriptor::class.java.classLoader
            val reply = Message.obtain().apply {
                what = message.what
                data = try {
                    when (message.what) {
                        MSG_GET_SIZE -> readImageSize(input = request)
                        MSG_DECODE -> decodeImage(input = request)
                        else -> errorBundle(message = "Unsupported message: ${message.what}")
                    }
                } catch (failure: Exception) {
                    errorBundle(message = failure.message ?: "Unknown decode error")
                } finally {
                    closeInputDescriptor(data = request)
                }
            }

            try {
                replyTo.send(reply)
            } catch (_: Exception) {
                // Client is gone.
            } finally {
                closeOutputSharedMemory(data = reply.data)
            }
        }
    }

    private fun readImageSize(input: Bundle): Bundle {
        val mimeType = normalizedMimeType(input.getString(KEY_MIME_TYPE, ""))
            ?: return errorBundle(message = "Unsupported MIME type")
        val encodedBytes = readInputBytes(input = input)
        val size = getImageSize(bytes = encodedBytes, mimeType = mimeType)
            ?: return errorBundle(message = "Failed to read image size")
        validateOriginalSize(size = size)

        return Bundle().apply {
            putInt(KEY_IMAGE_WIDTH, size.width)
            putInt(KEY_IMAGE_HEIGHT, size.height)
        }
    }

    private fun decodeImage(input: Bundle): Bundle {
        val mimeType = normalizedMimeType(input.getString(KEY_MIME_TYPE, ""))
            ?: return errorBundle(message = "Unsupported MIME type")
        val encodedBytes = readInputBytes(input = input)
        val originalSize = getImageSize(bytes = encodedBytes, mimeType = mimeType)
            ?: return errorBundle(message = "Failed to read image size")
        validateOriginalSize(size = originalSize)

        val decodedBudget = input.getLong(KEY_MAX_DECODED_BYTES, 0L)
            .coerceIn(minimumValue = MIN_DECODED_BITMAP_BYTES, maximumValue = MAX_DECODED_BITMAP_BYTES)
        val targetSize = resolveTargetSize(
            originalSize = originalSize,
            requestedWidth = input.getInt(KEY_TARGET_WIDTH, 0),
            requestedHeight = input.getInt(KEY_TARGET_HEIGHT, 0),
            maximumDecodedBytes = decodedBudget,
        )
        val bitmap = decodeBitmap(
            bytes = encodedBytes,
            mimeType = mimeType,
            targetSize = targetSize,
        ) ?: return errorBundle(message = "Decode failed for MIME type: $mimeType")

        return bitmap.useForResult { decodedBitmap ->
            val pixelByteCount = decodedBitmap.byteCount
            if (pixelByteCount <= 0 || pixelByteCount.toLong() > decodedBudget) {
                return@useForResult errorBundle(message = "Decoded bitmap exceeds memory budget")
            }

            val outputSharedMemory = SharedMemory.create("decoded_pixels", pixelByteCount)
            try {
                val outputBuffer = outputSharedMemory.mapReadWrite()
                try {
                    decodedBitmap.copyPixelsToBuffer(outputBuffer)
                } finally {
                    SharedMemory.unmap(outputBuffer)
                }
                if (!outputSharedMemory.setProtect(OsConstants.PROT_READ)) {
                    throw IOException("Failed to make decoded pixels read-only")
                }

                Bundle().apply {
                    putParcelable(KEY_OUTPUT_SHM, outputSharedMemory)
                    putInt(KEY_ORIGINAL_WIDTH, originalSize.width)
                    putInt(KEY_ORIGINAL_HEIGHT, originalSize.height)
                    putInt(KEY_DECODED_WIDTH, decodedBitmap.width)
                    putInt(KEY_DECODED_HEIGHT, decodedBitmap.height)
                    putInt(KEY_DECODED_BYTE_COUNT, pixelByteCount)
                    putString(
                        KEY_DECODED_CONFIG,
                        (decodedBitmap.config ?: Bitmap.Config.ARGB_8888).name,
                    )
                }
            } catch (failure: Exception) {
                outputSharedMemory.close()
                throw failure
            }
        }
    }

    private fun readInputBytes(input: Bundle): ByteArray {
        val inputDescriptor = input
            .getParcelable(KEY_INPUT_PFD, ParcelFileDescriptor::class.java)
            ?: throw IOException("Missing input descriptor")
        val output = ByteArrayOutputStream(INITIAL_INPUT_CAPACITY_BYTES)
        ParcelFileDescriptor.AutoCloseInputStream(inputDescriptor).use { inputStream ->
            SizeLimitedInputStream(
                inputStream = inputStream,
                maximumBytes = MAX_ENCODED_MEDIA_BYTES,
            ).copyTo(
                out = output,
                bufferSize = INPUT_BUFFER_BYTES,
            )
        }

        if (output.size() == 0) {
            throw IOException("Encoded image is empty")
        }
        return output.toByteArray()
    }

    private fun getImageSize(bytes: ByteArray, mimeType: String): Size? {
        return when {
            mimeType in HEIF_MIME_TYPES -> heifCoder.getSize(bytes)
            mimeType == JXL_MIME_TYPE -> JxlCoder.getSize(bytes)
            else -> null
        }
    }

    private fun decodeBitmap(bytes: ByteArray, mimeType: String, targetSize: Size): Bitmap? {
        return when {
            mimeType in HEIF_MIME_TYPES -> {
                heifCoder.decodeSampled(bytes, targetSize.width, targetSize.height)
            }

            mimeType == JXL_MIME_TYPE -> {
                JxlCoder.decodeSampled(bytes, targetSize.width, targetSize.height)
            }

            else -> null
        }
    }

    private fun validateOriginalSize(size: Size) {
        if (size.width <= 0 || size.height <= 0) {
            throw IOException("Invalid image dimensions")
        }
        val pixelCount = size.width.toLong() * size.height.toLong()
        if (pixelCount <= 0L || pixelCount > MAX_IMAGE_PIXELS) {
            throw IOException("Image dimensions exceed safety limit")
        }
    }

    private fun resolveTargetSize(
        originalSize: Size,
        requestedWidth: Int,
        requestedHeight: Int,
        maximumDecodedBytes: Long,
    ): Size {
        val requestedScale = when {
            requestedWidth > 0 && requestedHeight > 0 -> {
                min(
                    requestedWidth.toDouble() / originalSize.width.toDouble(),
                    requestedHeight.toDouble() / originalSize.height.toDouble(),
                )
            }

            requestedWidth > 0 -> requestedWidth.toDouble() / originalSize.width.toDouble()
            requestedHeight > 0 -> requestedHeight.toDouble() / originalSize.height.toDouble()
            else -> 1.0
        }.coerceAtMost(maximumValue = 1.0)
        val requestedPixels = originalSize.width.toDouble() * originalSize.height.toDouble() *
            requestedScale * requestedScale
        val maximumPixels = maximumDecodedBytes.toDouble() / ARGB_BYTES_PER_PIXEL.toDouble()
        val budgetScale = when {
            requestedPixels > maximumPixels -> sqrt(maximumPixels / requestedPixels)
            else -> 1.0
        }
        val finalScale = requestedScale * budgetScale
        return Size(
            (originalSize.width.toDouble() * finalScale).roundToInt().coerceAtLeast(minimumValue = 1),
            (originalSize.height.toDouble() * finalScale).roundToInt().coerceAtLeast(minimumValue = 1),
        )
    }

    private fun normalizedMimeType(mimeType: String): String? {
        val normalized = mimeType.substringBefore(';').trim().lowercase(Locale.ROOT)
        return normalized.takeIf { value ->
            value == JXL_MIME_TYPE || value in HEIF_MIME_TYPES
        }
    }

    private inline fun <T> Bitmap.useForResult(block: (Bitmap) -> T): T {
        return try {
            block(this)
        } finally {
            recycle()
        }
    }

    private fun closeOutputSharedMemory(data: Bundle) {
        data.classLoader = SharedMemory::class.java.classLoader
        data.getParcelable(KEY_OUTPUT_SHM, SharedMemory::class.java)?.close()
    }

    private fun closeInputDescriptor(data: Bundle) {
        data.classLoader = ParcelFileDescriptor::class.java.classLoader
        data.getParcelable(KEY_INPUT_PFD, ParcelFileDescriptor::class.java)?.let { descriptor ->
            runCatching {
                descriptor.close()
            }
        }
    }

    private fun errorBundle(message: String): Bundle {
        return Bundle().apply {
            putBoolean(KEY_ERROR, true)
            putString(KEY_ERROR_MESSAGE, message)
        }
    }

    companion object {
        internal const val MSG_GET_SIZE = 1
        internal const val MSG_DECODE = 2

        internal const val KEY_INPUT_PFD = "input_pfd"
        internal const val KEY_MIME_TYPE = "mime_type"
        internal const val KEY_TARGET_WIDTH = "target_width"
        internal const val KEY_TARGET_HEIGHT = "target_height"
        internal const val KEY_MAX_DECODED_BYTES = "max_decoded_bytes"

        internal const val KEY_IMAGE_WIDTH = "image_width"
        internal const val KEY_IMAGE_HEIGHT = "image_height"
        internal const val KEY_ORIGINAL_WIDTH = "original_width"
        internal const val KEY_ORIGINAL_HEIGHT = "original_height"

        internal const val KEY_OUTPUT_SHM = "output_shm"
        internal const val KEY_DECODED_WIDTH = "decoded_width"
        internal const val KEY_DECODED_HEIGHT = "decoded_height"
        internal const val KEY_DECODED_BYTE_COUNT = "decoded_byte_count"
        internal const val KEY_DECODED_CONFIG = "decoded_config"

        internal const val KEY_ERROR = "error"
        internal const val KEY_ERROR_MESSAGE = "error_message"

        private const val ARGB_BYTES_PER_PIXEL = 4
        private const val INITIAL_INPUT_CAPACITY_BYTES = 64 * 1024
        private const val INPUT_BUFFER_BYTES = 64 * 1024
        private const val JXL_MIME_TYPE = "image/jxl"
        private const val MAX_IMAGE_PIXELS = 1_000_000_000L
        private const val MIN_DECODED_BITMAP_BYTES = 4L * 1024L * 1024L
        private const val MAX_DECODED_BITMAP_BYTES = IsolatedImageDecoder.MAX_DECODED_BITMAP_BYTES

        private val HEIF_MIME_TYPES = setOf(
            "image/heif",
            "image/heic",
            "image/heif-sequence",
            "image/heic-sequence",
            "image/avif",
            "image/avis",
        )
    }
}
