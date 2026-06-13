package com.dot.gallery.feature_node.data.data_source.mediastore.queries

import android.app.Application
import android.content.ContentResolver
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.provider.MediaStore
import com.dot.gallery.core.util.MediaStoreBuckets
import com.dot.gallery.feature_node.data.data_source.mediastore.MediaQuery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(application = Application::class)
@RunWith(RobolectricTestRunner::class)
internal class NullableMediaColumnsTest {
    @Test
    fun mediaQueriesRetainRowsWithNullStrings() {
        runBlocking {
            val resolver = mockk<ContentResolver>(relaxed = true)
            every { resolver.query(any(), any(), any(), any<CancellationSignal>()) } answers {
                val projection = arg<Array<String>>(1)
                MatrixCursor(projection).apply {
                    addRow(projection.map { column ->
                        when (column) {
                            MediaStore.MediaColumns._ID -> 42L
                            MediaStore.MediaColumns.MIME_TYPE -> "image/jpeg"
                            else -> null
                        }
                    })
                }
            }
            val queries = listOf(
                MediaFlow(
                    contentResolver = resolver,
                    buckedId = MediaStoreBuckets.MEDIA_STORE_BUCKET_TIMELINE.id,
                    skipBatching = true,
                ),
                MediaUriFlow(
                    contentResolver = resolver,
                    uris = listOf(Uri.parse("content://media/external/images/media/42")),
                ),
            )
            for (query in queries) {
                val media = query.flowData().first().single()
                assertEquals(42L, media.id)
                assertEquals("", media.path)
                assertEquals("", media.relativePath)
                assertEquals("", media.label)
            }
        }
    }
}
