package com.dot.gallery.feature_node.presentation.exif

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dot.gallery.core.workers.MediaCopyBatch
import com.dot.gallery.core.workers.MediaCopyBatchStatus
import com.dot.gallery.core.workers.MediaCopyRequest
import com.dot.gallery.core.workers.MediaCopyScheduler
import com.dot.gallery.feature_node.data.model.Media
import com.dot.gallery.feature_node.data.util.getUri
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

private const val CURRENT_BATCH_ITEM_COUNT_KEY = "current_copy_batch_item_count"
private const val CURRENT_BATCH_TAG_KEY = "current_copy_batch_tag"
private const val CURRENT_BATCH_WORK_COUNT_KEY = "current_copy_batch_work_count"
private const val LEGACY_BATCH_ENQUEUE_CONFIRMED_KEY = "current_copy_batch_enqueue_confirmed"

@Immutable
internal sealed interface CopyMediaUiState {

    data object Selecting : CopyMediaUiState

    data class Copying(val progress: Float) : CopyMediaUiState

    data object Succeeded : CopyMediaUiState

    data class Failed(
        val copiedCount: Int,
        val failedCount: Int,
    ) : CopyMediaUiState

    data object StatusUnavailable : CopyMediaUiState
}

@HiltViewModel
internal class CopyMediaViewModel @Inject constructor(
    private val mediaCopyScheduler: MediaCopyScheduler,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val _uiState = MutableStateFlow<CopyMediaUiState>(CopyMediaUiState.Selecting)
    private var enqueueJob: Job? = null
    private var observationJob: Job? = null

    val uiState: StateFlow<CopyMediaUiState> = _uiState.asStateFlow()

    init {
        restoredBatch()?.let { batch ->
            observeBatch(batch = batch)
        }
    }

    fun <T : Media> enqueueCopy(vararg sets: Pair<T, String>) {
        if (sets.isEmpty() || _uiState.value is CopyMediaUiState.Copying) {
            return
        }

        val requests = sets.map { (media, path) ->
            MediaCopyRequest(
                sourceUri = media.getUri(),
                destinationPath = path,
            )
        }
        val batch = mediaCopyScheduler.prepareBatch(requests = requests)
        saveBatch(batch = batch)
        _uiState.value = CopyMediaUiState.Copying(progress = 0f)
        enqueueJob = viewModelScope.launch {
            try {
                mediaCopyScheduler.enqueue(
                    batch = batch,
                    requests = requests,
                )
                observeBatch(batch = batch)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                _uiState.value = CopyMediaUiState.StatusUnavailable
            }
        }
    }

    fun onResultHandled() {
        if (_uiState.value is CopyMediaUiState.Copying) {
            return
        }

        enqueueJob?.cancel()
        enqueueJob = null
        observationJob?.cancel()
        observationJob = null
        clearSavedBatch()
        _uiState.value = CopyMediaUiState.Selecting
    }

    private fun observeBatch(batch: MediaCopyBatch) {
        observationJob?.cancel()
        _uiState.value = CopyMediaUiState.Copying(progress = 0f)
        observationJob = viewModelScope.launch {
            try {
                val terminalStatus = mediaCopyScheduler.observe(batch = batch)
                    .onEach { status ->
                        if (status is MediaCopyBatchStatus.Copying) {
                            _uiState.value = CopyMediaUiState.Copying(progress = status.progress)
                        }
                    }
                    .firstOrNull { status -> status !is MediaCopyBatchStatus.Copying }

                _uiState.value = terminalStatus?.toUiState()
                    ?: CopyMediaUiState.StatusUnavailable
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                _uiState.value = CopyMediaUiState.StatusUnavailable
            }
        }
    }

    private fun saveBatch(batch: MediaCopyBatch) {
        savedStateHandle[CURRENT_BATCH_TAG_KEY] = batch.tag
        savedStateHandle[CURRENT_BATCH_WORK_COUNT_KEY] = batch.workRequestCount
        savedStateHandle[CURRENT_BATCH_ITEM_COUNT_KEY] = batch.itemCount
    }

    private fun restoredBatch(): MediaCopyBatch? {
        val tag = savedStateHandle.get<String>(CURRENT_BATCH_TAG_KEY)
        val workRequestCount = savedStateHandle.get<Int>(CURRENT_BATCH_WORK_COUNT_KEY)
        val itemCount = savedStateHandle.get<Int>(CURRENT_BATCH_ITEM_COUNT_KEY)

        return when {
            tag == null || workRequestCount == null || itemCount == null -> null
            workRequestCount <= 0 || itemCount <= 0 -> null
            else -> MediaCopyBatch(
                tag = tag,
                workRequestCount = workRequestCount,
                itemCount = itemCount,
            )
        }
    }

    private fun clearSavedBatch() {
        savedStateHandle.remove<String>(CURRENT_BATCH_TAG_KEY)
        savedStateHandle.remove<Int>(CURRENT_BATCH_WORK_COUNT_KEY)
        savedStateHandle.remove<Int>(CURRENT_BATCH_ITEM_COUNT_KEY)
        savedStateHandle.remove<Boolean>(LEGACY_BATCH_ENQUEUE_CONFIRMED_KEY)
    }
}

private fun MediaCopyBatchStatus.toUiState(): CopyMediaUiState {
    return when (this) {
        is MediaCopyBatchStatus.Copying -> CopyMediaUiState.Copying(progress = progress)
        is MediaCopyBatchStatus.Finished -> when {
            successful -> CopyMediaUiState.Succeeded
            else -> CopyMediaUiState.Failed(
                copiedCount = copiedCount,
                failedCount = failedCount,
            )
        }

        MediaCopyBatchStatus.Unavailable -> CopyMediaUiState.StatusUnavailable
    }
}
