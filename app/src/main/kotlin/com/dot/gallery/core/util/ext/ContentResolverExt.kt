/*
 * SPDX-FileCopyrightText: 2023 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.core.util.ext

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.database.ContentObserver
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.OperationCanceledException
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import com.dot.gallery.core.util.runCancellableContentProviderOperation
import com.dot.gallery.feature_node.data.model.Media
import com.dot.gallery.feature_node.data.util.getUri
import com.dot.gallery.feature_node.presentation.util.printWarning
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withContext

private const val MILLIS_PER_SECOND = 1_000L
private const val QUERY_DEBOUNCE_MILLIS = 250L
private const val STEPPED_QUERY_LIMIT = 250

fun ContentResolver.querySteppedFlow(
    uri: Uri,
    projection: Array<String>? = null,
    queryArgs: Bundle? = Bundle(),
): Flow<Cursor?> {
    return observedQueryFlow(
        uri = uri,
        projection = projection,
        queryArgs = queryArgs,
        steppedInitialQuery = true,
    )
}

fun ContentResolver.queryFlow(
    uri: Uri,
    projection: Array<String>? = null,
    queryArgs: Bundle? = Bundle(),
): Flow<Cursor?> {
    return observedQueryFlow(
        uri = uri,
        projection = projection,
        queryArgs = queryArgs,
        steppedInitialQuery = false,
    )
}

@OptIn(FlowPreview::class)
private fun ContentResolver.observedQueryFlow(
    uri: Uri,
    projection: Array<String>?,
    queryArgs: Bundle?,
    steppedInitialQuery: Boolean,
): Flow<Cursor?> {
    return channelFlow {
        val activeCancellationSignal = AtomicReference<CancellationSignal?>(null)
        val invalidationGeneration = AtomicLong(0L)
        val invalidations = Channel<Long>(capacity = Channel.CONFLATED)
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                val generation = invalidationGeneration.incrementAndGet()
                activeCancellationSignal.getAndSet(null)?.cancel()
                invalidations.trySend(generation)
            }
        }

        registerContentObserver(uri, true, observer)
        var isInitialQuery = true
        try {
            merge(
                flowOf(0L),
                invalidations.receiveAsFlow().debounce(QUERY_DEBOUNCE_MILLIS),
            ).collectLatest { generation ->
                val shouldUseSteppedQuery = isInitialQuery && steppedInitialQuery
                isInitialQuery = false
                runObservedQuery(
                    output = this,
                    uri = uri,
                    projection = projection,
                    queryArgs = queryArgs,
                    stepped = shouldUseSteppedQuery,
                    activeCancellationSignal = activeCancellationSignal,
                    invalidationGeneration = invalidationGeneration,
                    expectedGeneration = generation,
                )
            }
        } finally {
            unregisterContentObserver(observer)
            activeCancellationSignal.getAndSet(null)?.cancel()
            invalidations.close()
        }
    }
}

private suspend fun ContentResolver.runObservedQuery(
    output: ProducerScope<Cursor?>,
    uri: Uri,
    projection: Array<String>?,
    queryArgs: Bundle?,
    stepped: Boolean,
    activeCancellationSignal: AtomicReference<CancellationSignal?>,
    invalidationGeneration: AtomicLong,
    expectedGeneration: Long,
) {
    try {
        withContext(Dispatchers.IO) {
            if (stepped) {
                val limitedArgs = queryArgs?.deepCopy()?.apply {
                    putString(ContentResolver.QUERY_ARG_SQL_LIMIT, STEPPED_QUERY_LIMIT.toString())
                }
                val limitedCursor = queryWithCancellation(
                    uri = uri,
                    projection = projection,
                    queryArgs = limitedArgs,
                    activeCancellationSignal = activeCancellationSignal,
                    invalidationGeneration = invalidationGeneration,
                    expectedGeneration = expectedGeneration,
                )
                val limitedCount = limitedCursor?.count ?: 0
                output.sendOwnedCursor(cursor = limitedCursor)
                if (limitedCount < STEPPED_QUERY_LIMIT) {
                    return@withContext
                }
            }

            val cursor = queryWithCancellation(
                uri = uri,
                projection = projection,
                queryArgs = queryArgs,
                activeCancellationSignal = activeCancellationSignal,
                invalidationGeneration = invalidationGeneration,
                expectedGeneration = expectedGeneration,
            )
            output.sendOwnedCursor(cursor = cursor)
        }
    } catch (exception: CancellationException) {
        throw exception
    } catch (_: OperationCanceledException) {
        // A newer observer event cancelled this obsolete query.
    } catch (exception: Exception) {
        printWarning("MediaStore query failed: ${exception.message.orEmpty()}")
    }
}

private suspend fun ContentResolver.queryWithCancellation(
    uri: Uri,
    projection: Array<String>?,
    queryArgs: Bundle?,
    activeCancellationSignal: AtomicReference<CancellationSignal?>,
    invalidationGeneration: AtomicLong,
    expectedGeneration: Long,
): Cursor? {
    return runCancellableContentProviderOperation(
        disposeResult = { cursor -> cursor?.close() },
    ) { cancellationSignal ->
        activeCancellationSignal.set(cancellationSignal)
        if (invalidationGeneration.get() != expectedGeneration) {
            cancellationSignal.cancel()
        }
        try {
            val cursor = query(uri, projection, queryArgs, cancellationSignal)
            if (invalidationGeneration.get() != expectedGeneration) {
                cursor?.close()
                throw OperationCanceledException()
            }
            cursor
        } finally {
            activeCancellationSignal.compareAndSet(cancellationSignal, null)
        }
    }
}

private suspend fun ProducerScope<Cursor?>.sendOwnedCursor(
    cursor: Cursor?,
) {
    try {
        send(cursor)
    } catch (exception: Throwable) {
        cursor?.close()
        throw exception
    }
}

suspend fun ContentResolver.restoreImage(
    data: ByteArray,
    format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG,
    mimeType: String = "image/png",
    relativePath: String = "${Environment.DIRECTORY_PICTURES}/Restored",
    displayName: String
): Uri? = saveBitmap(
    BitmapFactory.decodeByteArray(data, 0, data.size),
    format,
    mimeType,
    relativePath,
    displayName
)

suspend fun ContentResolver.saveImage(
    bitmap: Bitmap,
    format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG,
    mimeType: String = "image/png",
    relativePath: String = Environment.DIRECTORY_PICTURES,
    displayName: String
): Uri? = saveBitmap(
    bitmap,
    format,
    mimeType,
    relativePath,
    displayName
)

private suspend fun ContentResolver.saveBitmap(
    bitmap: Bitmap,
    format: Bitmap.CompressFormat,
    mimeType: String,
    relativePath: String,
    displayName: String
): Uri? = performInsertWrite(
    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
    ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
        put(
            MediaStore.MediaColumns.RELATIVE_PATH,
            if (relativePath.contains("DCIM") || relativePath.contains("Pictures"))
                relativePath
            else Environment.DIRECTORY_PICTURES + "/Edited"
        )
    }
) { out ->
    if (!bitmap.compress(format, 95, out)) throw IOException("Compression failed")
}

suspend fun ContentResolver.saveVideo(
    data: ByteArray,
    mimeType: String,
    relativePath: String = Environment.DIRECTORY_MOVIES,
    displayName: String
): Uri? = performInsertWrite(
    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
    ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
        put(
            MediaStore.MediaColumns.RELATIVE_PATH,
            if (relativePath.contains("DCIM") || relativePath.contains("Movies"))
                relativePath
            else Environment.DIRECTORY_MOVIES + "/Edited"
        )
    }
) { out ->
    out.write(data)
}

/**
 * Saves raw image bytes without bitmap conversion.
 * Use this for formats like GIF, WebP that would lose animation/quality if converted.
 */
