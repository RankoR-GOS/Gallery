package com.dot.gallery.feature_node.data.repository

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Intent
import android.content.IntentSender
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import com.dot.gallery.feature_node.data.externalcrop.CropSize
import com.dot.gallery.feature_node.data.externalcrop.buildCropResultIntent
import com.dot.gallery.feature_node.data.externalcrop.resolveCropOutputSize
import com.dot.gallery.feature_node.data.externalcrop.resolveCropSourceDecodeSize
import com.dot.gallery.feature_node.data.externalcrop.resolveIntentBitmapSize
import com.dot.gallery.feature_node.data.model.editor.SaveFormat
import com.dot.gallery.feature_node.data.model.editor.crop.CropImage
import com.dot.gallery.feature_node.data.model.editor.crop.CropPixelRect
import com.dot.gallery.feature_node.data.model.editor.crop.ExternalCropRequest
import com.dot.gallery.feature_node.data.model.editor.crop.NormalizedCropRect
import com.dot.gallery.injection.qualifier.DefaultDispatcher
import com.dot.gallery.injection.qualifier.IoDispatcher
import java.io.IOException
import java.io.OutputStream
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal interface ExternalCropRepository {

    suspend fun loadImage(uri: Uri): CropImage?

    suspend fun saveCropResult(
        request: ExternalCropRequest,
        image: CropImage,
        normalizedRect: NormalizedCropRect,
    ): ExternalCropSaveResult
}

internal sealed interface ExternalCropSaveResult {

    data class Saved(val resultIntent: Intent) : ExternalCropSaveResult

    /**
     * The caller-supplied output uri is not writable by us. Scoped storage only lets an app write
     * media it owns, and the caller cannot delegate its own access through
     * [android.provider.MediaStore.EXTRA_OUTPUT] — a plain extra carries no uri grant. The user
     * resolves it by approving [intentSender], which also makes the overwrite explicit to them.
     */
    data class OutputPermissionRequired(val intentSender: IntentSender) : ExternalCropSaveResult

    data object Failed : ExternalCropSaveResult
}

