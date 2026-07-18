package com.dot.gallery.core.sandbox

import android.app.Service
import android.content.Intent
import android.content.res.AssetFileDescriptor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import android.system.OsConstants
import android.util.Log
import android.util.Size
import androidx.core.graphics.createBitmap
import com.awxkee.jxlcoder.JxlCoder
import com.caverock.androidsvg.SVG
import com.dot.gallery.core.decoder.ImageFileFormat
import com.dot.gallery.core.decoder.classifyImageHeader
import com.dot.gallery.core.decoder.preadImageHeader
import com.dot.gallery.core.util.MAX_ENCODED_MEDIA_BYTES
import com.dot.gallery.core.util.SizeLimitedInputStream
import com.radzivon.bartoshyk.avif.coder.HeifCoder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

class IsolatedDecoderService : Service() {
    private lateinit var messenger: Messenger
    private val heifCoder = HeifCoder()
    private val requestExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate() {
        super.onCreate()
        messenger = Messenger(IncomingHandler(Looper.getMainLooper()))
    }

    override fun onBind(intent: Intent?): IBinder {
        return messenger.binder
    }

    override fun onDestroy() {
        requestExecutor.shutdownNow()

        super.onDestroy()
    }

    private inner class IncomingHandler(looper: Looper) : Handler(looper) {
        override fun handleMessage(message: Message) {
            val replyTo = message.replyTo ?: return
            val request = message.data

            request.classLoader = ParcelFileDescriptor::class.java.classLoader

            val requestType = message.what

            requestExecutor.execute {
                val reply = Message.obtain().apply {
                    what = requestType
                    data = try {
                        when (requestType) {
                            MSG_GET_SIZE -> {
                                readImageSize(input = request)
                            }

                            MSG_DECODE -> {
                                decodeImage(input = request)
                            }

                            MSG_DECODE_PREVIEW -> {
                                decodePreview(input = request)
                            }

                            else -> {
                                errorBundle(message = "Unsupported message: $requestType")
                            }
                        }
                    } catch (failure: Exception) {
                        Log.w(TAG, "Isolated decode request failed", failure)
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

        return bitmapResult(
            bitmap = bitmap,
            originalSize = originalSize,
            decodedBudget = decodedBudget,
        )
    }

    private fun decodePreview(input: Bundle): Bundle {
        val inputDescriptor = input
            .getParcelable(KEY_INPUT_PFD, ParcelFileDescriptor::class.java)
            ?: return errorBundle(message = "Missing input descriptor")
        val encodedSize = inputDescriptor.statSize
        if (encodedSize == 0L) {
            return errorBundle(message = "Encoded media has an invalid size")
        }

        val requestedWidth = input.getInt(KEY_TARGET_WIDTH, 0)
        val requestedHeight = input.getInt(KEY_TARGET_HEIGHT, 0)
        if (requestedWidth <= 0 || requestedHeight <= 0) {
            return errorBundle(message = "Invalid preview dimensions")
        }
        val decodedBudget = input.getLong(KEY_MAX_DECODED_BYTES, 0L)
        val requiredBytes = requestedWidth.toLong() * requestedHeight.toLong() * ARGB_BYTES_PER_PIXEL
        if (requiredBytes <= 0L || decodedBudget < requiredBytes) {
            return errorBundle(message = "Invalid preview memory budget")
        }

        val header = runCatching {
            inputDescriptor.fileDescriptor.preadImageHeader()
        }.getOrDefault(defaultValue = byteArrayOf())
        val imageFormat = classifyImageHeader(header = header, length = header.size)
        val preview = when {
            imageFormat == ImageFileFormat.SVG -> decodeSvgPreview(
                inputDescriptor = inputDescriptor,
                encodedSize = encodedSize,
                requestedWidth = requestedWidth,
                requestedHeight = requestedHeight,
            )

            imageFormat?.sandboxMimeType != null -> decodeSandboxedPreview(
                inputDescriptor = inputDescriptor,
                format = imageFormat,
                encodedSize = encodedSize,
                requestedWidth = requestedWidth,
                requestedHeight = requestedHeight,
            )

            imageFormat != null -> decodePlatformPreview(
                inputDescriptor = inputDescriptor,
                requestedWidth = requestedWidth,
                requestedHeight = requestedHeight,
            )

            input.getBoolean(KEY_IS_VIDEO, false) -> {
                return Bundle().apply {
                    putBoolean(KEY_USE_VIDEO_THUMBNAIL, true)
                }
            }

            else -> null
        } ?: return errorBundle(message = "Unable to decode media preview")

        val normalizedBitmap = preview.bitmap.toArgb8888() ?: run {
            preview.bitmap.recycle()
            return errorBundle(message = "Unable to normalize media preview")
        }
        if (normalizedBitmap !== preview.bitmap) {
            preview.bitmap.recycle()
        }

        return bitmapResult(
            bitmap = normalizedBitmap,
            originalSize = preview.originalSize,
            decodedBudget = decodedBudget,
        )
    }

    private fun decodeSvgPreview(
        inputDescriptor: ParcelFileDescriptor,
        encodedSize: Long,
        requestedWidth: Int,
        requestedHeight: Int,
    ): PreviewBitmap? {
        if (encodedSize > MAX_SVG_BYTES) {
            return null
        }

        val encodedBytes = readInputBytes(
            inputDescriptor = inputDescriptor,
            maximumBytes = MAX_SVG_BYTES,
        )

        val svg = ByteArrayInputStream(encodedBytes).use { inputStream ->
            SVG.getFromInputStream(inputStream)
        }

        val viewBox = svg.documentViewBox
        val originalSize = Size(
            resolveSvgDimension(
                viewBoxDimension = viewBox?.width(),
                documentDimension = svg.documentWidth,
            ),
            resolveSvgDimension(
                viewBoxDimension = viewBox?.height(),
                documentDimension = svg.documentHeight,
            ),
        )

        validateOriginalSize(size = originalSize)

        if (viewBox == null) {
            svg.setDocumentViewBox(
                0f,
                0f,
                originalSize.width.toFloat(),
                originalSize.height.toFloat(),
            )
        }

        svg.setDocumentWidth("100%")
        svg.setDocumentHeight("100%")

        val targetSize = resolvePreviewTargetSize(
            originalSize = originalSize,
            requestedWidth = requestedWidth,
            requestedHeight = requestedHeight,
            maximumDecodedBytes = requestedWidth.toLong() *
                    requestedHeight.toLong() * MAXIMUM_PREVIEW_BYTES_PER_PIXEL,
        )

        val bitmap = createBitmap(
            width = targetSize.width,
            height = targetSize.height,
            config = Bitmap.Config.ARGB_8888,
        )

        svg.renderToCanvas(Canvas(bitmap))

        return PreviewBitmap(
            bitmap = centerCrop(
                bitmap = bitmap,
                width = requestedWidth,
                height = requestedHeight,
            ),
            originalSize = originalSize,
        )
    }

    private fun resolveSvgDimension(viewBoxDimension: Float?, documentDimension: Float): Int {
        val dimension = viewBoxDimension?.takeIf { value -> value.isFinite() && value > 0f }
            ?: documentDimension.takeIf { value -> value.isFinite() && value > 0f }
            ?: throw IOException("SVG has invalid dimensions")

        if (dimension > Int.MAX_VALUE.toFloat()) {
            throw IOException("SVG dimensions exceed safety limit")
        }

        return dimension.roundToInt().coerceAtLeast(minimumValue = 1)
    }

    private fun decodeSandboxedPreview(
        inputDescriptor: ParcelFileDescriptor,
        format: ImageFileFormat,
        encodedSize: Long,
        requestedWidth: Int,
        requestedHeight: Int,
    ): PreviewBitmap? {
        if (encodedSize > MAX_ENCODED_MEDIA_BYTES) {
            return null
        }
        val mimeType = format.sandboxMimeType ?: return null
        val encodedBytes = readInputBytes(inputDescriptor = inputDescriptor)
        val originalSize = getImageSize(bytes = encodedBytes, mimeType = mimeType) ?: return null

        validateOriginalSize(size = originalSize)

        val targetSize = resolvePreviewTargetSize(
            originalSize = originalSize,
            requestedWidth = requestedWidth,
            requestedHeight = requestedHeight,
            maximumDecodedBytes = requestedWidth.toLong() * requestedHeight.toLong() * 16L,
        )

        val decodedBitmap = decodeBitmap(
            bytes = encodedBytes,
            mimeType = mimeType,
            targetSize = targetSize,
        ) ?: return null

        return PreviewBitmap(
            bitmap = centerCrop(
                bitmap = decodedBitmap,
                width = requestedWidth,
                height = requestedHeight,
            ),
            originalSize = originalSize,
        )
    }

    private fun decodePlatformPreview(
        inputDescriptor: ParcelFileDescriptor,
        requestedWidth: Int,
        requestedHeight: Int,
    ): PreviewBitmap? {
        var originalSize: Size? = null
        val source = ImageDecoder.createSource {
            AssetFileDescriptor(
                ParcelFileDescriptor.dup(inputDescriptor.fileDescriptor),
                0L,
                inputDescriptor.statSize,
            )
        }

        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, imageInfo, _ ->
            validateOriginalSize(size = imageInfo.size)
            originalSize = imageInfo.size
            val targetSize = resolvePreviewTargetSize(
                originalSize = imageInfo.size,
                requestedWidth = requestedWidth,
                requestedHeight = requestedHeight,
                maximumDecodedBytes = requestedWidth.toLong() *
                        requestedHeight.toLong() * MAXIMUM_PREVIEW_BYTES_PER_PIXEL,
            )
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE)
            decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_LOW_RAM
            decoder.setTargetSize(targetSize.width, targetSize.height)
        }
        val validatedOriginalSize = originalSize ?: return null
        return PreviewBitmap(
            bitmap = centerCrop(
                bitmap = bitmap,
                width = requestedWidth,
                height = requestedHeight,
            ),
            originalSize = validatedOriginalSize,
        )
    }

    private fun centerCrop(bitmap: Bitmap, width: Int, height: Int): Bitmap {
        if (bitmap.width == width && bitmap.height == height) {
            return bitmap
        }
        val sourceAspectRatio = bitmap.width.toDouble() / bitmap.height.toDouble()
        val targetAspectRatio = width.toDouble() / height.toDouble()
        val sourceRect = when {
            sourceAspectRatio > targetAspectRatio -> {
                val cropWidth = (bitmap.height.toDouble() * targetAspectRatio)
                    .roundToInt()
                    .coerceIn(minimumValue = 1, maximumValue = bitmap.width)
                val left = (bitmap.width - cropWidth) / 2
                Rect(left, 0, left + cropWidth, bitmap.height)
            }

            else -> {
                val cropHeight = (bitmap.width.toDouble() / targetAspectRatio)
                    .roundToInt()
                    .coerceIn(minimumValue = 1, maximumValue = bitmap.height)
                val top = (bitmap.height - cropHeight) / 2
                Rect(0, top, bitmap.width, top + cropHeight)
            }
        }
        return try {
            createBitmap(
                width = width,
                height = height,
                config = Bitmap.Config.ARGB_8888,
            ).apply {
                Canvas(this).drawBitmap(
                    bitmap,
                    sourceRect,
                    Rect(0, 0, width, height),
                    Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
                )
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun Bitmap.toArgb8888(): Bitmap? {
        return when (config) {
            Bitmap.Config.ARGB_8888 -> this
            else -> copy(Bitmap.Config.ARGB_8888, false)
        }
    }

    private fun resolvePreviewTargetSize(
        originalSize: Size,
        requestedWidth: Int,
        requestedHeight: Int,
        maximumDecodedBytes: Long,
    ): Size {
        val fillScale = max(
            requestedWidth.toDouble() / originalSize.width.toDouble(),
            requestedHeight.toDouble() / originalSize.height.toDouble(),
        ).coerceAtMost(maximumValue = 1.0)
        val desiredWidth = max(
            1,
            (originalSize.width.toDouble() * fillScale).roundToInt(),
        )
        val desiredHeight = max(
            1,
            (originalSize.height.toDouble() * fillScale).roundToInt(),
        )
        val desiredBytes = desiredWidth.toLong() * desiredHeight.toLong() * ARGB_BYTES_PER_PIXEL
        val budgetScale = when {
            desiredBytes > maximumDecodedBytes -> sqrt(
                maximumDecodedBytes.toDouble() / desiredBytes.toDouble(),
            )

            else -> 1.0
        }
        return Size(
            max(1, (desiredWidth.toDouble() * budgetScale).roundToInt()),
            max(1, (desiredHeight.toDouble() * budgetScale).roundToInt()),
        )
    }

    private fun bitmapResult(
        bitmap: Bitmap,
        originalSize: Size,
        decodedBudget: Long,
    ): Bundle {
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

    private data class PreviewBitmap(
        val bitmap: Bitmap,
        val originalSize: Size,
    )

    private fun readInputBytes(input: Bundle): ByteArray {
        val inputDescriptor = input
            .getParcelable(KEY_INPUT_PFD, ParcelFileDescriptor::class.java)
            ?: throw IOException("Missing input descriptor")
        return readInputBytes(inputDescriptor = inputDescriptor)
    }

    private fun readInputBytes(
        inputDescriptor: ParcelFileDescriptor,
        maximumBytes: Long = MAX_ENCODED_MEDIA_BYTES,
    ): ByteArray {
        val output = ByteArrayOutputStream(INITIAL_INPUT_CAPACITY_BYTES)
        ParcelFileDescriptor.AutoCloseInputStream(inputDescriptor).use { inputStream ->
            SizeLimitedInputStream(
                inputStream = inputStream,
                maximumBytes = maximumBytes,
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
        private const val MAXIMUM_PREVIEW_BYTES_PER_PIXEL = 16L
        private const val TAG = "IsolatedDecoderService"
        internal const val MSG_GET_SIZE = 1
        internal const val MSG_DECODE = 2
        internal const val MSG_DECODE_PREVIEW = 3

        internal const val KEY_INPUT_PFD = "input_pfd"
        internal const val KEY_MIME_TYPE = "mime_type"
        internal const val KEY_IS_VIDEO = "is_video"
        internal const val KEY_USE_VIDEO_THUMBNAIL = "use_video_thumbnail"
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
        private const val MAX_SVG_BYTES = 16L * 1024L * 1024L
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
