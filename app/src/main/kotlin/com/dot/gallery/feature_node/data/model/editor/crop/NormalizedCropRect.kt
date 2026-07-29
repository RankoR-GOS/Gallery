package com.dot.gallery.feature_node.data.model.editor.crop

import android.graphics.RectF
import androidx.compose.runtime.Immutable
import kotlin.math.abs

@Immutable
internal data class NormalizedCropRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {

    constructor(rect: RectF) : this(
        left = rect.left,
        top = rect.top,
        right = rect.right,
        bottom = rect.bottom,
    )

    fun toAndroidRectF(): RectF {
        return RectF(
            left,
            top,
            right,
            bottom,
        )
    }

    companion object {
        fun full(): NormalizedCropRect {
            return NormalizedCropRect(
                left = 0f,
                top = 0f,
                right = 1f,
                bottom = 1f,
            )
        }

        fun centeredForAspectRatio(
            sourceWidth: Int,
            sourceHeight: Int,
            aspectRatio: Float?,
        ): NormalizedCropRect {
            if (sourceWidth <= 0 || sourceHeight <= 0 || aspectRatio == null || aspectRatio <= 0f) {
                return full()
            }

            val sourceAspectRatio = sourceWidth.toFloat() / sourceHeight.toFloat()
            return when {
                abs(sourceAspectRatio - aspectRatio) < ASPECT_RATIO_EPSILON -> full()
                sourceAspectRatio > aspectRatio -> {
                    centeredWidthCrop(width = aspectRatio / sourceAspectRatio)
                }

                else -> {
                    centeredHeightCrop(height = sourceAspectRatio / aspectRatio)
                }
            }
        }

        private fun centeredWidthCrop(width: Float): NormalizedCropRect {
            val normalizedWidth = width.coerceIn(
                minimumValue = 0f,
                maximumValue = 1f,
            )
            val left = (1f - normalizedWidth) / 2f

            return NormalizedCropRect(
                left = left,
                top = 0f,
                right = left + normalizedWidth,
                bottom = 1f,
            )
        }

        private fun centeredHeightCrop(height: Float): NormalizedCropRect {
            val normalizedHeight = height.coerceIn(
                minimumValue = 0f,
                maximumValue = 1f,
            )
            val top = (1f - normalizedHeight) / 2f

            return NormalizedCropRect(
                left = 0f,
                top = top,
                right = 1f,
                bottom = top + normalizedHeight,
            )
        }

        private const val ASPECT_RATIO_EPSILON = 0.0001f
    }
}
