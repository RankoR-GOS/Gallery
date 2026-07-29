package com.dot.gallery.feature_node.data.model.editor.crop

import android.graphics.Bitmap
import androidx.compose.runtime.Immutable

@Immutable
internal data class CropImage(
    val sourceBitmap: Bitmap,
    val previewBitmap: Bitmap,
)
