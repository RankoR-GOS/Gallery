package com.dot.gallery.feature_node.presentation.securereview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dot.gallery.feature_node.data.repository.SecureReviewMediaRepository
import com.dot.gallery.feature_node.data.model.securereview.AuthorizedSecureReviewRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

internal interface SecureReviewScreenModel {
    val effects: Flow<SecureReviewEffect>
    val uiState: StateFlow<SecureReviewUiState>

    fun onLaunchRequest(request: AuthorizedSecureReviewRequest)
    fun onCloseClick()
}

@HiltViewModel
internal class SecureReviewViewModel @Inject constructor(
    private val repository: SecureReviewMediaRepository,
) : ViewModel(),
    SecureReviewScreenModel {

    private val effectsChannel = Channel<SecureReviewEffect>(capacity = Channel.BUFFERED)
    private val _uiState = MutableStateFlow<SecureReviewUiState>(SecureReviewUiState.Loading)

    private var request: AuthorizedSecureReviewRequest? = null
    private var loadJob: Job? = null
    private var hasFinished = false

    override val effects = effectsChannel.receiveAsFlow()
    override val uiState = _uiState.asStateFlow()

    override fun onLaunchRequest(request: AuthorizedSecureReviewRequest) {
        if (this.request == request) {
            return
        }

        this.request = request
        hasFinished = false
        loadJob?.cancel()
        _uiState.value = SecureReviewUiState.Loading
        loadJob = viewModelScope.launch {
            loadMedia(request = request)
        }
    }

    override fun onCloseClick() {
        loadJob?.cancel()
        sendFinishEffect()
    }

    private suspend fun loadMedia(request: AuthorizedSecureReviewRequest) {
        try {
            val media = repository.loadMedia(request = request)
            val isMediaIncomplete = media == null || media.size != request.uris.size

            when {
                isMediaIncomplete -> sendFinishEffect()
                else -> {
                    _uiState.value = SecureReviewUiState.Ready(media = media)
                }
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            sendFinishEffect()
        }
    }

    private fun sendFinishEffect() {
        if (hasFinished) {
            return
        }

        hasFinished = true
        viewModelScope.launch {
            effectsChannel.send(element = SecureReviewEffect.Finish)
        }
    }
}
