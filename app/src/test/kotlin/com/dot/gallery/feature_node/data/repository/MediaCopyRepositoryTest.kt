package com.dot.gallery.feature_node.data.repository

import android.content.ContentResolver
import android.content.ContentValues
import android.content.res.AssetFileDescriptor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private enum class OutputFailurePoint {
    WRITE,
    FLUSH,
    CLOSE,
}

@RunWith(RobolectricTestRunner::class)
class MediaCopyRepositoryTest {

    @Test
    fun copyMedia_whenCopyAndPublishSucceed_returnsPublishedUri() {
        runTest {
            val sourceBytes = byteArrayOf(1, 2, 3, 4)
            val outputStream = ByteArrayOutputStream()
            val pendingValues = slot<ContentValues>()
            val publishedValues = slot<ContentValues>()
            val contentResolver = mockk<ContentResolver>()
            stubInsertedImage(
                contentResolver = contentResolver,
                pendingValues = pendingValues,
            )
            every { contentResolver.openInputStream(sourceUri) } returns
                    ByteArrayInputStream(sourceBytes)
            every { contentResolver.openOutputStream(destinationUri) } returns outputStream
            every {
                contentResolver.update(
                    destinationUri,
                    capture(publishedValues),
                    null,
                    null,
                )
            } returns 1
            val copiedByteCounts = mutableListOf<Int>()
            val repository = mediaCopyRepository(contentResolver = contentResolver)

            val result = repository.copyMedia(
                sourceUri = sourceUri,
                destinationPath = DESTINATION_PATH,
            ) { bytesCopied ->
                copiedByteCounts += bytesCopied
            }

            assertEquals(destinationUri, result)
            assertEquals("original-café 日本語.jpg",
                pendingValues.captured.getAsString(MediaStore.MediaColumns.DISPLAY_NAME))
            assertArrayEquals(sourceBytes, outputStream.toByteArray())
            assertEquals(sourceBytes.size, copiedByteCounts.sum())
            assertEquals(
                1,
                pendingValues.captured.getAsInteger(MediaStore.MediaColumns.IS_PENDING),
            )
            assertEquals(
                "Pictures/MediaCopyTest",
                pendingValues.captured.getAsString(MediaStore.MediaColumns.RELATIVE_PATH),
            )
            assertEquals(
                0,
                publishedValues.captured.getAsInteger(MediaStore.MediaColumns.IS_PENDING),
            )
            verify(exactly = 0) {
                contentResolver.delete(destinationUri, null, null)
            }
        }
    }

    @Test
    fun copyMedia_whenInputStreamIsNull_deletesPendingDestination() {
        runTest {
            val contentResolver = mockk<ContentResolver>()
            stubInsertedImage(contentResolver = contentResolver)
            every { contentResolver.openInputStream(sourceUri) } returns null
            every { contentResolver.delete(destinationUri, null, null) } returns 1
            val repository = mediaCopyRepository(contentResolver = contentResolver)

            val result = repository.copyMedia(
                sourceUri = sourceUri,
                destinationPath = DESTINATION_PATH,
                onBytesCopied = {},
            )

            assertNull(result)
            verify(exactly = 1) {
                contentResolver.delete(destinationUri, null, null)
            }
            verify(exactly = 0) {
                contentResolver.update(destinationUri, any(), null, null)
            }
        }
    }

    @Test
    fun copyMedia_whenOutputStreamIsNull_deletesPendingDestination() {
        runTest {
            val contentResolver = mockk<ContentResolver>()
            stubInsertedImage(contentResolver = contentResolver)
            every { contentResolver.openInputStream(sourceUri) } returns
                    ByteArrayInputStream(byteArrayOf(1))
            every { contentResolver.openOutputStream(destinationUri) } returns null
            every { contentResolver.delete(destinationUri, null, null) } returns 1
            val repository = mediaCopyRepository(contentResolver = contentResolver)

            val result = repository.copyMedia(
                sourceUri = sourceUri,
                destinationPath = DESTINATION_PATH,
                onBytesCopied = {},
            )

            assertNull(result)
            verify(exactly = 1) {
                contentResolver.delete(destinationUri, null, null)
            }
            verify(exactly = 0) {
                contentResolver.update(destinationUri, any(), null, null)
            }
        }
    }

