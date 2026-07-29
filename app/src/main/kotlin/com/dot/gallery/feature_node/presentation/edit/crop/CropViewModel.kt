package com.dot.gallery.feature_node.presentation.edit.crop

import android.content.IntentSender
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dot.gallery.feature_node.data.model.editor.crop.CropImage
import com.dot.gallery.feature_node.data.model.editor.crop.ExternalCropRequest
import com.dot.gallery.feature_node.data.model.editor.crop.NormalizedCropRect
import com.dot.gallery.feature_node.data.repository.ExternalCropRepository
import com.dot.gallery.feature_node.data.repository.ExternalCropSaveResult
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal interface CropScreenModel {
    val effects: Flow<CropEffect>
    val uiState: StateFlow<CropUiState>

    fun onLaunchRequest(request: ExternalCropRequest)
    fun onCancelClick()
    fun onDoneClick()
    fun onCropRectChanged(normalizedRect: NormalizedCropRect)
    fun onOutputWritePermissionResult(isGranted: Boolean)
}

@HiltViewModel
internal class CropViewModel @Inject constructor(
    private val repository: ExternalCropRepository,
) : ViewModel(),
    CropScreenModel {

    private val effectsChannel = Channel<CropEffect>(capacity = Channel.BUFFERED)
    private val _uiState = MutableStateFlow(CropUiState())

    private var request: ExternalCropRequest? = null
    private var loadJob: Job? = null
    private var saveJob: Job? = null
    private var hasFinished = false
    private var pendingSaveInput: CropSaveInput? = null
    private var hasRequestedOutputWritePermission = false

    override val effects = effectsChannel.receiveAsFlow()
    override val uiState = _uiState.asStateFlow()

    override fun onLaunchRequest(request: ExternalCropRequest) {
        if (this.request == request) {
            return
        }

        startLaunchRequest(request = request)
        loadCropImage(request = request)
    }

    private fun startLaunchRequest(request: ExternalCropRequest) {
        this.request = request
        resetCropSession()

        _uiState.value = CropUiState(
            aspectRatio = request.aspectRatio,
        )
    }

    private fun resetCropSession() {
        hasFinished = false
        pendingSaveInput = null
        hasRequestedOutputWritePermission = false
        loadJob?.cancel()
        saveJob?.cancel()
    }

    private fun loadCropImage(request: ExternalCropRequest) {
        loadJob = viewModelScope.launch {
            try {
                loadCropImageIntoState(request = request)
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                setLoadErrorAndFinish()
            }
        }
    }

    private suspend fun loadCropImageIntoState(request: ExternalCropRequest) {
        val image = repository.loadImage(uri = request.sourceUri)
        if (image == null) {
            setLoadErrorAndFinish()
            return
        }

        setLoadedImage(
            request = request,
            image = image,
        )
    }

    private fun setLoadedImage(request: ExternalCropRequest, image: CropImage) {
        _uiState.update { state ->
            state.copy(
                image = image,
                normalizedCropRect = initialCropRect(
                    request = request,
                    image = image,
                ),
            )
        }
    }

    private fun initialCropRect(
        request: ExternalCropRequest,
        image: CropImage,
    ): NormalizedCropRect {
        return NormalizedCropRect.centeredForAspectRatio(
            sourceWidth = image.sourceBitmap.width,
            sourceHeight = image.sourceBitmap.height,
            aspectRatio = request.aspectRatio,
        )
    }

    override fun onCancelClick() {
        loadJob?.cancel()
        saveJob?.cancel()
        _uiState.update { state ->
            state.copy(isSaving = false)
        }
        sendTerminalEffect(effect = CropEffect.FinishCanceled)
    }

    override fun onDoneClick() {
        if (!canStartCropSave()) {
            return
        }

        val saveInput = createCropSaveInput() ?: return
        saveJob = launchCropSave(saveInput = saveInput)
    }

    override fun onCropRectChanged(normalizedRect: NormalizedCropRect) {
        if (hasFinished) {
            return
        }

        _uiState.update { state ->
            state.copy(
                normalizedCropRect = normalizedRect,
            )
        }
    }

    private fun canStartCropSave(): Boolean {
        val currentState = _uiState.value
        return currentState.image != null &&
            currentState.normalizedCropRect != null &&
            !currentState.isSaving &&
            saveJob?.isActive != true &&
            !hasFinished
    }

    private fun createCropSaveInput(): CropSaveInput? {
        val currentRequest = request ?: return null
        val currentState = uiState.value
        val image = currentState.image ?: return null
        val normalizedRect = currentState.normalizedCropRect ?: return null

        return CropSaveInput(
            request = currentRequest,
            image = image,
            normalizedRect = normalizedRect,
        )
    }

    private fun launchCropSave(saveInput: CropSaveInput): Job {
        return viewModelScope.launch {
            saveCropResult(saveInput = saveInput)
        }
    }

    private suspend fun saveCropResult(saveInput: CropSaveInput) {
        try {
            setSaving(isSaving = true)
            val resultEffect = saveCropAndCreateResultEffect(saveInput = saveInput)
            setSaving(isSaving = false)

            when (resultEffect) {
                // The save resumes in onOutputWritePermissionResult, so the session stays open.
                is CropEffect.RequestOutputWritePermission -> sendEffect(effect = resultEffect)
                else -> sendTerminalEffect(effect = resultEffect)
            }
        } catch (exception: CancellationException) {
            setSaving(isSaving = false)
            throw exception
        } catch (_: Exception) {
            setSaving(isSaving = false)
            sendTerminalEffect(effect = CropEffect.ShowSaveErrorAndCancel)
        }
    }

    private suspend fun saveCropAndCreateResultEffect(saveInput: CropSaveInput): CropEffect {
        return createCropResultEffect(
            saveInput = saveInput,
            saveResult = repository.saveCropResult(
                request = saveInput.request,
                image = saveInput.image,
                normalizedRect = saveInput.normalizedRect,
            ),
        )
    }

    private fun createCropResultEffect(
        saveInput: CropSaveInput,
        saveResult: ExternalCropSaveResult,
    ): CropEffect {
        return when (saveResult) {
            is ExternalCropSaveResult.Saved -> {
                CropEffect.FinishWithResult(resultIntent = saveResult.resultIntent)
            }

            is ExternalCropSaveResult.OutputPermissionRequired -> {
                createOutputWritePermissionEffect(
                    saveInput = saveInput,
                    intentSender = saveResult.intentSender,
                )
            }

            ExternalCropSaveResult.Failed -> CropEffect.ShowSaveErrorAndCancel
        }
    }

    /**
     * Only ask once per session: if the save still cannot write after the user has answered, asking
     * again would loop the dialog.
     */
    private fun createOutputWritePermissionEffect(
        saveInput: CropSaveInput,
        intentSender: IntentSender,
    ): CropEffect {
        if (hasRequestedOutputWritePermission) {
            return CropEffect.ShowSaveErrorAndCancel
        }

        hasRequestedOutputWritePermission = true
        pendingSaveInput = saveInput

        return CropEffect.RequestOutputWritePermission(intentSender = intentSender)
    }

    override fun onOutputWritePermissionResult(isGranted: Boolean) {
        val saveInput = pendingSaveInput ?: return
        pendingSaveInput = null

        if (!isGranted) {
            sendTerminalEffect(effect = CropEffect.FinishCanceled)
            return
        }

        saveJob = launchCropSave(saveInput = saveInput)
    }

    private fun setSaving(isSaving: Boolean) {
        _uiState.update { state ->
            state.copy(isSaving = isSaving)
        }
    }

    private fun setLoadErrorAndFinish() {
        _uiState.update { state ->
            state.copy(
                image = null,
                isSaving = false,
            )
        }

        sendTerminalEffect(effect = CropEffect.FinishCanceled)
    }

    private fun sendTerminalEffect(effect: CropEffect) {
        if (hasFinished) {
            return
        }

        hasFinished = true
        sendEffect(effect = effect)
    }

    private fun sendEffect(effect: CropEffect) {
        viewModelScope.launch {
            effectsChannel.send(element = effect)
        }
    }
}

private data class CropSaveInput(
    val request: ExternalCropRequest,
    val image: CropImage,
    val normalizedRect: NormalizedCropRect,
)
