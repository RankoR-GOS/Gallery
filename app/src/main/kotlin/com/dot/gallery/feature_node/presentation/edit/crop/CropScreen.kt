package com.dot.gallery.feature_node.presentation.edit.crop

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dot.gallery.R
import com.dot.gallery.feature_node.data.model.editor.crop.CropImage
import com.dot.gallery.feature_node.data.model.editor.crop.ExternalCropRequest
import com.dot.gallery.feature_node.data.model.editor.crop.NormalizedCropRect
import com.smarttoolfactory.cropper.ImageCropper
import com.smarttoolfactory.cropper.model.AspectRatio
import com.smarttoolfactory.cropper.model.OutlineType
import com.smarttoolfactory.cropper.model.RectCropShape
import com.smarttoolfactory.cropper.settings.CropDefaults
import com.smarttoolfactory.cropper.settings.CropOutlineProperty
import com.smarttoolfactory.cropper.settings.CropProperties
import com.smarttoolfactory.cropper.settings.CropStyle

@Composable
internal fun CropScreen(
    request: ExternalCropRequest,
    onFinishCanceled: () -> Unit,
    onFinishWithResult: (Intent) -> Unit,
    onShowSaveErrorAndCancel: () -> Unit,
    modifier: Modifier = Modifier,
    screenModel: CropScreenModel = hiltViewModel<CropViewModel>(),
) {
    val uiState by screenModel.uiState.collectAsStateWithLifecycle()
    val currentOnFinishCanceled by rememberUpdatedState(onFinishCanceled)
    val currentOnFinishWithResult by rememberUpdatedState(onFinishWithResult)
    val currentOnShowSaveErrorAndCancel by rememberUpdatedState(onShowSaveErrorAndCancel)

    LaunchedEffect(request, screenModel) {
        screenModel.onLaunchRequest(request = request)
    }

    LaunchedEffect(screenModel) {
        screenModel.effects.collect { effect ->
            when (effect) {
                CropEffect.FinishCanceled -> currentOnFinishCanceled()
                CropEffect.ShowSaveErrorAndCancel -> currentOnShowSaveErrorAndCancel()
                is CropEffect.FinishWithResult -> {
                    currentOnFinishWithResult(effect.resultIntent)
                }
            }
        }
    }

    BackHandler {
        screenModel.onCancelClick()
    }

    CropScreenContent(
        modifier = modifier,
        uiState = uiState,
        onCancel = screenModel::onCancelClick,
        onDone = screenModel::onDoneClick,
        onCropRectChanged = screenModel::onCropRectChanged,
    )
}

@Composable
internal fun CropScreenContent(
    uiState: CropUiState,
    onCancel: () -> Unit,
    onDone: () -> Unit,
    onCropRectChanged: (NormalizedCropRect) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .systemBarsPadding(),
    ) {
        CropScreenTopBar(
            isDoneEnabled = uiState.image != null && !uiState.isSaving,
            isSaving = uiState.isSaving,
            onCancel = onCancel,
            onDone = onDone,
        )

        CropImageStage(
            cropImage = uiState.image,
            aspectRatio = uiState.aspectRatio,
            normalizedCropRect = uiState.normalizedCropRect,
            onCropRectChanged = onCropRectChanged,
        )
    }
}

@Composable
private fun CropScreenTopBar(
    isDoneEnabled: Boolean,
    isSaving: Boolean,
    onCancel: () -> Unit,
    onDone: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onCancel,
        ) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = stringResource(id = R.string.close),
            )
        }
        Text(
            text = stringResource(id = R.string.crop),
            style = MaterialTheme.typography.titleMedium,
        )
        CropDoneButton(
            isEnabled = isDoneEnabled,
            isSaving = isSaving,
            onDone = onDone,
        )
    }
}

@Composable
private fun CropDoneButton(
    isEnabled: Boolean,
    isSaving: Boolean,
    onDone: () -> Unit,
) {
    Button(
        onClick = onDone,
        enabled = isEnabled,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        colors = ButtonDefaults.buttonColors(),
    ) {
        when {
            isSaving -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )
            }

            else -> {
                Text(text = stringResource(id = R.string.done))
            }
        }
    }
}

@Composable
private fun CropImageStage(
    cropImage: CropImage?,
    aspectRatio: Float?,
    normalizedCropRect: NormalizedCropRect?,
    onCropRectChanged: (NormalizedCropRect) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest),
        contentAlignment = Alignment.Center,
    ) {
        when (cropImage) {
            null -> CircularProgressIndicator()
            else -> {
                LoadedCropImage(
                    cropImage = cropImage,
                    aspectRatio = aspectRatio,
                    normalizedCropRect = normalizedCropRect,
                    onCropRectChanged = onCropRectChanged,
                )
            }
        }
    }
}

@Composable
private fun LoadedCropImage(
    cropImage: CropImage,
    aspectRatio: Float?,
    normalizedCropRect: NormalizedCropRect?,
    onCropRectChanged: (NormalizedCropRect) -> Unit,
) {
    val previewImageBitmap = rememberPreviewImageBitmap(cropImage = cropImage)
    val cropProperties = rememberCropProperties(aspectRatio = aspectRatio)
    val cropStyle = rememberCropStyle()
    val initialCropRect = remember(normalizedCropRect) {
        normalizedCropRect?.toAndroidRectF()
    }

    ImageCropper(
        modifier = Modifier.fillMaxSize(),
        imageBitmap = previewImageBitmap,
        contentDescription = null,
        cropStyle = cropStyle,
        cropProperties = cropProperties,
        initialCropRect = initialCropRect,
        onCropStart = {},
        onCropSuccess = {},
        onCropRectChanged = { cropRect ->
            onCropRectChanged(NormalizedCropRect(rect = cropRect))
        },
    )
}

@Composable
private fun rememberPreviewImageBitmap(cropImage: CropImage): ImageBitmap {
    return remember(cropImage.previewBitmap) {
        cropImage.previewBitmap.asImageBitmap()
    }
}

@Composable
private fun rememberCropProperties(aspectRatio: Float?): CropProperties {
    return remember(aspectRatio) {
        CropDefaults.properties(
            cropOutlineProperty = CropOutlineProperty(
                outlineType = OutlineType.RoundedRect,
                cropOutline = RectCropShape(
                    id = 0,
                    title = OutlineType.RoundedRect.name,
                ),
            ),
            aspectRatio = createCropAspectRatio(aspectRatio = aspectRatio),
            overlayRatio = 1f,
            fixedAspectRatio = aspectRatio != null,
        )
    }
}

private fun createCropAspectRatio(aspectRatio: Float?): AspectRatio {
    return when (aspectRatio) {
        null -> AspectRatio.Original
        else -> AspectRatio(aspectRatio)
    }
}

@Composable
private fun rememberCropStyle(): CropStyle {
    val handleColor = MaterialTheme.colorScheme.tertiary
    return remember(handleColor) {
        CropDefaults.style(
            handleColor = handleColor,
            strokeWidth = 1.dp,
        )
    }
}
