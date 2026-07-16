package com.dot.gallery.core.workers

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.IntDef
import androidx.core.net.toUri
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.dot.gallery.core.util.MAX_ENCODED_MEDIA_BYTES
import com.dot.gallery.core.util.SizeLimitExceededException
import com.dot.gallery.core.util.SizeLimitedInputStream
import com.dot.gallery.feature_node.domain.model.Media
import com.dot.gallery.feature_node.domain.util.getUri
import com.github.panpf.sketch.util.rotate
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.UUID
import kotlin.math.min

private const val TAG = "RotateMediaWorker"

fun WorkManager.rotateImage(media: Media, degrees: Int): UUID {
    val work = OneTimeWorkRequestBuilder<RotateMediaWorker>()
        .setInputData(
            workDataOf(
                RotateMediaWorker.KEY_MEDIA_URI to media.getUri().toString(),
                RotateMediaWorker.KEY_ROTATION_DEGREES to degrees,
                RotateMediaWorker.KEY_MIME_TYPE to media.mimeType,
            ),
        )
        .build()
    enqueue(work)
    return work.id
}

@HiltWorker
class RotateMediaWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {

    private val contentResolver: ContentResolver = appContext.contentResolver

    override suspend fun doWork(): Result {
        return withContext(Dispatchers.IO) {
            val sourceUri = inputData.getString(KEY_MEDIA_URI)?.toUri()
                ?: return@withContext failure(
                    message = "Missing media URI",
                    reason = FAILURE_REASON_GENERIC,
                )
            val degrees = inputData.getInt(KEY_ROTATION_DEGREES, 0).mod(other = FULL_ROTATION_DEGREES)
            if (degrees % RIGHT_ANGLE_DEGREES != 0) {
                return@withContext failure(
                    message = "Rotation must be a multiple of 90 degrees",
                    reason = FAILURE_REASON_GENERIC,
                )
            }
            if (degrees == 0) {
                return@withContext success(message = "No rotation requested")
            }

            val declaredMimeType = normalizedMimeType(
                mimeType = inputData.getString(KEY_MIME_TYPE) ?: contentResolver.getType(sourceUri),
            )

            try {
                update(status = Status.STARTED, message = "Begin")
                validateSourceSize(uri = sourceUri)
                val imageHeader = readImageHeader(uri = sourceUri)
                    ?: return@withContext failure(
                        message = "Failed to read image dimensions",
                        reason = FAILURE_REASON_GENERIC,
                    )
                val compressFormat = compressFormatFor(
                    mimeType = imageHeader.mimeType ?: declaredMimeType,
                ) ?: return@withContext failure(
                    message = "Unsupported image format",
                    reason = FAILURE_REASON_UNSUPPORTED_FORMAT,
                )
                validateRotationMemory(imageHeader = imageHeader)

                update(status = Status.DECODING, message = "Decoding original")
                val original = decodeFullResolution(uri = sourceUri)
                    ?: return@withContext failure(
                        message = "Failed to decode image",
                        reason = FAILURE_REASON_GENERIC,
                    )

                update(status = Status.ROTATING, message = "Applying rotation=$degrees")
                val rotated = original.rotate(degrees)
                if (rotated !== original) {
                    original.recycle()
                }

                try {
                    update(status = Status.SAVING, message = "Saving")
                    replaceWithRotatedImage(
                        sourceUri = sourceUri,
                        rotated = rotated,
                        compressFormat = compressFormat,
                    )
                } finally {
                    rotated.recycle()
                }

                update(status = Status.COMPLETED, message = "Done")
                success(message = "Rotation applied")
            } catch (exception: ImageTooLargeException) {
                Log.w(TAG, "Refusing unsafe full-resolution rotation for $sourceUri", exception)
                failure(
                    message = exception.message ?: "Image is too large to rotate safely",
                    reason = FAILURE_REASON_TOO_LARGE,
                )
            } catch (exception: SizeLimitExceededException) {
                Log.w(TAG, "Refusing oversized encoded image $sourceUri", exception)
                failure(
                    message = exception.message ?: "Image is too large to rotate safely",
                    reason = FAILURE_REASON_TOO_LARGE,
                )
            } catch (exception: CancellationException) {
                throw exception
            } catch (error: OutOfMemoryError) {
                Log.e(TAG, "Out of memory while rotating $sourceUri", error)
                failure(
                    message = "Image is too large to rotate safely",
                    reason = FAILURE_REASON_TOO_LARGE,
                )
            } catch (exception: Exception) {
                Log.w(TAG, "Failed to rotate $sourceUri", exception)
                failure(
                    message = exception.message ?: "Rotation failed",
                    reason = FAILURE_REASON_GENERIC,
                )
            }
        }
    }

