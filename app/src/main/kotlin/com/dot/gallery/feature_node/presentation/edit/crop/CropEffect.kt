package com.dot.gallery.feature_node.presentation.edit.crop

import android.content.Intent

internal sealed interface CropEffect {

    data object FinishCanceled : CropEffect

    data object ShowSaveErrorAndCancel : CropEffect

    data class FinishWithResult(
        val resultIntent: Intent,
    ) : CropEffect
}
