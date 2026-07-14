package com.dot.gallery.core.workers

import androidx.core.net.toUri
import androidx.work.OneTimeWorkRequest
import androidx.work.Operation
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.google.common.util.concurrent.SettableFuture
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
internal class MediaCopySchedulerTest {

    @Test
    fun prepareBatch_doesNotEnqueueAndReturnsChunkedBatch() {
        val workManager = mockk<WorkManager>(relaxed = true)
        val scheduler = MediaCopySchedulerImpl(workManager = workManager)

        val batch = scheduler.prepareBatch(requests = copyRequests(count = 33))

        assertTrue(batch.tag.startsWith("MediaCopyBatch_"))
        assertEquals(2, batch.workRequestCount)
        assertEquals(33, batch.itemCount)
        verify(exactly = 0) { workManager.enqueue(any<List<OneTimeWorkRequest>>()) }
    }

    @Test
    fun enqueue_waitsForOperationAndUsesPreparedBatchTag() {
        runTest {
            val operationResult = SettableFuture.create<Operation.State.SUCCESS>()
            val workRequests = slot<List<OneTimeWorkRequest>>()
            val workManager = mockk<WorkManager>()
            every { workManager.enqueue(capture(workRequests)) } returns operation(
                result = operationResult,
            )
            val scheduler = MediaCopySchedulerImpl(workManager = workManager)
            val requests = copyRequests(count = 33)
            val batch = scheduler.prepareBatch(requests = requests)

            val enqueueResult = async {
                scheduler.enqueue(
                    batch = batch,
                    requests = requests,
                )
            }
            runCurrent()

            assertFalse(enqueueResult.isCompleted)
            operationResult.set(mockk())
            runCurrent()

            enqueueResult.await()
            assertEquals(2, workRequests.captured.size)
            assertTrue(workRequests.captured.all { workRequest -> batch.tag in workRequest.tags })
        }
    }

    @Test
    fun enqueue_whenOperationFails_completesWithTrackablePreparedBatch() {
        runTest {
            val operationResult = SettableFuture.create<Operation.State.SUCCESS>()
            val workManager = mockk<WorkManager>()
            every { workManager.enqueue(any<List<OneTimeWorkRequest>>()) } returns operation(
                result = operationResult,
            )
            val scheduler = MediaCopySchedulerImpl(workManager = workManager)
            val requests = copyRequests(count = 1)
            val batch = scheduler.prepareBatch(requests = requests)
            operationResult.setException(IllegalStateException("enqueue failed"))

            scheduler.enqueue(
                batch = batch,
                requests = requests,
            )

            assertEquals(1, batch.workRequestCount)
            assertEquals(1, batch.itemCount)
        }
    }

    @Test
    fun enqueue_whenOperationFailsAndWorkFinished_observesActualStatus() {
        runTest {
            val operationResult = SettableFuture.create<Operation.State.SUCCESS>()
            val workManager = mockk<WorkManager>()
            every { workManager.enqueue(any<List<OneTimeWorkRequest>>()) } returns operation(
                result = operationResult,
            )
            every { workManager.getWorkInfosByTagFlow(any()) } returns flow {
                emit(
                    listOf(
                        workInfo(
                            state = WorkInfo.State.SUCCEEDED,
                            successfulCount = 1,
                        ),
                    ),
                )
            }
            val scheduler = MediaCopySchedulerImpl(workManager = workManager)
            val requests = copyRequests(count = 1)
            val batch = scheduler.prepareBatch(requests = requests)
            operationResult.setException(IllegalStateException("enqueue failed"))

            scheduler.enqueue(
                batch = batch,
                requests = requests,
            )
            val status = scheduler.observe(batch = batch).first()

            assertEquals(
                MediaCopyBatchStatus.Finished(
                    copiedCount = 1,
                    failedCount = 0,
                    successful = true,
                ),
                status,
            )
        }
    }

    @Test
    fun enqueue_whenOperationIsCancelled_propagatesCancellation() {
        runTest {
            val operationResult = SettableFuture.create<Operation.State.SUCCESS>()
            val workManager = mockk<WorkManager>()
            every { workManager.enqueue(any<List<OneTimeWorkRequest>>()) } returns operation(
                result = operationResult,
            )
            val scheduler = MediaCopySchedulerImpl(workManager = workManager)
            val requests = copyRequests(count = 1)
            val batch = scheduler.prepareBatch(requests = requests)
            operationResult.setException(CancellationException("cancelled"))

            val exception = runCatching {
                scheduler.enqueue(
                    batch = batch,
                    requests = requests,
                )
            }.exceptionOrNull()

            assertEquals(CancellationException::class.java, exception?.javaClass)
        }
    }

    @Test
    fun enqueue_withMismatchedItemCount_rejectsBatch() {
        runTest {
            val scheduler = MediaCopySchedulerImpl(workManager = mockk(relaxed = true))
            val requests = copyRequests(count = 1)

            val exception = runCatching {
                scheduler.enqueue(
                    batch = batch(itemCount = 2),
                    requests = requests,
                )
            }.exceptionOrNull()

            assertEquals(IllegalArgumentException::class.java, exception?.javaClass)
        }
    }

    @Test
    fun enqueue_withMismatchedWorkRequestCount_rejectsBatch() {
        runTest {
            val scheduler = MediaCopySchedulerImpl(workManager = mockk(relaxed = true))
            val requests = copyRequests(count = 1)

            val exception = runCatching {
                scheduler.enqueue(
                    batch = batch(workRequestCount = 2),
                    requests = requests,
                )
            }.exceptionOrNull()

            assertEquals(IllegalArgumentException::class.java, exception?.javaClass)
        }
    }

