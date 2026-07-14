package com.dot.gallery.core.workers

import android.content.Context
import android.net.Uri
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.dot.gallery.feature_node.data.repository.MediaCopyRepository
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
internal class MediaCopyWorkerTest {

    @Test
    fun doWork_whenEveryCopySucceeds_returnsCountsAndNotifiesMediaStore() {
        runTest {
            val repository = FakeMediaCopyRepository(
                copyResults = listOf(destinationUri, destinationUri),
            )
            val worker = mediaCopyWorker(
                repository = repository,
                uriCount = 2,
            )

            val result = worker.doWork()
            val expectedOutput = mediaCopyOutput(successfulCount = 2, failedCount = 0)

            assertEquals(ListenableWorker.Result.success(expectedOutput), result)
            assertEquals(1, repository.notificationCount.get())
        }
    }

    @Test
    fun doWork_whenOneCopyFails_returnsFailureCountsWithoutRetry() {
        runTest {
            val repository = FakeMediaCopyRepository(
                copyResults = listOf(destinationUri, null),
            )
            val worker = mediaCopyWorker(
                repository = repository,
                uriCount = 2,
            )

            val result = worker.doWork()
            val expectedOutput = mediaCopyOutput(successfulCount = 1, failedCount = 1)

            assertEquals(ListenableWorker.Result.failure(expectedOutput), result)
            assertEquals(1, repository.notificationCount.get())
        }
    }

    @Test
    fun doWork_whenEveryCopyFails_returnsFailureWithoutNotification() {
        runTest {
            val repository = FakeMediaCopyRepository(copyResults = listOf(null, null))
            val worker = mediaCopyWorker(
                repository = repository,
                uriCount = 2,
            )

            val result = worker.doWork()
            val expectedOutput = mediaCopyOutput(successfulCount = 0, failedCount = 2)

            assertEquals(ListenableWorker.Result.failure(expectedOutput), result)
            assertEquals(0, repository.notificationCount.get())
        }
    }

    @Test
    fun doWork_whenInputArraysHaveDifferentSizes_returnsTerminalFailure() {
        runTest {
            val repository = FakeMediaCopyRepository(copyResults = emptyList())
            val worker = mediaCopyWorker(
                repository = repository,
                uriCount = 2,
                pathCount = 1,
            )

            val result = worker.doWork()
            val expectedOutput = mediaCopyOutput(successfulCount = 0, failedCount = 2)

            assertEquals(ListenableWorker.Result.failure(expectedOutput), result)
            assertEquals(0, repository.copyCount.get())
        }
    }

    @Test
    fun doWork_whenRepositoryIsCancelled_rethrowsCancellation() {
        runTest {
            val cancellationException = CancellationException("cancelled")
            val repository = FakeMediaCopyRepository(
                copyResults = emptyList(),
                copyException = cancellationException,
            )
            val worker = mediaCopyWorker(
                repository = repository,
                uriCount = 1,
            )

            val thrownException = try {
                worker.doWork()
                null
            } catch (exception: CancellationException) {
                exception
            }

            assertEquals(cancellationException.message, thrownException?.message)
        }
    }

    private fun mediaCopyWorker(
        repository: MediaCopyRepository,
        uriCount: Int,
        pathCount: Int = uriCount,
    ): MediaCopyWorker {
        val context = RuntimeEnvironment.getApplication().applicationContext
        val inputData = workDataOf(
            "uris" to Array(uriCount) { index -> "content://media/source/$index" },
            "paths" to Array(pathCount) { "Pictures/Test" },
        )
        val workerFactory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ): ListenableWorker {
                return MediaCopyWorker(
                    mediaCopyRepository = repository,
                    appContext = appContext,
                    params = workerParameters,
                )
            }
        }

        return TestListenableWorkerBuilder<MediaCopyWorker>(
            context = context,
            inputData = inputData,
        ).setWorkerFactory(workerFactory)
            .build()
    }

    private fun mediaCopyOutput(successfulCount: Int, failedCount: Int): Data {
        return workDataOf(
            MediaCopyWorker.MEDIA_COPY_SUCCESSFUL_COUNT_KEY to successfulCount,
            MediaCopyWorker.MEDIA_COPY_FAILED_COUNT_KEY to failedCount,
        )
    }

    private class FakeMediaCopyRepository(
        copyResults: List<Uri?>,
        private val copyException: CancellationException? = null,
    ) : MediaCopyRepository {

        private val remainingResults = ArrayDeque(copyResults)

        val copyCount = AtomicInteger(0)
        val notificationCount = AtomicInteger(0)

        override suspend fun getMediaSize(uri: Uri): Long {
            return 1L
        }

        override suspend fun copyMedia(
            sourceUri: Uri,
            destinationPath: String,
            onBytesCopied: suspend (Int) -> Unit,
        ): Uri? {
            copyCount.incrementAndGet()
            copyException?.let { exception ->
                throw exception
            }
            onBytesCopied(1)
            return remainingResults.removeFirst()
        }

        override suspend fun notifyMediaChanged() {
            notificationCount.incrementAndGet()
        }
    }

    companion object {
        private val destinationUri = Uri.parse("content://media/destination/1")
    }
}
