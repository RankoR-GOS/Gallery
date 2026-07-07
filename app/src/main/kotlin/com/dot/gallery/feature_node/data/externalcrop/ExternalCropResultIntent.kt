package com.dot.gallery.feature_node.data.externalcrop

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import com.dot.gallery.feature_node.domain.model.editor.crop.CropPixelRect

private const val EXTRA_CROPPED_RECT = "cropped-rect"
private const val EXTRA_DATA = "data"

internal fun buildCropResultIntent(
    croppedRect: CropPixelRect,
    outputUri: Uri?,
    returnDataBitmap: Bitmap?,
): Intent {
    return Intent().apply {
        putExtra(EXTRA_CROPPED_RECT, croppedRect.toAndroidRect())
        outputUri?.let { uri ->
            data = uri
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        returnDataBitmap?.let { bitmap ->
            putExtra(EXTRA_DATA, bitmap)
        }
    }
}
