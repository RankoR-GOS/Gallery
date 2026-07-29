package com.dot.gallery.core.util.ext

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verifyOrder
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
internal class ContentResolverInsertTest {

    @Test
    fun saveRawStream_insertsPendingWritesThenPublishes() {
        runBlocking {
            val contentResolver = mockk<ContentResolver>()
            val destinationUri = Uri.parse("content://media/external/images/media/1")
            val insertedValues = slot<ContentValues>()
            val publishedValues = slot<ContentValues>()
            val outputStream = ByteArrayOutputStream()
            every {
                contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    capture(insertedValues),
                )
            } returns destinationUri
            every { contentResolver.openOutputStream(destinationUri) } returns outputStream
            every {
                contentResolver.update(
                    destinationUri,
                    capture(publishedValues),
                    null,
                    null,
                )
            } answers {
                assertEquals(
                    1,
                    insertedValues.captured.getAsInteger(MediaStore.MediaColumns.IS_PENDING),
                )
                assertEquals(EXPECTED_BYTES.toList(), outputStream.toByteArray().toList())
                1
            }

            val result = contentResolver.saveRawStream(
                writeBlock = { stream -> stream.write(EXPECTED_BYTES) },
                mimeType = "image/png",
                displayName = "test.png",
            )

            assertEquals(destinationUri, result)
            assertEquals(1, insertedValues.captured.getAsInteger(MediaStore.MediaColumns.IS_PENDING))
            assertEquals(0, publishedValues.captured.getAsInteger(MediaStore.MediaColumns.IS_PENDING))
            assertNotNull(publishedValues.captured.getAsLong(MediaStore.MediaColumns.DATE_MODIFIED))
            assertEquals(EXPECTED_BYTES.toList(), outputStream.toByteArray().toList())
            verifyOrder {
                contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    any(),
                )
                contentResolver.openOutputStream(destinationUri)
                contentResolver.update(destinationUri, any(), null, null)
            }
        }
    }

    private companion object {
        private val EXPECTED_BYTES = byteArrayOf(1, 2, 3)
    }
}