    @Test
    fun copyMedia_whenCopyThrows_deletesPendingDestination() {
        runTest {
            val contentResolver = mockk<ContentResolver>()
            stubInsertedImage(contentResolver = contentResolver)
            every { contentResolver.openInputStream(sourceUri) } returns failingInputStream()
            every { contentResolver.openOutputStream(destinationUri) } returns
                    ByteArrayOutputStream()
            every { contentResolver.delete(destinationUri, null, null) } returns 1
            val repository = mediaCopyRepository(contentResolver = contentResolver)

            val result = repository.copyMedia(
                sourceUri = sourceUri,
                destinationPath = DESTINATION_PATH,
                onBytesCopied = {},
            )

            assertNull(result)
            verify(exactly = 1) {
                contentResolver.delete(destinationUri, null, null)
            }
            verify(exactly = 0) {
                contentResolver.update(destinationUri, any(), null, null)
            }
        }
    }

    @Test
    fun copyMedia_whenOutputWriteThrows_deletesPendingDestination() {
        runTest {
            val contentResolver = mockk<ContentResolver>()
            stubInsertedImage(contentResolver = contentResolver)
            every { contentResolver.openInputStream(sourceUri) } returns
                    ByteArrayInputStream(byteArrayOf(1))
            every { contentResolver.openOutputStream(destinationUri) } returns
                    failingOutputStream(failurePoint = OutputFailurePoint.WRITE)
            every { contentResolver.delete(destinationUri, null, null) } returns 1
            val repository = mediaCopyRepository(contentResolver = contentResolver)

            val result = repository.copyMedia(
                sourceUri = sourceUri,
                destinationPath = DESTINATION_PATH,
                onBytesCopied = {},
            )

            assertNull(result)
            verifyFailedCopyCleanup(contentResolver = contentResolver)
        }
    }

    @Test
    fun copyMedia_whenOutputFlushThrows_deletesPendingDestination() {
        runTest {
            val contentResolver = mockk<ContentResolver>()
            stubInsertedImage(contentResolver = contentResolver)
            every { contentResolver.openInputStream(sourceUri) } returns
                    ByteArrayInputStream(byteArrayOf(1))
            every { contentResolver.openOutputStream(destinationUri) } returns
                    failingOutputStream(failurePoint = OutputFailurePoint.FLUSH)
            every { contentResolver.delete(destinationUri, null, null) } returns 1
            val repository = mediaCopyRepository(contentResolver = contentResolver)

            val result = repository.copyMedia(
                sourceUri = sourceUri,
                destinationPath = DESTINATION_PATH,
                onBytesCopied = {},
            )

            assertNull(result)
            verifyFailedCopyCleanup(contentResolver = contentResolver)
        }
    }

    @Test
    fun copyMedia_whenOutputCloseThrows_deletesPendingDestination() {
        runTest {
            val contentResolver = mockk<ContentResolver>()
            stubInsertedImage(contentResolver = contentResolver)
            every { contentResolver.openInputStream(sourceUri) } returns
                    ByteArrayInputStream(byteArrayOf(1))
            every { contentResolver.openOutputStream(destinationUri) } returns
                    failingOutputStream(failurePoint = OutputFailurePoint.CLOSE)
            every { contentResolver.delete(destinationUri, null, null) } returns 1
            val repository = mediaCopyRepository(contentResolver = contentResolver)

            val result = repository.copyMedia(
                sourceUri = sourceUri,
                destinationPath = DESTINATION_PATH,
                onBytesCopied = {},
            )

            assertNull(result)
            verifyFailedCopyCleanup(contentResolver = contentResolver)
        }
    }

    @Test
    fun copyMedia_whenPublishFails_deletesPendingDestination() {
        runTest {
            val contentResolver = mockk<ContentResolver>()
            stubInsertedImage(contentResolver = contentResolver)
            every { contentResolver.openInputStream(sourceUri) } returns
                    ByteArrayInputStream(byteArrayOf(1))
            every { contentResolver.openOutputStream(destinationUri) } returns
                    ByteArrayOutputStream()
            every {
                contentResolver.update(destinationUri, any(), null, null)
            } returns 0
            every { contentResolver.delete(destinationUri, null, null) } returns 1
            val repository = mediaCopyRepository(contentResolver = contentResolver)

            val result = repository.copyMedia(
                sourceUri = sourceUri,
                destinationPath = DESTINATION_PATH,
                onBytesCopied = {},
            )

            assertNull(result)
            verify(exactly = 1) {
                contentResolver.delete(destinationUri, null, null)
            }
        }
    }