suspend fun ContentResolver.saveRawImage(
    data: ByteArray,
    mimeType: String,
    relativePath: String = Environment.DIRECTORY_PICTURES,
    displayName: String
): Uri? = performInsertWrite(
    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
    ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
        put(
            MediaStore.MediaColumns.RELATIVE_PATH,
            if (relativePath.contains("DCIM") || relativePath.contains("Pictures"))
                relativePath
            else Environment.DIRECTORY_PICTURES + "/Restored"
        )
    }
) { out ->
    out.write(data)
}

suspend fun ContentResolver.saveRawStream(
    writeBlock: (OutputStream) -> Unit,
    mimeType: String,
    relativePath: String = Environment.DIRECTORY_PICTURES,
    displayName: String
): Uri? = performInsertWrite(
    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
    ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
        put(
            MediaStore.MediaColumns.RELATIVE_PATH,
            if (relativePath.contains("DCIM") || relativePath.contains("Pictures"))
                relativePath
            else Environment.DIRECTORY_PICTURES + "/Restored"
        )
    }
) { out ->
    writeBlock(out)
}

suspend fun ContentResolver.saveVideoStream(
    writeBlock: (OutputStream) -> Unit,
    mimeType: String,
    relativePath: String = Environment.DIRECTORY_MOVIES,
    displayName: String
): Uri? = performInsertWrite(
    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
    ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
        put(
            MediaStore.MediaColumns.RELATIVE_PATH,
            if (relativePath.contains("DCIM") || relativePath.contains("Movies"))
                relativePath
            else Environment.DIRECTORY_MOVIES + "/Edited"
        )
    }
) { out ->
    writeBlock(out)
}