internal class ExternalCropRepositoryImpl @Inject constructor(
    private val contentResolver: ContentResolver,
    @param:DefaultDispatcher
    private val defaultDispatcher: CoroutineDispatcher,
    @param:IoDispatcher
    private val ioDispatcher: CoroutineDispatcher,
) : ExternalCropRepository {

    override suspend fun loadImage(uri: Uri): CropImage? {
        return try {
            val sourceBitmap = withContext(ioDispatcher) {
                decodeBoundedCropBitmap(
                    uri = uri,
                )
            }
            val previewBitmap = withContext(defaultDispatcher) {
                toCropPreviewBitmap(bitmap = sourceBitmap)
            }

            CropImage(
                sourceBitmap = sourceBitmap,
                previewBitmap = previewBitmap,
            )
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: IOException) {
            logCropLoadFailure(uri = uri, throwable = exception)
            null
        } catch (exception: RuntimeException) {
            logCropLoadFailure(uri = uri, throwable = exception)
            null
        } catch (error: OutOfMemoryError) {
            logCropLoadFailure(uri = uri, throwable = error)
            null
        }
    }

    override suspend fun saveCropResult(
        request: ExternalCropRequest,
        image: CropImage,
        normalizedRect: NormalizedCropRect,
    ): ExternalCropSaveResult {
        val saveFormat = request.saveFormat
        val cropResult = withContext(defaultDispatcher) {
            createCropBitmapResult(
                request = request,
                image = image,
                normalizedRect = normalizedRect,
            )
        }
        val outcome = writeRequestedOutput(
            request = request,
            bitmap = cropResult.bitmap,
            saveFormat = saveFormat,
        )
        val outputUri = when (outcome) {
            is OutputWriteOutcome.Written -> outcome.uri
            is OutputWriteOutcome.PermissionDenied -> {
                return createOutputPermissionRequest(uri = outcome.uri)
            }

            OutputWriteOutcome.Failed -> null
        }
        val requiresOutput = request.outputUri != null || !request.returnData
        if (outputUri == null && requiresOutput) {
            return ExternalCropSaveResult.Failed
        }

        return ExternalCropSaveResult.Saved(
            resultIntent = buildCropResultIntent(
                croppedRect = cropResult.cropRect,
                outputUri = outputUri,
                returnDataBitmap = cropResult.returnDataBitmap,
            ),
        )
    }

    private fun createOutputPermissionRequest(uri: Uri): ExternalCropSaveResult {
        val intentSender = try {
            MediaStore.createWriteRequest(contentResolver, listOf(uri)).intentSender
        } catch (exception: Exception) {
            Log.w(TAG, "Failed to build write request for $uri", exception)
            null
        }

        return when (intentSender) {
            null -> ExternalCropSaveResult.Failed
            else -> ExternalCropSaveResult.OutputPermissionRequired(intentSender = intentSender)
        }
    }

    private suspend fun writeRequestedOutput(
        request: ExternalCropRequest,
        bitmap: Bitmap,
        saveFormat: SaveFormat,
    ): OutputWriteOutcome {
        val outputUri = request.outputUri
        return withContext(ioDispatcher) {
            when {
                outputUri != null -> {
                    writeCropOutput(
                        uri = outputUri,
                        bitmap = bitmap,
                        saveFormat = saveFormat,
                    )
                }

                request.returnData -> OutputWriteOutcome.Written(uri = null)

                else -> {
                    OutputWriteOutcome.Written(
                        uri = writeFallbackOutput(
                            sourceUri = request.sourceUri,
                            bitmap = bitmap,
                            saveFormat = saveFormat,
                        ),
                    )
                }
            }
        }
    }

    private suspend fun writeFallbackOutput(
        sourceUri: Uri,
        bitmap: Bitmap,
        saveFormat: SaveFormat,
    ): Uri? {
        return insertCropOutput(
            bitmap = bitmap,
            saveFormat = saveFormat,
            displayName = croppedDisplayName(
                sourceDisplayName = queryDisplayName(uri = sourceUri),
                saveFormat = saveFormat,
            ),
        )
    }

    private fun queryDisplayName(uri: Uri): String? {
        val cursor = try {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )
        } catch (_: Exception) {
            null
        }
        return useDisplayName(cursor = cursor) ?: uri.lastPathSegment
    }

    private fun logCropLoadFailure(uri: Uri, throwable: Throwable) {
        Log.w(TAG, "Failed to load crop source $uri", throwable)
    }

    private fun decodeBoundedCropBitmap(uri: Uri): Bitmap {
        val sourceSize = readCropImageSize(
            uri = uri,
        )
        val targetSize = resolveCropSourceDecodeSize(
            sourceWidth = sourceSize.width,
            sourceHeight = sourceSize.height,
        )

        return decodeCropBitmap(
            uri = uri,
            targetSize = targetSize,
        )
    }

    private fun readCropImageSize(uri: Uri): CropSize {
        var sourceSize: CropSize? = null
        try {
            ImageDecoder.decodeBitmap(
                ImageDecoder.createSource(contentResolver, uri),
            ) { _, info, _ ->
                val imageSize = info.size
                sourceSize = CropSize(
                    width = imageSize.width,
                    height = imageSize.height,
                )
                throw ImageHeaderDecodedException()
            }
        } catch (_: ImageHeaderDecodedException) {
            return sourceSize ?: throw IOException("Image header did not include dimensions")
        }

        return sourceSize ?: throw IOException("Image header did not include dimensions")
    }

    private fun decodeCropBitmap(
        uri: Uri,
        targetSize: CropSize,
    ): Bitmap {
        return ImageDecoder.decodeBitmap(
            ImageDecoder.createSource(contentResolver, uri),
        ) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetSize(targetSize.width, targetSize.height)
        }
    }

    private fun cropToRect(bitmap: Bitmap, cropRect: CropPixelRect): Bitmap {
        return Bitmap.createBitmap(
            bitmap,
            cropRect.left,
            cropRect.top,
            cropRect.width,
            cropRect.height,
        )
    }

    private fun createCropBitmapResult(
        request: ExternalCropRequest,
        image: CropImage,
        normalizedRect: NormalizedCropRect,
    ): CropBitmapResult {
        val cropRect = CropPixelRect.fromNormalizedRect(
            normalizedRect = normalizedRect,
            bitmapWidth = image.sourceBitmap.width,
            bitmapHeight = image.sourceBitmap.height,
        )
        val croppedSourceBitmap = cropToRect(
            bitmap = image.sourceBitmap,
            cropRect = cropRect,
        )
        val croppedBitmap = scaleForCropRequest(
            bitmap = croppedSourceBitmap,
            request = request,
        )

        val returnDataBitmap = when {
            request.returnData -> downsampleForIntentResult(bitmap = croppedBitmap)
            else -> null
        }

        return CropBitmapResult(
            cropRect = cropRect,
            bitmap = croppedBitmap,
            returnDataBitmap = returnDataBitmap,
        )
    }

    private fun scaleForCropRequest(bitmap: Bitmap, request: ExternalCropRequest): Bitmap {
        val targetSize = resolveCropOutputSize(
            cropWidth = bitmap.width,
            cropHeight = bitmap.height,
            outputX = request.outputX,
            outputY = request.outputY,
            scale = request.scale,
            scaleUpIfNeeded = request.scaleUpIfNeeded,
        )

        val isSameSize = targetSize.width == bitmap.width && targetSize.height == bitmap.height

        return when {
            isSameSize -> bitmap
            request.scale -> {
                bitmap.scale(
                    width = targetSize.width,
                    height = targetSize.height,
                )
            }

            else -> {
                centerBitmapOnOutput(
                    bitmap = bitmap,
                    targetSize = targetSize,
                )
            }
        }
    }

    private fun centerBitmapOnOutput(bitmap: Bitmap, targetSize: CropSize): Bitmap {
        val outputBitmap = createBitmap(targetSize.width, targetSize.height)

        val sourceRect = centeredSourceRect(
            bitmap = bitmap,
            targetSize = targetSize,
        )

        val destinationRect = centeredDestinationRect(
            sourceRect = sourceRect,
            targetSize = targetSize,
        )

        copyBitmapPixels(
            sourceBitmap = bitmap,
            outputBitmap = outputBitmap,
            sourceRect = sourceRect,
            destinationRect = destinationRect,
        )
        return outputBitmap
    }

    private fun copyBitmapPixels(
        sourceBitmap: Bitmap,
        outputBitmap: Bitmap,
        sourceRect: Rect,
        destinationRect: Rect,
    ) {
        val rowPixels = IntArray(sourceRect.width())
        for (rowIndex in 0 until sourceRect.height()) {
            sourceBitmap.getPixels(
                rowPixels,
                0,
                sourceRect.width(),
                sourceRect.left,
                sourceRect.top + rowIndex,
                sourceRect.width(),
                1,
            )
            outputBitmap.setPixels(
                rowPixels,
                0,
                sourceRect.width(),
                destinationRect.left,
                destinationRect.top + rowIndex,
                sourceRect.width(),
                1,
            )
        }
    }

    private fun centeredSourceRect(bitmap: Bitmap, targetSize: CropSize): Rect {
        val width = minOf(bitmap.width, targetSize.width)
        val height = minOf(bitmap.height, targetSize.height)
        val left = (bitmap.width - width) / 2
        val top = (bitmap.height - height) / 2

        return Rect(
            left,
            top,
            left + width,
            top + height,
        )
    }

    private fun centeredDestinationRect(sourceRect: Rect, targetSize: CropSize): Rect {
        val left = (targetSize.width - sourceRect.width()) / 2
        val top = (targetSize.height - sourceRect.height()) / 2

        return Rect(
            left,
            top,
            left + sourceRect.width(),
            top + sourceRect.height(),
        )
    }

    private fun downsampleForIntentResult(bitmap: Bitmap): Bitmap {
        val targetSize = resolveIntentBitmapSize(
            width = bitmap.width,
            height = bitmap.height,
        )

        val isSameSize = targetSize.width == bitmap.width && targetSize.height == bitmap.height

        return when {
            isSameSize -> bitmap
            else -> {
                bitmap.scale(
                    width = targetSize.width,
                    height = targetSize.height,
                )
            }
        }
    }

    private fun toCropPreviewBitmap(bitmap: Bitmap): Bitmap {
        val longestSide = maxOf(bitmap.width, bitmap.height)
        if (longestSide <= PREVIEW_MAX_SIZE) {
            return bitmap
        }

        val scale = PREVIEW_MAX_SIZE.toFloat() / longestSide.toFloat()

        return bitmap.scale(
            width = (bitmap.width.toFloat() * scale).toInt().coerceAtLeast(minimumValue = 1),
            height = (bitmap.height.toFloat() * scale).toInt().coerceAtLeast(minimumValue = 1),
        )
    }

    private fun writeCropOutput(
        uri: Uri,
        bitmap: Bitmap,
        saveFormat: SaveFormat,
    ): OutputWriteOutcome {
        return try {
            openWritableOutputStream(uri = uri)
                ?.use { output ->
                    compressOrThrow(
                        bitmap = bitmap,
                        saveFormat = saveFormat,
                        output = output,
                    )
                }
                ?: return OutputWriteOutcome.Failed

            OutputWriteOutcome.Written(uri = uri)
        } catch (exception: SecurityException) {
            // MediaProvider rejects writes to media we do not own; the user can still grant it.
            Log.w(TAG, "No write access to crop output $uri", exception)
            OutputWriteOutcome.PermissionDenied(uri = uri)
        } catch (exception: Exception) {
            Log.w(TAG, "Failed to write crop output to $uri", exception)
            OutputWriteOutcome.Failed
        }
    }

    private suspend fun insertCropOutput(
        bitmap: Bitmap,
        saveFormat: SaveFormat,
        displayName: String,
    ): Uri? {
        val uri = contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            pendingCropOutputValues(
                displayName = displayName,
                saveFormat = saveFormat,
            ),
        ) ?: return null

        return try {
            val wroteOutput = writeInsertedCropOutput(
                uri = uri,
                bitmap = bitmap,
                saveFormat = saveFormat,
            )

            if (!wroteOutput) {
                deleteInsertedCropOutput(
                    uri = uri,
                )
                return null
            }

            currentCoroutineContext().ensureActive()

            val publishedOutput = publishInsertedCropOutput(uri = uri)

            if (!publishedOutput) {
                deleteInsertedCropOutput(
                    uri = uri,
                )
                return null
            }

            uri
        } catch (exception: CancellationException) {
            deleteInsertedCropOutput(
                uri = uri,
            )
            throw exception
        } catch (exception: Exception) {
            Log.w(TAG, "Failed to insert crop output", exception)
            deleteInsertedCropOutput(
                uri = uri,
            )
            null
        }
    }

    private fun pendingCropOutputValues(
        displayName: String,
        saveFormat: SaveFormat,
    ): ContentValues {
        return ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, saveFormat.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Edited")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
    }

    private fun writeInsertedCropOutput(
        uri: Uri,
        bitmap: Bitmap,
        saveFormat: SaveFormat,
    ): Boolean {
        return openWritableOutputStream(uri = uri)?.use { output ->
            compressOrThrow(
                bitmap = bitmap,
                saveFormat = saveFormat,
                output = output,
            )
        } != null
    }

    private fun publishInsertedCropOutput(uri: Uri): Boolean {
        val publishedRows = contentResolver.update(
            uri,
            publishedCropOutputValues(),
            null,
            null,
        )

        return publishedRows > 0
    }

    private fun publishedCropOutputValues(): ContentValues {
        return ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
            put(
                MediaStore.MediaColumns.DATE_MODIFIED,
                System.currentTimeMillis() / 1000,
            )
        }
    }

    private fun deleteInsertedCropOutput(uri: Uri) {
        contentResolver.delete(uri, null, null)
    }

    private fun openWritableOutputStream(uri: Uri): OutputStream? {
        val truncatingStream = try {
            contentResolver.openOutputStream(uri, "wt")
        } catch (_: Exception) {
            null
        }

        return truncatingStream ?: contentResolver.openOutputStream(uri)
    }

    private fun croppedDisplayName(
        sourceDisplayName: String?,
        saveFormat: SaveFormat,
    ): String {
        val baseName = sourceDisplayName
            ?.substringBeforeLast(delimiter = '.', missingDelimiterValue = sourceDisplayName)
            ?.takeIf { it.isNotBlank() }
            ?: CROPPED_IMAGE_FALLBACK_NAME

        return "$baseName-cropped.${saveFormat.fileExtension}"
    }

    private fun compressOrThrow(
        bitmap: Bitmap,
        saveFormat: SaveFormat,
        output: OutputStream,
    ) {
        if (!bitmap.compress(saveFormat.compressFormat, 95, output)) {
            throw IOException("Compression failed")
        }
    }

    private fun useDisplayName(cursor: Cursor?): String? {
        return cursor?.use { currentCursor ->
            currentCursor
                .takeIf { it.moveToFirst() }
                ?.let { currentCursor ->
                    val displayNameIndex = currentCursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    when {
                        displayNameIndex >= 0 -> currentCursor.getString(displayNameIndex)
                        else -> null
                    }
                }
        }
    }

    private data class CropBitmapResult(
        val cropRect: CropPixelRect,
        val bitmap: Bitmap,
        val returnDataBitmap: Bitmap?,
    )

    /** Outcome of writing the crop bytes, before it is turned into an [ExternalCropSaveResult]. */
    private sealed interface OutputWriteOutcome {

        /** The uri is null when the caller only asked for `return-data`. */
        data class Written(val uri: Uri?) : OutputWriteOutcome

        data class PermissionDenied(val uri: Uri) : OutputWriteOutcome

        data object Failed : OutputWriteOutcome
    }

    private class ImageHeaderDecodedException : RuntimeException() {
        override fun fillInStackTrace(): Throwable {
            return this
        }
    }

    private companion object {
        private const val TAG = "ExternalCropRepository"
        private const val PREVIEW_MAX_SIZE = 2048
        private const val CROPPED_IMAGE_FALLBACK_NAME = "cropped_image"
    }
}
