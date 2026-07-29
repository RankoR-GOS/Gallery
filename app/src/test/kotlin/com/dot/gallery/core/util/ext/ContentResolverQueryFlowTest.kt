package com.dot.gallery.core.util.ext

import android.content.ContentResolver
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.OperationCanceledException
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
internal class ContentResolverQueryFlowTest {

    @Test
    fun queryFlow_coalescesBurstInvalidations() {
        runBlocking {
            val contentResolver = mockk<ContentResolver>(relaxed = true)
            val observerSlot = slot<ContentObserver>()
            val observerRegistered = CompletableDeferred<Unit>()
            val firstQueryCompleted = CompletableDeferred<Unit>()
            val firstCursorReceived = CompletableDeferred<Unit>()
            val queryCount = AtomicInteger()
            every {
                contentResolver.registerContentObserver(TEST_URI, true, capture(observerSlot))
            } answers {
                observerRegistered.complete(Unit)
            }
            every {
                contentResolver.query(TEST_URI, null, null, any<CancellationSignal>())
            } answers {
                if (queryCount.incrementAndGet() == 1) {
                    firstQueryCompleted.complete(Unit)
                }
                mockk<Cursor>(relaxed = true)
            }

            val result = async {
                contentResolver.queryFlow(uri = TEST_URI, queryArgs = null)
                    .onEach { firstCursorReceived.complete(Unit) }
                    .take(count = 2)
                    .toList()
            }
            withTimeout(timeMillis = TEST_TIMEOUT_MILLIS) {
                observerRegistered.await()
                firstQueryCompleted.await()
                firstCursorReceived.await()
            }

            repeat(times = 5) {
                observerSlot.captured.onChange(false)
            }

            delay(timeMillis = 500L)
            assertEquals(2, queryCount.get())
            val cursors = withTimeout(timeMillis = TEST_TIMEOUT_MILLIS) { result.await() }
            cursors.forEach { cursor -> cursor?.close() }
            verify(exactly = 1) { contentResolver.unregisterContentObserver(observerSlot.captured) }
        }
    }

    @Test
    fun queryFlow_cancelsStaleQueryBeforeDebouncedReplacement() {
        runBlocking {
            val contentResolver = mockk<ContentResolver>(relaxed = true)
            val observerSlot = slot<ContentObserver>()
            val observerRegistered = CompletableDeferred<Unit>()
            val firstQueryStarted = CompletableDeferred<CancellationSignal>()
            val queryCount = AtomicInteger()
            every {
                contentResolver.registerContentObserver(TEST_URI, true, capture(observerSlot))
            } answers {
                observerRegistered.complete(Unit)
            }
            every {
                contentResolver.query(TEST_URI, null, null, any<CancellationSignal>())
            } answers {
                val cancellationSignal = arg<CancellationSignal>(3)
                if (queryCount.incrementAndGet() == 1) {
                    firstQueryStarted.complete(cancellationSignal)
                    awaitCancellation(cancellationSignal = cancellationSignal)
                    throw OperationCanceledException()
                }
                mockk<Cursor>(relaxed = true)
            }

            val result = async {
                contentResolver.queryFlow(uri = TEST_URI, queryArgs = null)
                    .take(count = 1)
                    .toList()
            }
            val staleCancellationSignal = withTimeout(timeMillis = TEST_TIMEOUT_MILLIS) {
                observerRegistered.await()
                firstQueryStarted.await()
            }

            observerSlot.captured.onChange(false)

            val cursors = withTimeout(timeMillis = TEST_TIMEOUT_MILLIS) { result.await() }
            cursors.forEach { cursor -> cursor?.close() }
            assertTrue(staleCancellationSignal.isCanceled)
            assertEquals(2, queryCount.get())
            verify(exactly = 1) { contentResolver.unregisterContentObserver(observerSlot.captured) }
        }
    }

    @Test
    fun queryFlow_collectorCancellationCancelsQueryAndUnregistersObserver() {
        runBlocking {
            val contentResolver = mockk<ContentResolver>(relaxed = true)
            val observerSlot = slot<ContentObserver>()
            val queryStarted = CompletableDeferred<CancellationSignal>()
            every {
                contentResolver.registerContentObserver(TEST_URI, true, capture(observerSlot))
            } returns Unit
            every {
                contentResolver.query(TEST_URI, null, null, any<CancellationSignal>())
            } answers {
                val cancellationSignal = arg<CancellationSignal>(3)
                queryStarted.complete(cancellationSignal)
                awaitCancellation(cancellationSignal = cancellationSignal)
                throw OperationCanceledException()
            }

            val collection = async {
                contentResolver.queryFlow(uri = TEST_URI, queryArgs = null).collect {}
            }
            val cancellationSignal = withTimeout(timeMillis = TEST_TIMEOUT_MILLIS) {
                queryStarted.await()
            }

            collection.cancel()
            withTimeout(timeMillis = TEST_TIMEOUT_MILLIS) { collection.join() }

            assertTrue(cancellationSignal.isCanceled)
            verify(exactly = 1) {
                contentResolver.unregisterContentObserver(observerSlot.captured)
            }
        }
    }

    @Test
    fun queryFlow_lateCursorIsClosedAfterCollectorCancellation() {
        runBlocking {
            val contentResolver = mockk<ContentResolver>(relaxed = true)
            val observerSlot = slot<ContentObserver>()
            val queryStarted = CompletableDeferred<CancellationSignal>()
            val allowResult = CountDownLatch(1)
            val cursor = mockk<Cursor>(relaxed = true)
            every {
                contentResolver.registerContentObserver(TEST_URI, true, capture(observerSlot))
            } returns Unit
            every {
                contentResolver.query(TEST_URI, null, null, any<CancellationSignal>())
            } answers {
                queryStarted.complete(arg(n = 3))
                check(allowResult.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
                cursor
            }

            val collection = async {
                contentResolver.queryFlow(uri = TEST_URI, queryArgs = null).collect {}
            }
            val cancellationSignal = withTimeout(timeMillis = TEST_TIMEOUT_MILLIS) {
                queryStarted.await()
            }
            collection.cancel()
            assertTrue(cancellationSignal.isCanceled)

            allowResult.countDown()
            withTimeout(timeMillis = TEST_TIMEOUT_MILLIS) { collection.join() }

            verify(exactly = 1) { cursor.close() }
            verify(exactly = 1) {
                contentResolver.unregisterContentObserver(observerSlot.captured)
            }
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
        private const val TEST_TIMEOUT_MILLIS = 5_000L
        private val TEST_URI: Uri = Uri.parse("content://media/external/images/media")
    }
}