private suspend fun ContentResolver.performInsertWrite(
    baseUri: Uri,
    values: ContentValues,
    writeBlock: (OutputStream) -> Unit
): Uri? = withContext(Dispatchers.IO) {
    var tmp: Uri? = null
    runCatching {
        insert(baseUri, values)?.also { uri ->
            tmp = uri
            openOutputStream(uri)?.use(writeBlock)
                ?: throw IOException("Stream open failed")
        } ?: throw IOException("Insert returned null")
    }.getOrElse {
        tmp?.let { delete(it, null, null) }
        null
    }
}

suspend fun <T : Media> Context.renameMedia(media: T, newName: String): Boolean =
    withContext(Dispatchers.IO) {
        runCatching {
            contentResolver.update(
                media.getUri(),
                ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, newName) },
                null,
                null,
            ) > 0
        }.onSuccess {
            MediaScannerConnection.scanFile(
                this@renameMedia, arrayOf(media.path.removeSuffix(media.label)),
                arrayOf(media.mimeType), null
            )
        }.getOrElse {
            printWarning(it.message.toString())
            false
        }
    }

suspend fun <T : Media> Context.updateMedia(
    media: T,
    contentValues: ContentValues,
): Boolean {
    return withContext(Dispatchers.IO) {
        runCatching {
            contentResolver.update(media.getUri(), contentValues, null, null) > 0
        }.onSuccess { updated ->
            if (updated) {
                // If RELATIVE_PATH changed, scan the new file location.
                val newRelativePath =
                    contentValues.getAsString(MediaStore.MediaColumns.RELATIVE_PATH)
                val scanPath = if (newRelativePath != null) {
                    val volumePrefix = media.path.substringBeforeLast("/")
                        .removeSuffix(media.relativePath.removeSuffix("/"))
                        .trimEnd('/')
                    "${volumePrefix}/${newRelativePath.trimEnd('/')}/${media.label}"
                } else {
                    media.path
                }
                MediaScannerConnection.scanFile(
                    this@updateMedia, arrayOf(scanPath),
                    arrayOf(media.mimeType), null
                )
            }
        }.getOrElse {
            printWarning(it.message.toString())
            false
        }
    }
}

suspend fun <T : Media> Context.updateMediaExif(
    media: T,
    action: suspend ExifInterface.(T) -> Unit,
    postAction: suspend (T) -> Unit
): Boolean = withContext(Dispatchers.IO) {
    runCatching {
        contentResolver.openFileDescriptor(media.getUri(), "rw")?.use { pfd ->
            ExifInterface(pfd.fileDescriptor).apply {
                action(media)
                saveAttributes()
            }
        } ?: throw IOException("PFD null")
        updateMedia(media, ContentValues().apply {
            put(MediaStore.MediaColumns.DATE_MODIFIED, System.currentTimeMillis())
        })
        postAction(media)
        true
    }.getOrElse {
        printWarning(it.message.toString())
        false
    }
}
