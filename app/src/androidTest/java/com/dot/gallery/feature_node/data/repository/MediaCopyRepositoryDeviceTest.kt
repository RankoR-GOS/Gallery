package com.dot.gallery.feature_node.data.repository

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaCopyRepositoryDeviceTest {

    @Test
    fun copyMedia_publishesByteIdenticalMediaStoreDestination() {
        val contentResolver = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .contentResolver
        val sourceName = "media-copy-café 日本語-${System.nanoTime()}.jpg"
        val sourceBytes = byteArrayOf(1, 3, 5, 7, 9)
        var sourceUri: Uri? = null
        var destinationUri: Uri? = null

        try {
            val insertedSourceUri = insertTestMedia(
                contentResolver = contentResolver,
                displayName = sourceName,
                bytes = sourceBytes,
            )
            sourceUri = insertedSourceUri
            val repository = MediaCopyRepositoryImpl(
                contentResolver = contentResolver,
                ioDispatcher = Dispatchers.IO,
            )

            val publishedDestinationUri = requireNotNull(
                runBlocking {
                    repository.copyMedia(
                        sourceUri = insertedSourceUri,
                        destinationPath = "$testRelativePath/Copies",
                        onBytesCopied = {},
                    )
                },
            ) { "Media copy failed" }
            destinationUri = publishedDestinationUri

            assertEquals(
                0,
                getPendingState(
                    contentResolver = contentResolver,
                    uri = publishedDestinationUri,
                ),
            )
            contentResolver.query(
                publishedDestinationUri,
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                check(cursor.moveToFirst())
                assertEquals(sourceName, cursor.getString(0))
            } ?: error("Copied row is missing")
            assertArrayEquals(
                sourceBytes,
                readBytes(
                    contentResolver = contentResolver,
                    uri = publishedDestinationUri,
                ),
            )
        } finally {
            destinationUri?.let { uri ->
                contentResolver.delete(uri, null, null)
            }
            sourceUri?.let { uri ->
                contentResolver.delete(uri, null, null)
            }
        }
    }

    private fun insertTestMedia(
        contentResolver: ContentResolver,
        displayName: String,
        bytes: ByteArray,
    ): Uri {
        val uri = requireNotNull(
            contentResolver.insert(
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, testRelativePath)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                },
            ),
        ) { "Failed to insert source test media" }

        try {
            contentResolver.openOutputStream(uri)?.use { output ->
                output.write(bytes)
            } ?: throw IOException("Failed to open source test media")
            val publishedRows = contentResolver.update(
                uri,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                },
                null,
                null,
            )
            check(publishedRows > 0) { "Failed to publish source test media" }
            return uri
        } catch (exception: Exception) {
            contentResolver.delete(uri, null, null)
            throw exception
        }
    }

    private fun getPendingState(contentResolver: ContentResolver, uri: Uri): Int {
        return contentResolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns.IS_PENDING),
            null,
            null,
            null,
        )?.use { cursor ->
            check(cursor.moveToFirst()) { "Destination row is missing" }
            cursor.getInt(0)
        } ?: error("Failed to query destination row")
    }

    private fun readBytes(contentResolver: ContentResolver, uri: Uri): ByteArray {
        return contentResolver.openInputStream(uri)?.use { input ->
            input.readBytes()
        } ?: error("Failed to read destination media")
    }

    companion object {
        private val testRelativePath = Environment.DIRECTORY_PICTURES + "/GalleryMediaCopyTest"
    }
}
