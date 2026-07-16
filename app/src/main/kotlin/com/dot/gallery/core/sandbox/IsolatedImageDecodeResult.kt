package com.dot.gallery.core.sandbox

import android.graphics.Bitmap
import android.util.Size

internal data class IsolatedImageDecodeResult(
    val bitmap: Bitmap,
    val originalSize: Size,
)
