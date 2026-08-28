package com.dot.gallery.feature_node.data.util

import android.content.ContentResolver
import android.database.MatrixCursor
import android.net.Uri
import android.provider.MediaStore
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
internal class MediaTimestampTest {
    @Test
    fun preservesKnownDatesWithoutFailingCopiesForUnavailableDates() {
        val source = Uri.parse("content://media/external/images/media/1")
        val destination = Uri.parse("content://media/external/images/media/2")
        val resolver = mockk<ContentResolver>()
        val file = File.createTempFile("copy-timestamp-", ".jpg")
        var modifiedSeconds: Long? = 946684800L
        every { resolver.query(source, any(), null, null, null) } answers {
            MatrixCursor(arrayOf(MediaStore.MediaColumns.DATE_MODIFIED)).apply {
                addRow(arrayOf(modifiedSeconds))
            }
        }
        every { resolver.query(destination, any(), null, null, null) } answers {
            MatrixCursor(arrayOf(MediaStore.MediaColumns.DATA)).apply {
                addRow(arrayOf(file.absolutePath))
            }
        }
        try {
            resolver.preserveMediaTimestamp(sourceUri = source, destinationUri = destination)
            assertEquals(946684800000L, file.lastModified())
            for (unavailable in listOf(null, 0L, -1L, Long.MAX_VALUE)) {
                modifiedSeconds = unavailable
                resolver.preserveMediaTimestamp(sourceUri = source, destinationUri = destination)
                assertEquals(946684800000L, file.lastModified())
            }
            every { resolver.query(source, any(), null, null, null) } throws SecurityException()
            resolver.preserveMediaTimestamp(sourceUri = source, destinationUri = destination)
            assertEquals(946684800000L, file.lastModified())
            every { resolver.query(source, any(), null, null, null) } throws CancellationException()
            assertThrows(CancellationException::class.java) {
                resolver.preserveMediaTimestamp(sourceUri = source, destinationUri = destination)
            }
        } finally {
            check(file.delete())
        }
    }
}
