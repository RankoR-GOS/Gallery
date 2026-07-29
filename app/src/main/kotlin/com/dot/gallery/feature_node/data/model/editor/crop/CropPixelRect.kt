package com.dot.gallery.feature_node.data.model.editor.crop

import android.graphics.Rect
import androidx.compose.runtime.Immutable

@Immutable
internal data class CropPixelRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int
        get() {
            return right - left
        }

    val height: Int
        get() {
            return bottom - top
        }

    fun toAndroidRect(): Rect {
        return Rect(
            left,
            top,
            right,
            bottom,
        )
    }

    companion object {
        fun fromNormalizedRect(
            normalizedRect: NormalizedCropRect,
            bitmapWidth: Int,
            bitmapHeight: Int,
        ): CropPixelRect {
            val left = (normalizedRect.left * bitmapWidth.toFloat()).toInt().coerceIn(
                minimumValue = 0,
                maximumValue = bitmapWidth - 1,
            )

            val top = (normalizedRect.top * bitmapHeight.toFloat()).toInt().coerceIn(
                minimumValue = 0,
                maximumValue = bitmapHeight - 1,
            )

            val right = (normalizedRect.right * bitmapWidth.toFloat()).toInt().coerceIn(
                minimumValue = left + 1,
                maximumValue = bitmapWidth,
            )

            val bottom = (normalizedRect.bottom * bitmapHeight.toFloat()).toInt().coerceIn(
                minimumValue = top + 1,
                maximumValue = bitmapHeight,
            )

            return CropPixelRect(
                left = left,
                top = top,
                right = right,
                bottom = bottom,
            )
        }
    }
}