    @Test
    fun copyMedia_whenCancelled_deletesPendingDestinationAndRethrowsCancellation() {
        runTest {
            val cancellationException = CancellationException("cancelled")
            val contentResolver = mockk<ContentResolver>()
            stubInsertedImage(contentResolver = contentResolver)
            every { contentResolver.openInputStream(sourceUri) } returns
                    ByteArrayInputStream(byteArrayOf(1))
            every { contentResolver.openOutputStream(destinationUri) } returns
                    ByteArrayOutputStream()
            every { contentResolver.delete(destinationUri, null, null) } returns 1
            val repository = mediaCopyRepository(contentResolver = contentResolver)

            val thrownException = try {
                repository.copyMedia(
                    sourceUri = sourceUri,
                    destinationPath = DESTINATION_PATH,
                ) {
                    throw cancellationException
                }
                null
            } catch (exception: CancellationException) {
                exception
            }

            assertSame(cancellationException, thrownException)
            verify(exactly = 1) {
                contentResolver.delete(destinationUri, null, null)
            }
            verify(exactly = 0) {
                contentResolver.update(destinationUri, any(), null, null)
            }
        }
    }

    @Test
    fun copyMedia_whenCleanupThrows_returnsFailure() {
        runTest {
            val contentResolver = mockk<ContentResolver>()
            stubInsertedImage(contentResolver = contentResolver)
            every { contentResolver.openInputStream(sourceUri) } returns null
            every {
                contentResolver.delete(destinationUri, null, null)
            } throws IllegalStateException("delete failed")
            val repository = mediaCopyRepository(contentResolver = contentResolver)

            val result = repository.copyMedia(
                sourceUri = sourceUri,
                destinationPath = DESTINATION_PATH,
                onBytesCopied = {},
            )

            assertNull(result)
        }
    }

    @Test
    fun copyMedia_whenInsertFails_returnsFailureWithoutOpeningStreams() {
        runTest {
            val contentResolver = mockk<ContentResolver>()
            every { contentResolver.getType(sourceUri) } returns "image/jpeg"
        every { contentResolver.query(sourceUri, any(), null, null, null) } answers {
            MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply {
                addRow(arrayOf("original-café 日本語.jpg"))
            }
        }
            every {
                contentResolver.insert(
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    any(),
                )
            } returns null
            val repository = mediaCopyRepository(contentResolver = contentResolver)

            val result = repository.copyMedia(
                sourceUri = sourceUri,
                destinationPath = DESTINATION_PATH,
                onBytesCopied = {},
            )

            assertNull(result)
            verify(exactly = 0) {
                contentResolver.openInputStream(any())
            }
            verify(exactly = 0) {
                contentResolver.delete(any(), null, null)
            }
        }
    }

    @Test
    fun copyMedia_whenCopyingVideoToSecondaryStorage_usesVideoCollection() {
        runTest {
            val secondaryDestinationUri =
                Uri.parse("content://media/71f8-2c0a/video/media/2")
            val contentResolver = mockk<ContentResolver>()
            every { contentResolver.getType(sourceUri) } returns "video/mp4"
            every { contentResolver.query(sourceUri, any(), null, null, null) } returns null
            every {
                contentResolver.insert(
                    MediaStore.Video.Media.getContentUri("71f8-2c0a"),
                    any(),
                )
            } returns secondaryDestinationUri
            every { contentResolver.openInputStream(sourceUri) } returns
                    ByteArrayInputStream(byteArrayOf(1))
            every { contentResolver.openOutputStream(secondaryDestinationUri) } returns
                    ByteArrayOutputStream()
            every {
                contentResolver.update(secondaryDestinationUri, any(), null, null)
            } returns 1
            val repository = mediaCopyRepository(contentResolver = contentResolver)

            val result = repository.copyMedia(
                sourceUri = sourceUri,
                destinationPath = "/storage/71F8-2C0A/Movies/Copied",
                onBytesCopied = {},
            )

            assertEquals(secondaryDestinationUri, result)
        }
    }

