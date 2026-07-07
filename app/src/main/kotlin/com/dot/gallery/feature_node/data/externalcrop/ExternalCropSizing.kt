package com.dot.gallery.feature_node.data.externalcrop

import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val ARGB_BYTES_PER_PIXEL = 4
private const val MAX_BITMAP_BYTES_IN_RESULT = 750_000
private const val MAX_CROP_OUTPUT_AREA_SIDE = 4096
private const val MAX_CROP_OUTPUT_DIMENSION = 8192
private const val MAX_CROP_OUTPUT_PIXELS =
    MAX_CROP_OUTPUT_AREA_SIDE * MAX_CROP_OUTPUT_AREA_SIDE

internal data class CropSize(
    val width: Int,
    val height: Int,
)

internal fun resolveCropOutputSize(
    cropWidth: Int,
    cropHeight: Int,
    outputX: Int,
    outputY: Int,
    scale: Boolean,
    scaleUpIfNeeded: Boolean,
): CropSize {
    return when {
        isInvalidCropSize(
            width = cropWidth,
            height = cropHeight,
        ) -> minimumCropSize()

        !hasRequestedOutput(
            outputX = outputX,
            outputY = outputY,
        ) -> {
            CropSize(
                width = cropWidth,
                height = cropHeight,
            )
        }

        !scale -> {
            clampCropOutputSize(
                width = outputX,
                height = outputY,
            )
        }

        else -> {
            resolveRequestedCropOutputSize(
                cropSize = CropSize(
                    width = cropWidth,
                    height = cropHeight,
                ),
                outputX = outputX,
                outputY = outputY,
                scaleUpIfNeeded = scaleUpIfNeeded,
            )
        }
    }
}

private fun isInvalidCropSize(width: Int, height: Int): Boolean {
    return width <= 0 || height <= 0
}

private fun minimumCropSize(): CropSize {
    return CropSize(
        width = 1,
        height = 1,
    )
}

private fun hasRequestedOutput(
    outputX: Int,
    outputY: Int,
): Boolean {
    return outputX > 0 && outputY > 0
}

private fun resolveRequestedCropOutputSize(
    cropSize: CropSize,
    outputX: Int,
    outputY: Int,
    scaleUpIfNeeded: Boolean,
): CropSize {
    val requestedSize = clampCropOutputSize(
        width = outputX,
        height = outputY,
    )
    return when {
        scaleUpIfNeeded -> requestedSize
        else -> {
            downscaleCropSizeToRequestedBounds(
                cropSize = cropSize,
                requestedSize = requestedSize,
            )
        }
    }
}

private fun downscaleCropSizeToRequestedBounds(
    cropSize: CropSize,
    requestedSize: CropSize,
): CropSize {
    val scaleFactor = minOf(
        1.0,
        requestedSize.width.toDouble() / cropSize.width.toDouble(),
        requestedSize.height.toDouble() / cropSize.height.toDouble(),
    )
    return scaleCropSize(
        cropSize = cropSize,
        scaleFactor = scaleFactor,
    )
}

private fun scaledDimension(dimension: Int, scaleFactor: Double): Int {
    return (dimension.toDouble() * scaleFactor)
        .roundToInt()
        .coerceAtLeast(minimumValue = 1)
}

internal fun resolveCropSourceDecodeSize(
    sourceWidth: Int,
    sourceHeight: Int,
): CropSize {
    return when {
        isInvalidCropSize(
            width = sourceWidth,
            height = sourceHeight,
        ) -> minimumCropSize()

        else -> {
            clampCropOutputSize(
                width = sourceWidth,
                height = sourceHeight,
            )
        }
    }
}

private fun clampCropOutputSize(width: Int, height: Int): CropSize {
    return positiveCropSize(
        width = width,
        height = height,
    )
        .let(::clampCropSizeToMaxDimensions)
        .let(::clampCropSizeToMaxPixels)
}

private fun positiveCropSize(width: Int, height: Int): CropSize {
    return CropSize(
        width = width.coerceAtLeast(minimumValue = 1),
        height = height.coerceAtLeast(minimumValue = 1),
    )
}

private fun clampCropSizeToMaxDimensions(cropSize: CropSize): CropSize {
    val scaleFactor = minOf(
        1.0,
        MAX_CROP_OUTPUT_DIMENSION.toDouble() / cropSize.width.toDouble(),
        MAX_CROP_OUTPUT_DIMENSION.toDouble() / cropSize.height.toDouble(),
    )

    return scaleCropSize(
        cropSize = cropSize,
        scaleFactor = scaleFactor,
    )
}

private fun clampCropSizeToMaxPixels(cropSize: CropSize): CropSize {
    val pixelCount = cropPixelCount(cropSize = cropSize)

    return when {
        pixelCount <= MAX_CROP_OUTPUT_PIXELS.toLong() -> cropSize
        else -> {
            scaleCropSize(
                cropSize = cropSize,
                scaleFactor = sqrt(MAX_CROP_OUTPUT_PIXELS.toDouble() / pixelCount.toDouble()),
            )
        }
    }
}

private fun cropPixelCount(cropSize: CropSize): Long {
    return cropSize.width.toLong() * cropSize.height.toLong()
}

private fun scaleCropSize(cropSize: CropSize, scaleFactor: Double): CropSize {
    return CropSize(
        width = scaledDimension(
            dimension = cropSize.width,
            scaleFactor = scaleFactor,
        ),
        height = scaledDimension(
            dimension = cropSize.height,
            scaleFactor = scaleFactor,
        ),
    )
}

internal fun resolveIntentBitmapSize(
    width: Int,
    height: Int,
): CropSize {
    var targetWidth = width.coerceAtLeast(minimumValue = 1)
    var targetHeight = height.coerceAtLeast(minimumValue = 1)

    while (targetWidth.toLong() * targetHeight.toLong() * ARGB_BYTES_PER_PIXEL >
        MAX_BITMAP_BYTES_IN_RESULT.toLong() &&
        targetWidth > 1 &&
        targetHeight > 1
    ) {
        targetWidth = (targetWidth / 2).coerceAtLeast(minimumValue = 1)
        targetHeight = (targetHeight / 2).coerceAtLeast(minimumValue = 1)
    }

    return CropSize(
        width = targetWidth,
        height = targetHeight,
    )
}
