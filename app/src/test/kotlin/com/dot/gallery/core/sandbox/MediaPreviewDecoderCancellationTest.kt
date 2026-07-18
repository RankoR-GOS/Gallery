package com.dot.gallery.core.sandbox

import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.os.ParcelFileDescriptor
import android.util.Size
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
internal class MediaPreviewDecoderCancellationTest {

    @Test
    fun decode_callerTimeoutCancelsDescriptorOpenPromptly() {
        runBlocking {
            val contentResolver = mockk<ContentResolver>()
            val openStarted = CompletableDeferred<CancellationSignal>()
            every {
                contentResolver.openFileDescriptor(TEST_URI, "r", any())
            } answers {
                val cancellationSignal = arg<CancellationSignal>(2)
                openStarted.complete(cancellationSignal)
                awaitCancellation(cancellationSignal = cancellationSignal)
                throw OperationCanceledException()
            }
            val decoder = MediaPreviewDecoder(
                connection = mockk(relaxed = true),
                contentResolver = contentResolver,
                imageDecoder = mockk(relaxed = true),
                ioDispatcher = Dispatchers.IO,
            )

            val decode = async {
                runCatching {
                    withTimeout(timeMillis = SHORT_TIMEOUT_MILLIS) {
                        decoder.decode(uri = TEST_URI, mimeType = "video/mp4", isVideo = true)
                    }
                }.exceptionOrNull()
            }
            val cancellationSignal = withTimeout(timeMillis = TEST_TIMEOUT_MILLIS) {
                openStarted.await()
            }
            val failure = withTimeout(timeMillis = TEST_TIMEOUT_MILLIS) { decode.await() }

            assertTrue(failure is TimeoutCancellationException)
            assertTrue(cancellationSignal.isCanceled)
            verify(exactly = 1) {
                contentResolver.openFileDescriptor(TEST_URI, "r", cancellationSignal)
            }
        }
    }

    @Test
    fun loadThumbnailCancellable_cancellationCancelsProviderSignal() {
        runBlocking {
            val contentResolver = mockk<ContentResolver>()
            val loadStarted = CompletableDeferred<CancellationSignal>()
            every {
                contentResolver.loadThumbnail(TEST_URI, TEST_SIZE, any())
            } answers {
                val cancellationSignal = arg<CancellationSignal>(2)
                loadStarted.complete(cancellationSignal)
                awaitCancellation(cancellationSignal = cancellationSignal)
                throw OperationCanceledException()
            }

            val load = async(Dispatchers.IO) {
                contentResolver.loadThumbnailCancellable(uri = TEST_URI, size = TEST_SIZE)
            }
            val cancellationSignal = withTimeout(timeMillis = TEST_TIMEOUT_MILLIS) {
                loadStarted.await()
            }

            load.cancel()
            withTimeout(timeMillis = TEST_TIMEOUT_MILLIS) { load.join() }

            assertTrue(cancellationSignal.isCanceled)
        }
    }

    @Test
    fun loadThumbnailCancellable_timeoutCancelsProviderSignal() {
        runBlocking {
            val contentResolver = mockk<ContentResolver>()
            val loadStarted = CompletableDeferred<CancellationSignal>()
            every {
                contentResolver.loadThumbnail(TEST_URI, TEST_SIZE, any())
            } answers {
                val cancellationSignal = arg<CancellationSignal>(2)
                loadStarted.complete(cancellationSignal)
                awaitCancellation(cancellationSignal = cancellationSignal)
                throw OperationCanceledException()
            }

            val load = async(Dispatchers.IO) {
                runCatching {
                    withTimeout(timeMillis = SHORT_TIMEOUT_MILLIS) {
                        contentResolver.loadThumbnailCancellable(
                            uri = TEST_URI,
                            size = TEST_SIZE,
                        )
                    }
                }.exceptionOrNull()
            }
            val cancellationSignal = withTimeout(timeMillis = TEST_TIMEOUT_MILLIS) {
                loadStarted.await()
            }
            val failure = withTimeout(timeMillis = TEST_TIMEOUT_MILLIS) { load.await() }

            assertTrue(failure is TimeoutCancellationException)
            assertTrue(cancellationSignal.isCanceled)
        }
    }

    @Test
    fun openFileDescriptorCancellable_lateDescriptorIsClosed() {
        runBlocking {
            val contentResolver = mockk<ContentResolver>()
            val openStarted = CompletableDeferred<CancellationSignal>()
            val allowResult = CountDownLatch(1)
            val descriptor = ParcelFileDescriptor.open(
                File("/dev/null"),
                ParcelFileDescriptor.MODE_READ_ONLY,
            )
            every {
                contentResolver.openFileDescriptor(TEST_URI, "r", any())
            } answers {
                openStarted.complete(arg(n = 2))
                check(allowResult.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
                descriptor
            }

            val open = async(Dispatchers.IO) {
                contentResolver.openFileDescriptorCancellable(uri = TEST_URI, mode = "r")
            }
            val cancellationSignal = withTimeout(timeMillis = TEST_TIMEOUT_MILLIS) {
                openStarted.await()
            }
            open.cancel()
            assertTrue(cancellationSignal.isCanceled)

            allowResult.countDown()
            withTimeout(timeMillis = TEST_TIMEOUT_MILLIS) { open.join() }

            assertFalse(descriptor.fileDescriptor.valid())
        }
    }

    @Test
    fun loadThumbnailCancellable_lateBitmapIsRecycled() {
        runBlocking {
            val contentResolver = mockk<ContentResolver>()
            val loadStarted = CompletableDeferred<CancellationSignal>()
            val allowResult = CountDownLatch(1)
            val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            every {
                contentResolver.loadThumbnail(TEST_URI, TEST_SIZE, any())
            } answers {
                loadStarted.complete(arg(n = 2))
                check(allowResult.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
                bitmap
            }

            val load = async(Dispatchers.IO) {
                contentResolver.loadThumbnailCancellable(uri = TEST_URI, size = TEST_SIZE)
            }
            val cancellationSignal = withTimeout(timeMillis = TEST_TIMEOUT_MILLIS) {
                loadStarted.await()
            }
            load.cancel()
            assertTrue(cancellationSignal.isCanceled)

            allowResult.countDown()
            withTimeout(timeMillis = TEST_TIMEOUT_MILLIS) { load.join() }

            assertTrue(bitmap.isRecycled)
        }
    }

    private fun awaitCancellation(cancellationSignal: CancellationSignal) {
        val deadlineNanoseconds = System.nanoTime() +
            TimeUnit.MILLISECONDS.toNanos(TEST_TIMEOUT_MILLIS)
        while (!cancellationSignal.isCanceled && System.nanoTime() < deadlineNanoseconds) {
            Thread.yield()
        }
        check(cancellationSignal.isCanceled)
    }

    private companion object {
        private const val SHORT_TIMEOUT_MILLIS = 100L
        private const val TEST_TIMEOUT_MILLIS = 5_000L
        private val TEST_SIZE = Size(224, 224)
        private val TEST_URI = Uri.parse("content://media/external/video/media/1")
    }
}
