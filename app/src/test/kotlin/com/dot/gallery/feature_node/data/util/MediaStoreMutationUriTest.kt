package com.dot.gallery.feature_node.data.util

import android.app.Application
import android.content.ContentResolver
import android.database.MatrixCursor
import android.net.Uri
import android.provider.MediaStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(application = Application::class)
@RunWith(RobolectricTestRunner::class)
internal class MediaStoreMutationUriTest {
    @Test
    fun preservesPhysicalVolumesAndResolvesLegacyAggregateUris() {
        val resolver = mockk<ContentResolver>()
        val primary = mediaStoreItemUri(id = 42L, mimeType = "image/jpeg", volumeName = "external_primary")
        val removable = mediaStoreItemUri(id = 43L, mimeType = "video/mp4", volumeName = "abcd-1234")
        assertEquals("content://media/external_primary/images/media/42", primary.toString())
        assertEquals("content://media/abcd-1234/video/media/43", removable.toString())
        assertEquals(primary, resolver.resolveMediaStoreMutationUri(uri = primary))
        assertEquals(removable, resolver.resolveMediaStoreMutationUri(uri = removable))
        verify(exactly = 0) { resolver.query(any(), any(), any(), any(), any()) }
        val aggregate = Uri.parse("content://media/external/video/media/43")
        every { resolver.query(aggregate, any(), null, null, null) } returns MatrixCursor(
            arrayOf(MediaStore.MediaColumns.VOLUME_NAME),
        ).apply { addRow(arrayOf("abcd-1234")) }
        assertEquals(removable, resolver.resolveMediaStoreMutationUri(uri = aggregate))
    }

    @Test
    fun rejectsUnknownRowsAndNonMediaUrisInsteadOfGuessingOrDeleting() {
        val resolver = mockk<ContentResolver>()
        every { resolver.query(any(), any(), any(), any(), any()) } returns null
        for (value in listOf(
            "content://other.provider/images/media/1",
            "content://media/external/images/media/1",
            "content://media/external_primary/file/1",
            "content://media/external_primary/images/media/not-an-id",
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                resolver.resolveMediaStoreMutationUri(uri = Uri.parse(value))
            }
        }
    }
}