    private fun normalizedMimeType(mimeType: String?): String? {
        return mimeType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase(Locale.ROOT)
    }

    private fun compressFormatFor(mimeType: String?): Bitmap.CompressFormat? {
        return when (mimeType) {
            "image/jpeg", "image/jpg" -> Bitmap.CompressFormat.JPEG
            "image/png" -> Bitmap.CompressFormat.PNG
            "image/webp" -> Bitmap.CompressFormat.WEBP_LOSSLESS
            else -> null
        }
    }

    private fun validateSourceSize(uri: Uri) {
        val sourceSize = contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
            descriptor.length.takeIf { length -> length >= 0L }
        }
        when {
            sourceSize != null && sourceSize > MAX_ENCODED_MEDIA_BYTES -> {
                throw ImageTooLargeException("Encoded image exceeds the safe size limit")
            }

            sourceSize == null -> {
                openLimitedInputStream(uri = uri).use { inputStream ->
                    val buffer = ByteArray(STREAM_BUFFER_BYTES)
                    while (inputStream.read(buffer) != -1) {
                        // Read to EOF so the size-limited stream can enforce the cap.
                    }
                }
            }
        }
    }

    private fun readImageHeader(uri: Uri): RotationImageHeader? {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        openLimitedInputStream(uri = uri).use { inputStream ->
            BitmapFactory.decodeStream(inputStream, null, options)
        }
        return RotationImageHeader(
            width = options.outWidth,
            height = options.outHeight,
            mimeType = normalizedMimeType(mimeType = options.outMimeType),
        ).takeIf { imageHeader -> imageHeader.width > 0 && imageHeader.height > 0 }
    }

    private fun validateRotationMemory(imageHeader: RotationImageHeader) {
        val bitmapBytes = imageHeader.width.toLong() * imageHeader.height.toLong() *
            ARGB_BYTES_PER_PIXEL
        val requiredBytes = bitmapBytes * ROTATION_BITMAP_COUNT
        val memoryBudget = min(
            MAX_ROTATION_MEMORY_BYTES,
            Runtime.getRuntime().maxMemory() / HEAP_BUDGET_DIVISOR,
        )
        if (bitmapBytes <= 0L || requiredBytes > memoryBudget) {
            throw ImageTooLargeException("Full-resolution rotation exceeds the safe memory budget")
        }
    }

    private fun decodeFullResolution(uri: Uri): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = false
        }
        return openLimitedInputStream(uri = uri).use { inputStream ->
            BitmapFactory.decodeStream(inputStream, null, options)
        }
    }

    private fun openLimitedInputStream(uri: Uri): InputStream {
        val inputStream = contentResolver.openInputStream(uri)
            ?: throw IOException("Failed to open source image")
        return SizeLimitedInputStream(
            inputStream = inputStream,
            maximumBytes = MAX_ENCODED_MEDIA_BYTES,
        )
    }

    private suspend fun replaceWithRotatedImage(
        sourceUri: Uri,
        rotated: Bitmap,
        compressFormat: Bitmap.CompressFormat,
    ) {
        val encodedFile = File.createTempFile("rotation_encoded_", ".tmp", appContext.cacheDir)
        val backupFile = File.createTempFile("rotation_backup_", ".tmp", appContext.cacheDir)
        var sourceWriteStarted = false
        try {
            encodedFile.outputStream().buffered().use { outputStream ->
                val compressed = rotated.compress(compressFormat, COMPRESSION_QUALITY, outputStream)
                if (!compressed) {
                    throw IOException("Failed to encode rotated image")
                }
            }
            openLimitedInputStream(uri = sourceUri).use { inputStream ->
                backupFile.outputStream().buffered().use { outputStream ->
                    copyStream(inputStream = inputStream, outputStream = outputStream)
                }
            }

            sourceWriteStarted = true
            writeFileToUri(file = encodedFile, destinationUri = sourceUri)
            touchMediaStore(uri = sourceUri)
        } catch (exception: CancellationException) {
            if (sourceWriteStarted) {
                restoreBackup(sourceUri = sourceUri, backupFile = backupFile)
            }
            throw exception
        } catch (exception: Exception) {
            if (sourceWriteStarted) {
                restoreBackup(sourceUri = sourceUri, backupFile = backupFile)
            }
            throw exception
        } finally {
            encodedFile.delete()
            backupFile.delete()
        }
    }

    private suspend fun restoreBackup(sourceUri: Uri, backupFile: File) {
        withContext(NonCancellable + Dispatchers.IO) {
            try {
                writeFileToUri(file = backupFile, destinationUri = sourceUri)
            } catch (exception: Exception) {
                Log.e(TAG, "Failed to restore original image after rotation failure", exception)
            }
        }
    }

    private suspend fun writeFileToUri(file: File, destinationUri: Uri) {
        val outputStream = contentResolver.openOutputStream(destinationUri, "wt")
            ?: throw IOException("Failed to open destination image")
        file.inputStream().buffered().use { inputStream ->
            outputStream.buffered().use { destinationStream ->
                copyStream(inputStream = inputStream, outputStream = destinationStream)
            }
        }
    }

    private suspend fun copyStream(inputStream: InputStream, outputStream: OutputStream) {
        val buffer = ByteArray(STREAM_BUFFER_BYTES)
        while (true) {
            currentCoroutineContext().ensureActive()
            val readBytes = inputStream.read(buffer)
            if (readBytes == -1) {
                break
            }
            outputStream.write(buffer, 0, readBytes)
        }
        outputStream.flush()
    }

    private fun touchMediaStore(uri: Uri) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DATE_MODIFIED, System.currentTimeMillis() / MILLIS_PER_SECOND)
        }
        contentResolver.update(uri, values, null, null)
    }

    private suspend fun update(@Status status: Int, message: String) {
        setProgress(workDataOf(KEY_STATUS to status, KEY_MESSAGE to message))
    }

    private fun success(message: String): Result {
        return Result.success(
            Data.Builder()
                .putInt(KEY_STATUS, Status.COMPLETED)
                .putString(KEY_MESSAGE, message)
                .build(),
        )
    }

    private fun failure(message: String, @FailureReason reason: Int): Result {
        return Result.failure(
            Data.Builder()
                .putInt(KEY_STATUS, Status.FAILED)
                .putString(KEY_MESSAGE, message)
                .putInt(KEY_FAILURE_REASON, reason)
                .build(),
        )
    }

    private class ImageTooLargeException(message: String) : IOException(message)

    @IntDef(
        Status.STARTED,
        Status.DECODING,
        Status.ROTATING,
        Status.SAVING,
        Status.COMPLETED,
        Status.FAILED,
    )
    @Retention(AnnotationRetention.SOURCE)
    private annotation class Status {
        companion object {
            const val STARTED = 0
            const val DECODING = 1
            const val ROTATING = 2
            const val SAVING = 3
            const val COMPLETED = 4
            const val FAILED = 5
        }
    }

    @IntDef(
        FAILURE_REASON_GENERIC,
        FAILURE_REASON_TOO_LARGE,
        FAILURE_REASON_UNSUPPORTED_FORMAT,
    )
    @Retention(AnnotationRetention.SOURCE)
    private annotation class FailureReason

    companion object {
        internal const val KEY_MEDIA_URI = "media_uri"
        internal const val KEY_ROTATION_DEGREES = "rotation_degrees"
        internal const val KEY_MIME_TYPE = "mime_type"
        internal const val KEY_FAILURE_REASON = "failure_reason"

        internal const val FAILURE_REASON_GENERIC = 0
        internal const val FAILURE_REASON_TOO_LARGE = 1
        internal const val FAILURE_REASON_UNSUPPORTED_FORMAT = 2

        private const val ARGB_BYTES_PER_PIXEL = 4L
        private const val COMPRESSION_QUALITY = 95
        private const val FULL_ROTATION_DEGREES = 360
        private const val HEAP_BUDGET_DIVISOR = 4L
        private const val KEY_MESSAGE = "message"
        private const val KEY_STATUS = "status"
        private const val MAX_ROTATION_MEMORY_BYTES = 96L * 1024L * 1024L
        private const val MILLIS_PER_SECOND = 1_000L
        private const val RIGHT_ANGLE_DEGREES = 90
        private const val ROTATION_BITMAP_COUNT = 2L
        private const val STREAM_BUFFER_BYTES = 64 * 1024
    }
}
