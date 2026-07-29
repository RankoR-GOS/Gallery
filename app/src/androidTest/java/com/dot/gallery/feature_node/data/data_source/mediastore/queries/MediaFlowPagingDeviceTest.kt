package com.dot.gallery.feature_node.data.data_source.mediastore.queries

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dot.gallery.core.util.MediaStoreBuckets
import com.dot.gallery.feature_node.data.data_source.mediastore.MediaQuery
import com.dot.gallery.feature_node.data.model.Media
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
internal class MediaFlowPagingDeviceTest {

    private val contentResolver: ContentResolver =
        InstrumentationRegistry.getInstrumentation().targetContext.contentResolver

    @Test
    fun consecutivePages_areAscendingLimitedAndNonOverlapping() {
        runBlocking {
            val initialHighestId = getHighestMediaId()
            val insertedUris = mutableListOf<Uri>()
            try {
                repeat(3) { index ->
                    insertedUris += insertImage(index = index)
                }
                val insertedIds = insertedUris.map(ContentUris::parseId)

                val firstPage = loadPage(afterId = initialHighestId, limit = 2)
                val secondPage = loadPage(afterId = firstPage.last().id, limit = 2)

                assertEquals(insertedIds.take(2), firstPage.map { media -> media.id })
                assertEquals(insertedIds.drop(2), secondPage.map { media -> media.id })
            } finally {
                insertedUris.forEach { uri -> contentResolver.delete(uri, null, null) }
            }
        }
    }

    private suspend fun loadPage(afterId: Long, limit: Int): List<Media.UriMedia> {
        return MediaFlow(
            contentResolver = contentResolver,
            buckedId = MediaStoreBuckets.MEDIA_STORE_BUCKET_TIMELINE.id,
            skipBatching = true,
            afterId = afterId,
            limit = limit,
        ).flowData().first()
    }

    private fun getHighestMediaId(): Long {
        val queryArgs = Bundle().apply {
            putString(
                ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
                "${MediaStore.Files.FileColumns._ID} DESC",
            )
            putInt(ContentResolver.QUERY_ARG_LIMIT, 1)
        }
        return contentResolver.query(
            MediaQuery.MediaStoreFileUri,
            arrayOf(MediaStore.Files.FileColumns._ID),
            queryArgs,
            null,
        )?.use { cursor ->
            when {
                cursor.moveToFirst() -> cursor.getLong(0)
                else -> 0L
            }
        } ?: 0L
    }

    private fun insertImage(index: Int): Uri {
        val destinationUri = contentResolver.insert(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            ContentValues().apply {
                put(
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    "media-flow-${UUID.randomUUID()}-$index.png",
                )
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(MediaStore.MediaColumns.RELATIVE_PATH, TEST_RELATIVE_PATH)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            },
        ) ?: error("Could not insert MediaStore test image")
        return try {
            contentResolver.openOutputStream(destinationUri)?.use { outputStream ->
                outputStream.write(PNG_SIGNATURE)
            } ?: error("Could not open MediaStore test image")
            val updatedRows = contentResolver.update(
                destinationUri,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                },
                null,
                null,
            )
            check(updatedRows == 1) { "Could not publish MediaStore test image" }
            destinationUri
        } catch (exception: Exception) {
            contentResolver.delete(destinationUri, null, null)
            throw exception
        }
    }

    private companion object {
        private const val TEST_RELATIVE_PATH = "Pictures/ReFraMediaFlowTests"
        private val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(),
            0x50,
            0x4E,
            0x47,
            0x0D,
            0x0A,
            0x1A,
            0x0A,
        )
    }
}