    @Test
    fun toStatus_withMissingWorkInfo_isUnavailable() {
        val status = toStatus(
            batch = batch(workRequestCount = 2),
            workInfos = listOf(workInfo(state = WorkInfo.State.RUNNING)),
        )

        assertEquals(MediaCopyBatchStatus.Unavailable, status)
    }

    @Test
    fun toStatus_withEmptyRestoredResult_isUnavailable() {
        val status = toStatus(
            batch = batch(workRequestCount = 1),
            workInfos = emptyList(),
        )

        assertEquals(MediaCopyBatchStatus.Unavailable, status)
    }

    @Test
    fun toStatus_withExcessWorkInfo_isUnavailable() {
        val status = toStatus(
            batch = batch(workRequestCount = 1),
            workInfos = listOf(
                workInfo(state = WorkInfo.State.RUNNING),
                workInfo(state = WorkInfo.State.RUNNING),
            ),
        )

        assertEquals(MediaCopyBatchStatus.Unavailable, status)
    }

    @Test
    fun toStatus_withActiveWork_calculatesProgress() {
        val status = toStatus(
            batch = batch(workRequestCount = 2),
            workInfos = listOf(
                workInfo(state = WorkInfo.State.SUCCEEDED),
                workInfo(state = WorkInfo.State.RUNNING, progress = 50),
            ),
        )

        assertEquals(MediaCopyBatchStatus.Copying(progress = 0.75f), status)
    }

    @Test
    fun toStatus_withSuccessfulWork_reportsSuccess() {
        val status = toStatus(
            batch = batch(workRequestCount = 2, itemCount = 3),
            workInfos = listOf(
                workInfo(state = WorkInfo.State.SUCCEEDED, successfulCount = 2),
                workInfo(state = WorkInfo.State.SUCCEEDED, successfulCount = 1),
            ),
        )

        assertEquals(
            MediaCopyBatchStatus.Finished(
                copiedCount = 3,
                failedCount = 0,
                successful = true,
            ),
            status,
        )
    }

    @Test
    fun toStatus_withMixedResult_reportsCounts() {
        val status = toStatus(
            batch = batch(workRequestCount = 2, itemCount = 4),
            workInfos = listOf(
                workInfo(state = WorkInfo.State.SUCCEEDED, successfulCount = 2),
                workInfo(
                    state = WorkInfo.State.FAILED,
                    successfulCount = 1,
                    failedCount = 1,
                ),
            ),
        )

        assertEquals(
            MediaCopyBatchStatus.Finished(
                copiedCount = 3,
                failedCount = 1,
                successful = false,
            ),
            status,
        )
    }

    @Test
    fun observe_whenWorkInfoQueryFails_emitsUnavailable() {
        runTest {
            val workManager = mockk<WorkManager>()
            every { workManager.getWorkInfosByTagFlow(BATCH_TAG) } returns flow {
                throw IllegalStateException("query failed")
            }
            val scheduler = MediaCopySchedulerImpl(workManager = workManager)

            val status = scheduler.observe(batch = batch()).first()

            assertEquals(MediaCopyBatchStatus.Unavailable, status)
        }
    }

    @Test
    fun observe_whenCollectionIsCancelled_propagatesCancellation() {
        runTest {
            val workManager = mockk<WorkManager>()
            every { workManager.getWorkInfosByTagFlow(BATCH_TAG) } returns flow {
                throw CancellationException("cancelled")
            }
            val scheduler = MediaCopySchedulerImpl(workManager = workManager)

            val exception = runCatching {
                scheduler.observe(batch = batch()).first()
            }.exceptionOrNull()

            assertEquals(CancellationException::class.java, exception?.javaClass)
        }
    }

    private fun operation(
        result: SettableFuture<Operation.State.SUCCESS>,
    ): Operation {
        return mockk {
            every { getResult() } returns result
        }
    }

    private fun copyRequests(count: Int): List<MediaCopyRequest> {
        return List(count) { index ->
            MediaCopyRequest(
                sourceUri = "content://media/$index".toUri(),
                destinationPath = "Pictures/Test",
            )
        }
    }

    private fun toStatus(
        batch: MediaCopyBatch,
        workInfos: List<WorkInfo>,
    ): MediaCopyBatchStatus {
        val scheduler = MediaCopySchedulerImpl(workManager = mockk(relaxed = true))
        return scheduler.toStatus(
            batch = batch,
            workInfos = workInfos,
        )
    }

    private fun batch(
        workRequestCount: Int = 1,
        itemCount: Int = 1,
    ): MediaCopyBatch {
        return MediaCopyBatch(
            tag = BATCH_TAG,
            workRequestCount = workRequestCount,
            itemCount = itemCount,
        )
    }

    private fun workInfo(
        state: WorkInfo.State,
        successfulCount: Int = 0,
        failedCount: Int = 0,
        progress: Int = 0,
    ): WorkInfo {
        return WorkInfo(
            id = UUID.randomUUID(),
            state = state,
            tags = setOf(BATCH_TAG),
            outputData = workDataOf(
                MediaCopyWorker.MEDIA_COPY_SUCCESSFUL_COUNT_KEY to successfulCount,
                MediaCopyWorker.MEDIA_COPY_FAILED_COUNT_KEY to failedCount,
            ),
            progress = workDataOf(MediaCopyWorker.MEDIA_COPY_PROGRESS_KEY to progress),
        )
    }

    companion object {
        private const val BATCH_TAG = "MediaCopyBatch_test"
    }
}