    @Test
    fun copyMedia_whenMimeTypeIsUnsupported_doesNotInsertDestination() {
        runTest {
            val contentResolver = mockk<ContentResolver>()
            every { contentResolver.getType(sourceUri) } returns "application/pdf"
            val repository = mediaCopyRepository(contentResolver = contentResolver)

            val result = repository.copyMedia(
                sourceUri = sourceUri,
                destinationPath = DESTINATION_PATH,
                onBytesCopied = {},
            )

            assertNull(result)
            verify(exactly = 0) {
                contentResolver.insert(any(), any())
            }
        }
    }

    @Test
    fun copyMedia_whenMimeTypeIsNull_doesNotInsertDestination() {
        runTest {
            val contentResolver = mockk<ContentResolver>()
            every { contentResolver.getType(sourceUri) } returns null
            val repository = mediaCopyRepository(contentResolver = contentResolver)

            val result = repository.copyMedia(
                sourceUri = sourceUri,
                destinationPath = DESTINATION_PATH,
                onBytesCopied = {},
            )

            assertNull(result)
            verify(exactly = 0) {
                contentResolver.insert(any(), any())
            }
        }
    }

    @Test
    fun getMediaSize_whenDescriptorHasLength_returnsLength() {
        runTest {
            val descriptor = mockk<AssetFileDescriptor>(relaxed = true)
            every { descriptor.length } returns 42L
            val contentResolver = mockk<ContentResolver>()
            every {
                contentResolver.openAssetFileDescriptor(sourceUri, "r")
            } returns descriptor
            val repository = mediaCopyRepository(contentResolver = contentResolver)

            val result = repository.getMediaSize(uri = sourceUri)

            assertEquals(42L, result)
        }
    }

    @Test
    fun notifyMediaChanged_notifiesExternalFilesCollection() {
        runTest {
            val contentResolver = mockk<ContentResolver>()
            every { contentResolver.notifyChange(any(), null) } returns Unit
            val repository = mediaCopyRepository(contentResolver = contentResolver)

            repository.notifyMediaChanged()

            verify(exactly = 1) {
                contentResolver.notifyChange(
                    MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
                    null,
                )
            }
        }
    }

    private fun mediaCopyRepository(contentResolver: ContentResolver): MediaCopyRepository {
        return MediaCopyRepositoryImpl(
            contentResolver = contentResolver,
            ioDispatcher = Dispatchers.Unconfined,
        )
    }

    private fun stubInsertedImage(
        contentResolver: ContentResolver,
        pendingValues: CapturingSlot<ContentValues>? = null,
    ) {
        every { contentResolver.getType(sourceUri) } returns "image/jpeg"
        every { contentResolver.query(sourceUri, any(), null, null, null) } answers {
            MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply {
                addRow(arrayOf("original-café 日本語.jpg"))
            }
        }
        every {
            contentResolver.insert(
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                if (pendingValues == null) any() else capture(pendingValues),
            )
        } returns destinationUri
    }

    private fun verifyFailedCopyCleanup(contentResolver: ContentResolver) {
        verify(exactly = 1) {
            contentResolver.delete(destinationUri, null, null)
        }
        verify(exactly = 0) {
            contentResolver.update(destinationUri, any(), null, null)
        }
    }

    private fun failingOutputStream(failurePoint: OutputFailurePoint): OutputStream {
        return object : ByteArrayOutputStream() {
            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                if (failurePoint == OutputFailurePoint.WRITE) {
                    throw IOException("write failed")
                }
                super.write(buffer, offset, length)
            }

            override fun flush() {
                if (failurePoint == OutputFailurePoint.FLUSH) {
                    throw IOException("flush failed")
                }
                super.flush()
            }

            override fun close() {
                if (failurePoint == OutputFailurePoint.CLOSE) {
                    throw IOException("close failed")
                }
                super.close()
            }
        }
    }

    private fun failingInputStream(): InputStream {
        return object : InputStream() {
            override fun read(): Int {
                throw IOException("copy failed")
            }
        }
    }

    companion object {
        private val sourceUri = Uri.parse("content://media/external/images/media/1")
        private val destinationUri = Uri.parse("content://media/external/images/media/2")
        private const val DESTINATION_PATH = "Pictures/MediaCopyTest"
    }
}
