package com.dot.gallery.feature_node.data.repository

import android.content.ContentResolver
import android.net.Uri
import com.dot.gallery.feature_node.domain.model.securereview.AuthorizedSecureReviewRequest
import io.mockk.every
import io.mockk.mockk
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
internal class SecureReviewMediaRepositoryTest {

    @Test
    fun loadMedia_createsOrderedReadOnlyImageAndVideoModels() {
        runTest {
            val imageUri = Uri.parse("content://media/external/images/media/10")
            val videoUri = Uri.parse("content://camera/review/video")
            val contentResolver = mockk<ContentResolver>()
            every { contentResolver.getType(imageUri) } returns " Image/JPEG ; charset=binary"
            every { contentResolver.getType(videoUri) } returns "video/mp4"
            val repository = repository(
                contentResolver = contentResolver,
                ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            )

            val media = repository.loadMedia(
                request = AuthorizedSecureReviewRequest(
                    uris = persistentListOf(imageUri, videoUri),
                ),
            )

            assertNotNull(media)
            requireNotNull(media)
            assertEquals(listOf(imageUri, videoUri), media.map { it.uri })
            assertEquals(listOf(-1L, -2L), media.map { it.id })
            assertEquals(listOf("image/jpeg", "video/mp4"), media.map { it.mimeType })
            assertTrue(media.all { it.albumID == -99L && it.path.isEmpty() })
        }
    }

    @Test
    fun loadMedia_rejectsUnsupportedMimeTypeWithoutPartialResult() {
        runTest {
            val imageUri = Uri.parse("content://camera/image/1")
            val unsupportedUri = Uri.parse("content://camera/document/2")
            val contentResolver = mockk<ContentResolver>()
            every { contentResolver.getType(imageUri) } returns "image/png"
            every { contentResolver.getType(unsupportedUri) } returns "application/pdf"
            val repository = repository(
                contentResolver = contentResolver,
                ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            )

            val media = repository.loadMedia(
                request = AuthorizedSecureReviewRequest(
                    uris = persistentListOf(imageUri, unsupportedUri),
                ),
            )

            assertNull(media)
        }
    }

    @Test
    fun loadMedia_rejectsMissingMimeType() {
        runTest {
            val uri = Uri.parse("content://camera/image/1")
            val contentResolver = mockk<ContentResolver>()
            every { contentResolver.getType(uri) } returns null
            val repository = repository(
                contentResolver = contentResolver,
                ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            )

            val media = repository.loadMedia(
                request = AuthorizedSecureReviewRequest(
                    uris = persistentListOf(uri),
                ),
            )

            assertNull(media)
        }
    }

    @Test
    fun loadMedia_rejectsProviderExceptionsWithoutPartialResult() {
        runTest {
            val uri = Uri.parse("content://camera/image/1")
            val exceptions = listOf(
                IllegalArgumentException("invalid URI"),
                SecurityException("grant revoked"),
            )

            exceptions.forEach { exception ->
                val contentResolver = mockk<ContentResolver>()
                every { contentResolver.getType(uri) } throws exception

                val media = repository(
                    contentResolver = contentResolver,
                    ioDispatcher = UnconfinedTestDispatcher(testScheduler),
                ).loadMedia(
                    request = AuthorizedSecureReviewRequest(
                        uris = persistentListOf(uri),
                    ),
                )

                assertNull(media)
            }
        }
    }

    private fun repository(
        contentResolver: ContentResolver,
        ioDispatcher: CoroutineDispatcher,
    ): SecureReviewMediaRepositoryImpl {
        return SecureReviewMediaRepositoryImpl(
            contentResolver = contentResolver,
            ioDispatcher = ioDispatcher,
        )
    }
}
