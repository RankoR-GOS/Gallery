package com.dot.gallery.feature_node.presentation.edit.crop

import androidx.compose.runtime.Immutable
import com.dot.gallery.feature_node.domain.model.editor.crop.CropImage
import com.dot.gallery.feature_node.domain.model.editor.crop.NormalizedCropRect

@Immutable
internal data class CropUiState(
    val image: CropImage? = null,
    val aspectRatio: Float? = null,
    val normalizedCropRect: NormalizedCropRect? = null,
    val isSaving: Boolean = false,
)
