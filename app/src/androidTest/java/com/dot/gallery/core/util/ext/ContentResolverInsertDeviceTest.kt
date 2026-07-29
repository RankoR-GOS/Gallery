package com.dot.gallery.core.util.ext

import android.content.ContentResolver
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
internal class ContentResolverInsertDeviceTest {

    private val contentResolver: ContentResolver =
        InstrumentationRegistry.getInstrumentation().targetContext.contentResolver

    @Test
    fun saveRawStream_publishesOnlyAfterSuccessfulWrite() {
        runBlocking {
            val displayName = "gallery-pending-${UUID.randomUUID()}.png"
            val expectedBytes = byteArrayOf(1, 2, 3, 4)
            var destinationUri: Uri? = null
            try {
                destinationUri = contentResolver.saveRawStream(
                    writeBlock = { outputStream -> outputStream.write(expectedBytes) },
                    mimeType = "image/png",
                    displayName = displayName,
                )

                assertTrue(destinationUri != null)
                val values = queryPublishedValues(uri = requireNotNull(destinationUri))
                assertEquals(0, values.isPending)
                assertTrue(values.dateModified > 0L)
                val actualBytes = contentResolver.openInputStream(destinationUri)?.use { input ->
                    input.readBytes()
                }
                assertArrayEquals(expectedBytes, actualBytes)
            } finally {
                destinationUri?.let { uri -> contentResolver.delete(uri, null, null) }
            }
        }
    }

    @Test
    fun saveRawStream_deletesRowWhenWriteFails() {
        runBlocking {
            val displayName = "gallery-failed-${UUID.randomUUID()}.png"

            val destinationUri = contentResolver.saveRawStream(
                writeBlock = { outputStream ->
                    outputStream.write(byteArrayOf(1, 2, 3))
                    throw IOException("forced failure")
                },
                mimeType = "image/png",
                displayName = displayName,
            )

            assertNull(destinationUri)
            assertEquals(0, countRows(displayName = displayName))
        }
    }

    @Test
    fun saveRawStream_cancelledBeforePublicationDeletesPendingRow() {
        runBlocking {
            val displayName = "gallery-cancelled-${UUID.randomUUID()}.png"
            val writeStarted = CountDownLatch(1)
            val releaseWrite = CountDownLatch(1)
            val saveJob = launch(Dispatchers.IO) {
                contentResolver.saveRawStream(
                    writeBlock = { outputStream ->
                        outputStream.write(byteArrayOf(1, 2, 3))
                        writeStarted.countDown()
                        check(releaseWrite.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    },
                    mimeType = "image/png",
                    displayName = displayName,
                )
            }

            assertTrue(writeStarted.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            saveJob.cancel()
            releaseWrite.countDown()
            withTimeout(TEST_TIMEOUT_MILLIS.milliseconds) {
                saveJob.cancelAndJoin()
            }

            assertEquals(0, countRows(displayName = displayName))
        }
    }

    private fun queryPublishedValues(uri: Uri): PublishedValues {
        return contentResolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.DATE_MODIFIED),
            null,
            null,
        )?.use { cursor ->
            check(cursor.moveToFirst())
            PublishedValues(
                isPending = cursor.getInt(0),
                dateModified = cursor.getLong(1),
            )
        } ?: error("Inserted media could not be queried")
    }

    private fun countRows(displayName: String): Int {
        val queryArgs = Bundle().apply {
            putString(
                ContentResolver.QUERY_ARG_SQL_SELECTION,
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
            )
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(displayName))
        }
        return contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns._ID),
            queryArgs,
            null,
        )?.use { cursor -> cursor.count } ?: 0
    }

    private class PublishedValues(
        val isPending: Int,
        val dateModified: Long,
    )

    private companion object {
        private const val TEST_TIMEOUT_MILLIS = 5_000L
        private const val TEST_TIMEOUT_SECONDS = 5L
    }
}
