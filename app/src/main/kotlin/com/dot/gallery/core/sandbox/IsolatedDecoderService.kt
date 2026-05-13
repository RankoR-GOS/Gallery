/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

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
import android.os.SharedMemory
import android.util.Size as AndroidSize
import com.awxkee.jxlcoder.JxlCoder
import com.radzivon.bartoshyk.avif.coder.HeifCoder

/**
 * Isolated-process service for complex image decoding.
 *
 * HEIF/AVIF/JXL bytes are decoded in a process with no app permissions. Input
 * and output buffers are exchanged through [SharedMemory].
 */
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
        override fun handleMessage(msg: Message) {
            val replyTo = msg.replyTo ?: return
            val data = msg.data
            data.classLoader = SharedMemory::class.java.classLoader

            val reply = Message.obtain()
            reply.what = msg.what

            try {
                reply.data = when (msg.what) {
                    MSG_GET_SIZE -> readImageSize(input = data)
                    MSG_DECODE -> decodeImage(input = data)
                    else -> errorBundle(message = "Unsupported message: ${msg.what}")
                }
            } catch (exception: Exception) {
                reply.data = errorBundle(message = exception.message ?: "Unknown decode error")
            }

            runCatching {
                replyTo.send(reply)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun readInputBytes(input: Bundle): ByteArray? {
        val inputSharedMemory = input.getParcelable<SharedMemory>(KEY_INPUT_SHM) ?: return null
        val byteCount = input.getInt(KEY_BYTE_COUNT, 0)
        if (byteCount <= 0) {
            inputSharedMemory.close()
            return null
        }

        return try {
            val inputBuffer = inputSharedMemory.mapReadOnly()
            val encodedBytes = ByteArray(byteCount)
            inputBuffer.get(encodedBytes)
            SharedMemory.unmap(inputBuffer)
            encodedBytes
        } finally {
            inputSharedMemory.close()
        }
    }

    private fun readImageSize(input: Bundle): Bundle {
        val encodedBytes = readInputBytes(input) ?: return errorBundle(message = "Missing input")
        val mimeType = input.getString(KEY_MIME_TYPE, "")
        val size = getImageSize(bytes = encodedBytes, mimeType = mimeType)
            ?: return errorBundle(message = "Unsupported mime type: $mimeType")

        return Bundle().apply {
            putInt(KEY_IMAGE_WIDTH, size.width)
            putInt(KEY_IMAGE_HEIGHT, size.height)
        }
    }

    private fun decodeImage(input: Bundle): Bundle {
        val encodedBytes = readInputBytes(input) ?: return errorBundle(message = "Missing input")
        val mimeType = input.getString(KEY_MIME_TYPE, "")
        val targetWidth = input.getInt(KEY_TARGET_WIDTH, 0)
        val targetHeight = input.getInt(KEY_TARGET_HEIGHT, 0)

        val bitmap = decodeBitmap(
            bytes = encodedBytes,
            mimeType = mimeType,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
        ) ?: return errorBundle(message = "Decode failed for mime type: $mimeType")

        val pixelByteCount = bitmap.byteCount
        val outputSharedMemory = SharedMemory.create("decoded_pixels", pixelByteCount)
        val outputBuffer = outputSharedMemory.mapReadWrite()
        bitmap.copyPixelsToBuffer(outputBuffer)
        SharedMemory.unmap(outputBuffer)

        return Bundle().apply {
            putParcelable(KEY_OUTPUT_SHM, outputSharedMemory)
            putInt(KEY_DECODED_WIDTH, bitmap.width)
            putInt(KEY_DECODED_HEIGHT, bitmap.height)
            putInt(KEY_DECODED_BYTE_COUNT, pixelByteCount)
            putString(KEY_DECODED_CONFIG, (bitmap.config ?: Bitmap.Config.ARGB_8888).name)
        }.also {
            bitmap.recycle()
        }
    }

    private fun getImageSize(bytes: ByteArray, mimeType: String): AndroidSize? {
        return when {
            isHeifMime(mimeType) -> heifCoder.getSize(bytes)
            mimeType.equals(JXL_MIME_TYPE, ignoreCase = true) -> JxlCoder.getSize(bytes)
            else -> null
        }
    }

    private fun decodeBitmap(
        bytes: ByteArray,
        mimeType: String,
        targetWidth: Int,
        targetHeight: Int,
    ): Bitmap? {
        val size = getImageSize(bytes = bytes, mimeType = mimeType) ?: return null
        val width = if (targetWidth > 0) targetWidth else size.width
        val height = if (targetHeight > 0) targetHeight else size.height

        return when {
            isHeifMime(mimeType) -> heifCoder.decodeSampled(bytes, width, height)
            mimeType.equals(JXL_MIME_TYPE, ignoreCase = true) -> JxlCoder.decodeSampled(bytes, width, height)
            else -> null
        }
    }

    private fun isHeifMime(mimeType: String): Boolean {
        val lower = mimeType.lowercase()
        return lower in HEIF_MIME_TYPES || lower.substringBefore(';') in HEIF_MIME_TYPES
    }

    private fun errorBundle(message: String): Bundle {
        return Bundle().apply {
            putBoolean(KEY_ERROR, true)
            putString(KEY_ERROR_MESSAGE, message)
        }
    }

    companion object {
        const val MSG_GET_SIZE = 1
        const val MSG_DECODE = 2

        const val KEY_INPUT_SHM = "input_shm"
        const val KEY_MIME_TYPE = "mime_type"
        const val KEY_TARGET_WIDTH = "target_width"
        const val KEY_TARGET_HEIGHT = "target_height"
        const val KEY_BYTE_COUNT = "byte_count"

        const val KEY_IMAGE_WIDTH = "image_width"
        const val KEY_IMAGE_HEIGHT = "image_height"

        const val KEY_OUTPUT_SHM = "output_shm"
        const val KEY_DECODED_WIDTH = "decoded_width"
        const val KEY_DECODED_HEIGHT = "decoded_height"
        const val KEY_DECODED_BYTE_COUNT = "decoded_byte_count"
        const val KEY_DECODED_CONFIG = "decoded_config"

        const val KEY_ERROR = "error"
        const val KEY_ERROR_MESSAGE = "error_message"

        private const val JXL_MIME_TYPE = "image/jxl"

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
