package com.dot.gallery.feature_node.presentation.exif

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import com.dot.gallery.core.workers.MediaCopyBatch
import com.dot.gallery.core.workers.MediaCopyBatchStatus
import com.dot.gallery.core.workers.MediaCopyRequest
import com.dot.gallery.core.workers.MediaCopyScheduler
import com.dot.gallery.feature_node.data.model.Media
import com.dot.gallery.testutil.MainDispatcherRule
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
internal class CopyMediaViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun noRestoredBatch_startsInSelectingState() {
        val viewModel = CopyMediaViewModel(
            mediaCopyScheduler = FakeMediaCopyScheduler(),
            savedStateHandle = SavedStateHandle(),
        )

        assertEquals(CopyMediaUiState.Selecting, viewModel.uiState.value)
    }

    @Test
    fun restoredBatch_withRunningWork_exposesProgress() {
        runTest(context = mainDispatcherRule.testDispatcher) {
            val scheduler = FakeMediaCopyScheduler(
                statuses = MutableStateFlow(MediaCopyBatchStatus.Copying(progress = 0.5f)),
            )
            val viewModel = restoredViewModel(scheduler = scheduler)

            runCurrent()

            assertEquals(CopyMediaUiState.Copying(progress = 0.5f), viewModel.uiState.value)
        }
    }

    @Test
    fun restoredBatch_withSuccessfulResult_exposesSuccess() {
        runTest(context = mainDispatcherRule.testDispatcher) {
            val scheduler = FakeMediaCopyScheduler(
                statuses = MutableStateFlow(
                    MediaCopyBatchStatus.Finished(
                        copiedCount = 3,
                        failedCount = 0,
                        successful = true,
                    ),
                ),
            )
            val viewModel = restoredViewModel(scheduler = scheduler)

            advanceUntilIdle()

            assertEquals(CopyMediaUiState.Succeeded, viewModel.uiState.value)
        }
    }

    @Test
    fun restoredBatch_withMixedResult_exposesCopiedAndFailedCounts() {
        runTest(context = mainDispatcherRule.testDispatcher) {
            val scheduler = FakeMediaCopyScheduler(
                statuses = MutableStateFlow(
                    MediaCopyBatchStatus.Finished(
                        copiedCount = 2,
                        failedCount = 1,
                        successful = false,
                    ),
                ),
            )
            val viewModel = restoredViewModel(scheduler = scheduler)

            advanceUntilIdle()

            assertEquals(
                CopyMediaUiState.Failed(copiedCount = 2, failedCount = 1),
                viewModel.uiState.value,
            )
        }
    }

    @Test
    fun restoredBatch_withUnavailableStatus_exposesDismissibleUnavailableState() {
        runTest(context = mainDispatcherRule.testDispatcher) {
            val scheduler = FakeMediaCopyScheduler(
                statuses = MutableStateFlow(MediaCopyBatchStatus.Unavailable),
            )
            val viewModel = restoredViewModel(scheduler = scheduler)

            advanceUntilIdle()

            assertEquals(CopyMediaUiState.StatusUnavailable, viewModel.uiState.value)
        }
    }

    @Test
    fun restoredBatch_whenObservationFails_exposesDismissibleUnavailableState() {
        runTest(context = mainDispatcherRule.testDispatcher) {
            val scheduler = FakeMediaCopyScheduler(
                statuses = flow { throw IllegalStateException("query failed") },
            )
            val viewModel = restoredViewModel(scheduler = scheduler)

            advanceUntilIdle()

            assertEquals(CopyMediaUiState.StatusUnavailable, viewModel.uiState.value)
        }
    }

    @Test
    fun enqueueCopy_savesBatchBeforeEnqueueCompletes() {
        runTest(context = mainDispatcherRule.testDispatcher) {
            val enqueueGate = CompletableDeferred<Unit>()
            val scheduler = FakeMediaCopyScheduler(
                enqueueGate = enqueueGate,
                statuses = MutableStateFlow(MediaCopyBatchStatus.Copying(progress = 0f)),
            )
            val savedStateHandle = SavedStateHandle()
            val viewModel = CopyMediaViewModel(
                mediaCopyScheduler = scheduler,
                savedStateHandle = savedStateHandle,
            )

            viewModel.enqueueCopy(media() to DESTINATION_PATH)
            runCurrent()

            assertEquals(CopyMediaUiState.Copying(progress = 0f), viewModel.uiState.value)
            assertEquals(BATCH_TAG, savedStateHandle.get<String>(BATCH_TAG_KEY))
            assertEquals(1, savedStateHandle.get<Int>(BATCH_WORK_COUNT_KEY))
            assertEquals(3, savedStateHandle.get<Int>(BATCH_ITEM_COUNT_KEY))

            enqueueGate.complete(Unit)
            runCurrent()

            assertEquals(1, scheduler.prepareCount)
            assertEquals(1, scheduler.enqueueCount)
        }
    }

    @Test
    fun enqueueCopy_whenSchedulerFails_exposesUnavailableWithoutDiscardingBatch() {
        runTest(context = mainDispatcherRule.testDispatcher) {
            val scheduler = FakeMediaCopyScheduler(
                enqueueFailure = IllegalStateException("enqueue failed"),
            )
            val savedStateHandle = SavedStateHandle()
            val viewModel = CopyMediaViewModel(
                mediaCopyScheduler = scheduler,
                savedStateHandle = savedStateHandle,
            )

            viewModel.enqueueCopy(
                media(id = 1L) to DESTINATION_PATH,
                media(id = 2L) to DESTINATION_PATH,
            )
            advanceUntilIdle()

            assertEquals(
                CopyMediaUiState.StatusUnavailable,
                viewModel.uiState.value,
            )
            assertEquals(BATCH_TAG, savedStateHandle.get<String>(BATCH_TAG_KEY))
        }
    }

    @Test
    fun enqueueCopy_whenWorkFinished_exposesActualCounts() {
        runTest(context = mainDispatcherRule.testDispatcher) {
            val scheduler = FakeMediaCopyScheduler(
                statuses = MutableStateFlow(
                    MediaCopyBatchStatus.Finished(
                        copiedCount = 2,
                        failedCount = 1,
                        successful = false,
                    ),
                ),
            )
            val savedStateHandle = SavedStateHandle()
            val viewModel = CopyMediaViewModel(
                mediaCopyScheduler = scheduler,
                savedStateHandle = savedStateHandle,
            )

            viewModel.enqueueCopy(
                media(id = 1L) to DESTINATION_PATH,
                media(id = 2L) to DESTINATION_PATH,
                media(id = 3L) to DESTINATION_PATH,
            )
            advanceUntilIdle()

            assertEquals(
                CopyMediaUiState.Failed(copiedCount = 2, failedCount = 1),
                viewModel.uiState.value,
            )
        }
    }

    @Test
    fun enqueueCopy_whenWorkIsActive_observesUntilActualTerminalResult() {
        runTest(context = mainDispatcherRule.testDispatcher) {
            val statuses = MutableStateFlow<MediaCopyBatchStatus>(
                MediaCopyBatchStatus.Copying(progress = 0.5f),
            )
            val scheduler = FakeMediaCopyScheduler(
                statuses = statuses,
            )
            val viewModel = CopyMediaViewModel(
                mediaCopyScheduler = scheduler,
                savedStateHandle = SavedStateHandle(),
            )

            viewModel.enqueueCopy(media() to DESTINATION_PATH)
            runCurrent()

            assertEquals(CopyMediaUiState.Copying(progress = 0.5f), viewModel.uiState.value)

            viewModel.enqueueCopy(media(id = 2L) to DESTINATION_PATH)

            assertEquals(1, scheduler.enqueueCount)

            statuses.value = MediaCopyBatchStatus.Finished(
                copiedCount = 1,
                failedCount = 0,
                successful = true,
            )
            advanceUntilIdle()

            assertEquals(CopyMediaUiState.Succeeded, viewModel.uiState.value)
        }
    }

    @Test
    fun enqueueCopy_whenWorkInfoIsMissing_exposesUnavailable() {
        runTest(context = mainDispatcherRule.testDispatcher) {
            val scheduler = FakeMediaCopyScheduler(
                statuses = MutableStateFlow(MediaCopyBatchStatus.Unavailable),
            )
            val viewModel = CopyMediaViewModel(
                mediaCopyScheduler = scheduler,
                savedStateHandle = SavedStateHandle(),
            )

            viewModel.enqueueCopy(media() to DESTINATION_PATH)
            advanceUntilIdle()

            assertEquals(CopyMediaUiState.StatusUnavailable, viewModel.uiState.value)
        }
    }

    @Test
    fun processRecreation_whileEnqueueIsPending_restoresActiveBatchAndBlocksSecondEnqueue() {
        runTest(context = mainDispatcherRule.testDispatcher) {
            val enqueueGate = CompletableDeferred<Unit>()
            val scheduler = FakeMediaCopyScheduler(
                enqueueGate = enqueueGate,
                statuses = MutableStateFlow(MediaCopyBatchStatus.Copying(progress = 0.5f)),
            )
            val savedStateHandle = SavedStateHandle()
            val originalViewModel = CopyMediaViewModel(
                mediaCopyScheduler = scheduler,
                savedStateHandle = savedStateHandle,
            )

            originalViewModel.enqueueCopy(media() to DESTINATION_PATH)
            runCurrent()

            val restoredViewModel = CopyMediaViewModel(
                mediaCopyScheduler = scheduler,
                savedStateHandle = snapshotSavedStateHandle(source = savedStateHandle),
            )
            runCurrent()

            assertEquals(
                CopyMediaUiState.Copying(progress = 0.5f),
                restoredViewModel.uiState.value,
            )

            restoredViewModel.enqueueCopy(media(id = 2L) to DESTINATION_PATH)

            assertEquals(1, scheduler.enqueueCount)

            enqueueGate.complete(Unit)
            runCurrent()
        }
    }

    @Test
    fun enqueueCopy_whileEnqueueIsPending_doesNotEnqueueAgain() {
        runTest(context = mainDispatcherRule.testDispatcher) {
            val enqueueGate = CompletableDeferred<Unit>()
            val scheduler = FakeMediaCopyScheduler(enqueueGate = enqueueGate)
            val viewModel = CopyMediaViewModel(
                mediaCopyScheduler = scheduler,
                savedStateHandle = SavedStateHandle(),
            )

            viewModel.enqueueCopy(media(id = 1L) to DESTINATION_PATH)
            viewModel.enqueueCopy(media(id = 2L) to DESTINATION_PATH)
            runCurrent()

            assertEquals(1, scheduler.enqueueCount)

            enqueueGate.complete(Unit)
            runCurrent()
        }
    }

    @Test
    fun resultHandled_clearsRestoredBatchAndReturnsToSelection() {
        runTest(context = mainDispatcherRule.testDispatcher) {
            val savedStateHandle = restoredSavedStateHandle(
                legacyEnqueueConfirmed = true,
            )
            val scheduler = FakeMediaCopyScheduler(
                statuses = MutableStateFlow(MediaCopyBatchStatus.Unavailable),
            )
            val viewModel = CopyMediaViewModel(
                mediaCopyScheduler = scheduler,
                savedStateHandle = savedStateHandle,
            )
            advanceUntilIdle()

            viewModel.onResultHandled()

            assertEquals(CopyMediaUiState.Selecting, viewModel.uiState.value)
            assertEquals(null, savedStateHandle.get<String>(BATCH_TAG_KEY))
            assertEquals(null, savedStateHandle.get<Boolean>(LEGACY_BATCH_ENQUEUE_CONFIRMED_KEY))
        }
    }

    private fun restoredViewModel(scheduler: MediaCopyScheduler): CopyMediaViewModel {
        return CopyMediaViewModel(
            mediaCopyScheduler = scheduler,
            savedStateHandle = restoredSavedStateHandle(),
        )
    }

    private fun restoredSavedStateHandle(
        legacyEnqueueConfirmed: Boolean? = null,
    ): SavedStateHandle {
        val savedState = mutableMapOf<String, Any>(
            BATCH_TAG_KEY to BATCH_TAG,
            BATCH_WORK_COUNT_KEY to 1,
            BATCH_ITEM_COUNT_KEY to 3,
        )
        legacyEnqueueConfirmed?.let { confirmed ->
            savedState[LEGACY_BATCH_ENQUEUE_CONFIRMED_KEY] = confirmed
        }
        return SavedStateHandle(savedState)
    }

    private fun snapshotSavedStateHandle(source: SavedStateHandle): SavedStateHandle {
        return SavedStateHandle(
            mapOf(
                BATCH_TAG_KEY to requireNotNull(source.get<String>(BATCH_TAG_KEY)),
                BATCH_WORK_COUNT_KEY to requireNotNull(source.get<Int>(BATCH_WORK_COUNT_KEY)),
                BATCH_ITEM_COUNT_KEY to requireNotNull(source.get<Int>(BATCH_ITEM_COUNT_KEY)),
            ),
        )
    }

    private fun media(id: Long = 1L): Media.UriMedia {
        return mockk<Media.UriMedia>().also { media ->
            every { media.uri } returns mockk<Uri>(name = "media-$id")
        }
    }

    private class FakeMediaCopyScheduler(
        private val enqueueGate: CompletableDeferred<Unit>? = null,
        private val enqueueFailure: Exception? = null,
        private val statuses: Flow<MediaCopyBatchStatus> = MutableStateFlow(
            MediaCopyBatchStatus.Copying(progress = 0f),
        ),
    ) : MediaCopyScheduler {

        var prepareCount = 0
            private set

        var enqueueCount = 0
            private set

        override fun prepareBatch(requests: List<MediaCopyRequest>): MediaCopyBatch {
            prepareCount++
            return BATCH
        }

        override suspend fun enqueue(
            batch: MediaCopyBatch,
            requests: List<MediaCopyRequest>,
        ) {
            enqueueCount++
            enqueueGate?.await()
            enqueueFailure?.let { exception -> throw exception }
        }

        override fun observe(batch: MediaCopyBatch): Flow<MediaCopyBatchStatus> {
            return statuses
        }
    }

    companion object {
        private const val BATCH_ITEM_COUNT_KEY = "current_copy_batch_item_count"
        private const val BATCH_TAG = "MediaCopyBatch_test"
        private const val BATCH_TAG_KEY = "current_copy_batch_tag"
        private const val BATCH_WORK_COUNT_KEY = "current_copy_batch_work_count"
        private const val DESTINATION_PATH = "Pictures/Test"
        private const val LEGACY_BATCH_ENQUEUE_CONFIRMED_KEY =
            "current_copy_batch_enqueue_confirmed"

        private val BATCH = MediaCopyBatch(
            tag = BATCH_TAG,
            workRequestCount = 1,
            itemCount = 3,
        )
    }
}
