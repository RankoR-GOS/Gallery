package com.dot.gallery.feature_node.data.repository

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.dot.gallery.feature_node.data.util.resolveMediaStoreVolume
import com.dot.gallery.injection.qualifier.IoDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject

private const val TAG = "MediaCopyRepository"

internal interface MediaCopyRepository {

    suspend fun getMediaSize(uri: Uri): Long?

    suspend fun copyMedia(
        sourceUri: Uri,
        destinationPath: String,
        onBytesCopied: suspend (Int) -> Unit,
    ): Uri?

    suspend fun notifyMediaChanged()
}

internal class MediaCopyRepositoryImpl @Inject constructor(
    private val contentResolver: ContentResolver,
    @param:IoDispatcher
    private val ioDispatcher: CoroutineDispatcher,
) : MediaCopyRepository {

    override suspend fun getMediaSize(uri: Uri): Long? {
        return withContext(ioDispatcher) {
            try {
                contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                    descriptor.length.takeIf { length -> length > 0L }
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                Log.w(TAG, "Failed to read media size for $uri", exception)
                null
            }
        }
    }

    override suspend fun copyMedia(
        sourceUri: Uri,
        destinationPath: String,
        onBytesCopied: suspend (Int) -> Unit,
    ): Uri? {
        return withContext(ioDispatcher) {
            var destinationUri: Uri? = null
            var destinationPublished = false

            try {
                destinationUri = insertPendingMedia(
                    sourceUri = sourceUri,
                    destinationPath = destinationPath,
                )

                when (val insertedUri = destinationUri) {
                    null -> null
                    else -> {
                        val mediaCopied = copyMediaBytes(
                            sourceUri = sourceUri,
                            destinationUri = insertedUri,
                            onBytesCopied = onBytesCopied,
                        )
                        destinationPublished = mediaCopied && publishMedia(uri = insertedUri)
                        insertedUri.takeIf { destinationPublished }
                    }
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                Log.w(TAG, "Failed to copy media from $sourceUri", exception)
                null
            } finally {
                if (!destinationPublished) {
                    destinationUri?.let { uri ->
                        deleteUnpublishedMedia(uri = uri)
                    }
                }
            }
        }
    }

    override suspend fun notifyMediaChanged() {
        withContext(ioDispatcher) {
            try {
                contentResolver.notifyChange(
                    MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
                    null,
                )
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                Log.w(TAG, "Failed to notify MediaStore after copying media", exception)
            }
        }
    }

    private fun insertPendingMedia(sourceUri: Uri, destinationPath: String): Uri? {
        val mimeType = contentResolver.getType(sourceUri)
        val (volumeName, relativePath) = resolveMediaStoreVolume(destinationPath)
        val destinationCollection = mimeType?.let { type ->
            getDestinationCollection(
                volumeName = volumeName,
                mimeType = type,
            )
        }

        return when {
            mimeType == null || destinationCollection == null -> null
            else -> {
                contentResolver.insert(
                    destinationCollection,
                    pendingMediaValues(
                        sourceUri = sourceUri,
                        relativePath = relativePath,
                        mimeType = mimeType,
                    ),
                )
            }
        }
    }

    private fun getDestinationCollection(volumeName: String, mimeType: String): Uri? {
        return when {
            mimeType.startsWith("image/") -> MediaStore.Images.Media.getContentUri(volumeName)
            mimeType.startsWith("video/") -> MediaStore.Video.Media.getContentUri(volumeName)
            else -> null
        }
    }

    private fun pendingMediaValues(
        sourceUri: Uri,
        relativePath: String,
        mimeType: String,
    ): ContentValues {
        return ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, sourceUri.lastPathSegment)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
    }

    private suspend fun copyMediaBytes(
        sourceUri: Uri,
        destinationUri: Uri,
        onBytesCopied: suspend (Int) -> Unit,
    ): Boolean {
        return contentResolver.openInputStream(sourceUri)?.use { input ->
            contentResolver.openOutputStream(destinationUri)?.use { output ->
                copyBytes(
                    input = input,
                    output = output,
                    onBytesCopied = onBytesCopied,
                )
                true
            }
        } == true
    }

    private suspend fun copyBytes(
        input: InputStream,
        output: OutputStream,
        onBytesCopied: suspend (Int) -> Unit,
    ) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val bytesRead = input.read(buffer)
            if (bytesRead == -1) {
                break
            }
            output.write(buffer, 0, bytesRead)
            onBytesCopied(bytesRead)
        }
        output.flush()
    }

    private fun publishMedia(uri: Uri): Boolean {
        val publishedRows = contentResolver.update(
            uri,
            ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
                put(
                    MediaStore.MediaColumns.DATE_MODIFIED,
                    System.currentTimeMillis() / 1000,
                )
            },
            null,
            null,
        )

        return publishedRows > 0
    }

    private fun deleteUnpublishedMedia(uri: Uri) {
        try {
            contentResolver.delete(uri, null, null)
        } catch (exception: Exception) {
            Log.w(TAG, "Failed to delete unpublished media at $uri", exception)
        }
    }
}
