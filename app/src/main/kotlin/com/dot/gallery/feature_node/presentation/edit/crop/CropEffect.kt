package com.dot.gallery.feature_node.presentation.edit.crop

import android.content.Intent
import android.content.IntentSender

internal sealed interface CropEffect {

    data object FinishCanceled : CropEffect

    data object ShowSaveErrorAndCancel : CropEffect

    /**
     * Ask the user to grant write access to the caller-supplied output uri. Not terminal — the save
     * resumes once the system dialog returns.
     */
    data class RequestOutputWritePermission(
        val intentSender: IntentSender,
    ) : CropEffect

    data class FinishWithResult(
        val resultIntent: Intent,
    ) : CropEffect
}
