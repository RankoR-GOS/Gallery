package com.dot.gallery.feature_node.domain.externalcrop

import android.app.ComponentCaller
import android.content.Intent
import com.dot.gallery.feature_node.domain.model.editor.crop.ExternalCropRequest

internal interface ExternalCropIntentParser {

    fun parse(
        intent: Intent,
        caller: ComponentCaller,
    ): ExternalCropRequest?

    companion object {
        const val ACTION_CROP = "com.android.camera.action.CROP"
    }
}
