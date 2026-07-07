package com.smarttoolfactory.cropper

import android.content.res.Configuration
import android.graphics.RectF
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import com.smarttoolfactory.cropper.crop.CropAgent
import com.smarttoolfactory.cropper.draw.DrawingOverlay
import com.smarttoolfactory.cropper.draw.ImageDrawCanvas
import com.smarttoolfactory.cropper.image.ImageWithConstraints
import com.smarttoolfactory.cropper.image.getScaledImageBitmap
import com.smarttoolfactory.cropper.model.CropData
import com.smarttoolfactory.cropper.model.CropOutline
import com.smarttoolfactory.cropper.settings.CropDefaults
import com.smarttoolfactory.cropper.settings.CropProperties
import com.smarttoolfactory.cropper.settings.CropStyle
import com.smarttoolfactory.cropper.settings.CropType
import com.smarttoolfactory.cropper.state.DynamicCropState
import com.smarttoolfactory.cropper.state.rememberCropState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart

@Composable
fun ImageCropper(
    modifier: Modifier = Modifier,
    imageBitmap: ImageBitmap,
    contentDescription: String? = null,
    cropStyle: CropStyle = CropDefaults.style(),
    cropProperties: CropProperties,
    filterQuality: FilterQuality = DrawScope.DefaultFilterQuality,
    crop: Boolean = false,
    cropEnabled: Boolean = true,
    initialCropRect: RectF? = null,
    onCropStart: () -> Unit,
    onCropSuccess: (ImageBitmap) -> Unit,
    onCropRect: ((RectF) -> Unit)? = null,
    onCropRectChanged: ((RectF) -> Unit)? = null,
    backgroundModifier: Modifier = Modifier
) {

    ImageWithConstraints(
        modifier = modifier.clipToBounds(),
        contentScale = cropProperties.contentScale,
        contentDescription = contentDescription,
        filterQuality = filterQuality,
        imageBitmap = imageBitmap,
        drawImage = false
    ) {

        // No crop operation is applied by ScalableImage so rect points to bounds of original
        // bitmap
        val scaledImageBitmap = getScaledImageBitmap(
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            rect = rect,
            bitmap = imageBitmap,
            contentScale = cropProperties.contentScale,
        )

        // Container Dimensions
        val containerWidthPx = constraints.maxWidth
        val containerHeightPx = constraints.maxHeight

        val containerWidth: Dp
        val containerHeight: Dp

        // Bitmap Dimensions
        val bitmapWidth = scaledImageBitmap.width
        val bitmapHeight = scaledImageBitmap.height

        // Dimensions of Composable that displays Bitmap
        val imageWidthPx: Int
        val imageHeightPx: Int

        with(LocalDensity.current) {
            imageWidthPx = imageWidth.roundToPx()
            imageHeightPx =
                imageHeight.roundToPx() - if (LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT) 0
                else WindowInsets.navigationBars.getBottom(LocalDensity.current)
            containerWidth = containerWidthPx.toDp()
            containerHeight = containerHeightPx.toDp()
        }

        val cropType = cropProperties.cropType
        val contentScale = cropProperties.contentScale
        val fixedAspectRatio = cropProperties.fixedAspectRatio
        val cropOutline = cropProperties.cropOutlineProperty.cropOutline

        // these keys are for resetting cropper when image width/height, contentScale or
        // overlay aspect ratio changes
        val resetKeys =
            getResetKeys(
                cropEnabled,
                scaledImageBitmap,
                imageWidthPx,
                imageHeightPx,
                containerWidthPx,
                containerHeightPx,
                contentScale,
                cropType,
                fixedAspectRatio
            )

        val cropState = rememberCropState(
            imageSize = IntSize(bitmapWidth, bitmapHeight),
            containerSize = IntSize(containerWidthPx, containerHeightPx),
            drawAreaSize = IntSize(imageWidthPx, imageHeightPx),
            cropProperties = cropProperties,
            keys = resetKeys
        )

        val isHandleTouched by remember(cropState) {
            derivedStateOf {
                cropState is DynamicCropState && handlesTouched(cropState.touchRegion)
            }
        }

        LaunchedEffect(cropState) {
            val restoredOverlayRect = initialCropRect?.toOverlayRect(
                sourceRect = rect,
                imageWidth = imageBitmap.width,
                imageHeight = imageBitmap.height,
                drawAreaRect = cropState.drawAreaRect,
            )
            if (restoredOverlayRect != null) {
                cropState.snapOverlayRectTo(rect = restoredOverlayRect)
            }
        }

        val pressedStateColor = remember(cropStyle.backgroundColor) {
            cropStyle.backgroundColor
                .copy(cropStyle.backgroundColor.alpha * .7f)
        }

        val transparentColor by animateColorAsState(
            animationSpec = tween(300, easing = LinearEasing),
            targetValue = if (isHandleTouched) pressedStateColor else cropStyle.backgroundColor,
            label = "transparentColor"
        )

        // Crops image when user invokes crop operation
        Crop(
            crop,
            scaledImageBitmap,
            cropState.cropRect,
            cropOutline,
            onCropStart,
            onCropSuccess,
            onCropRect = onCropRect?.let { callback ->
                { cropRect ->
                    callback(
                        cropRect.toNormalizedRect(
                            sourceRect = rect,
                            imageWidth = imageBitmap.width,
                            imageHeight = imageBitmap.height,
                        )
                    )
                }
            }
        )

        val notifyCropRectChanged = onCropRectChanged?.let { callback ->
            { cropData: CropData ->
                callback(
                    cropData.cropRect.toNormalizedRect(
                        sourceRect = rect,
                        imageWidth = imageBitmap.width,
                        imageHeight = imageBitmap.height,
                    )
                )
            }
        }

        val imageModifier = Modifier
            .size(containerWidth, containerHeight)
            .crop(
                keys = resetKeys,
                cropState = cropState,
                onUp = notifyCropRectChanged,
                onGestureEnd = notifyCropRectChanged,
            )

        LaunchedEffect(key1 = cropProperties) {
            cropState.updateProperties(cropProperties)
        }

        /// Create a MutableTransitionState<Boolean> for the AnimatedVisibility.
        var visible by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            delay(100)
            visible = true
        }

        ImageCropper(
            modifier = imageModifier,
            visible = visible,
            cropEnabled = cropEnabled,
            imageBitmap = imageBitmap,
            containerWidth = containerWidth,
            containerHeight = containerHeight,
            imageWidthPx = imageWidthPx,
            imageHeightPx = imageHeightPx,
            handleSize = cropProperties.handleSize,
            overlayRect = cropState.overlayRect,
            cropType = cropType,
            cropOutline = cropOutline,
            cropStyle = cropStyle,
            transparentColor = transparentColor,
            backgroundModifier = backgroundModifier
        )
    }
}

private fun Rect.toNormalizedRect(
    sourceRect: IntRect,
    imageWidth: Int,
    imageHeight: Int,
): RectF {
    val sourceWidth = imageWidth.toFloat()
    val sourceHeight = imageHeight.toFloat()
    return RectF(
        ((sourceRect.left + left) / sourceWidth).coerceIn(0f, 1f),
        ((sourceRect.top + top) / sourceHeight).coerceIn(0f, 1f),
        ((sourceRect.left + right) / sourceWidth).coerceIn(0f, 1f),
        ((sourceRect.top + bottom) / sourceHeight).coerceIn(0f, 1f),
    )
}

private fun RectF.toOverlayRect(
    sourceRect: IntRect,
    imageWidth: Int,
    imageHeight: Int,
    drawAreaRect: Rect,
): Rect? {
    if (drawAreaRect.isEmpty) {
        return null
    }

    val scaledLeft = left * imageWidth - sourceRect.left
    val scaledTop = top * imageHeight - sourceRect.top
    val scaledRight = right * imageWidth - sourceRect.left
    val scaledBottom = bottom * imageHeight - sourceRect.top
    if (scaledRight <= scaledLeft || scaledBottom <= scaledTop) {
        return null
    }

    val sourceWidth = sourceRect.width.toFloat()
    val sourceHeight = sourceRect.height.toFloat()
    if (sourceWidth <= 0f || sourceHeight <= 0f) {
        return null
    }

    return Rect(
        left = drawAreaRect.left + drawAreaRect.width * (scaledLeft / sourceWidth),
        top = drawAreaRect.top + drawAreaRect.height * (scaledTop / sourceHeight),
        right = drawAreaRect.left + drawAreaRect.width * (scaledRight / sourceWidth),
        bottom = drawAreaRect.top + drawAreaRect.height * (scaledBottom / sourceHeight),
    ).intersect(drawAreaRect)
}

@Composable
private fun ImageCropper(
    modifier: Modifier,
    visible: Boolean,
    cropEnabled: Boolean,
    imageBitmap: ImageBitmap,
    containerWidth: Dp,
    containerHeight: Dp,
    imageWidthPx: Int,
    imageHeightPx: Int,
    handleSize: Float,
    cropType: CropType,
    cropOutline: CropOutline,
    cropStyle: CropStyle,
    overlayRect: Rect,
    transparentColor: Color,
    backgroundModifier: Modifier
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(backgroundModifier)
    ) {

        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(500))
        ) {
            ImageCropperImpl(
                modifier = modifier,
                cropEnabled = cropEnabled,
                imageBitmap = imageBitmap,
                containerWidth = containerWidth,
                containerHeight = containerHeight,
                imageWidthPx = imageWidthPx,
                imageHeightPx = imageHeightPx,
                cropType = cropType,
                cropOutline = cropOutline,
                handleSize = handleSize,
                cropStyle = cropStyle,
                rectOverlay = overlayRect,
                transparentColor = transparentColor
            )
        }
    }
}

@Composable
private fun ImageCropperImpl(
    modifier: Modifier,
    imageBitmap: ImageBitmap,
    cropEnabled: Boolean,
    containerWidth: Dp,
    containerHeight: Dp,
    imageWidthPx: Int,
    imageHeightPx: Int,
    cropType: CropType,
    cropOutline: CropOutline,
    handleSize: Float,
    cropStyle: CropStyle,
    transparentColor: Color,
    rectOverlay: Rect
) {

    Box(contentAlignment = Alignment.Center) {

        // Draw Image
        ImageDrawCanvas(
            modifier = modifier,
            imageBitmap = imageBitmap,
            imageWidth = imageWidthPx,
            imageHeight = imageHeightPx
        )

        val drawOverlay = cropStyle.drawOverlay

        val drawGrid = cropStyle.drawGrid
        val overlayColor = cropStyle.overlayColor
        val handleColor = cropStyle.handleColor
        val drawHandles = cropType == CropType.Dynamic
        val strokeWidth = cropStyle.strokeWidth

        AnimatedVisibility(
            visible = cropEnabled,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            DrawingOverlay(
                modifier = Modifier.size(containerWidth, containerHeight),
                drawOverlay = drawOverlay,
                rect = rectOverlay,
                cropOutline = cropOutline,
                drawGrid = drawGrid,
                overlayColor = overlayColor,
                handleColor = handleColor,
                strokeWidth = strokeWidth,
                drawHandles = drawHandles,
                handleSize = handleSize,
                transparentColor = transparentColor,
            )
        }
    }
}

@Composable
private fun Crop(
    crop: Boolean,
    scaledImageBitmap: ImageBitmap,
    cropRect: Rect,
    cropOutline: CropOutline,
    onCropStart: () -> Unit,
    onCropSuccess: (ImageBitmap) -> Unit,
    onCropRect: ((Rect) -> Unit)? = null
) {

    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current

    // Crop Agent is responsible for cropping image
    val cropAgent = remember { CropAgent() }

    LaunchedEffect(crop) {
        if (crop) {
            flow {
                emit(
                    cropAgent.crop(
                        scaledImageBitmap,
                        cropRect,
                        cropOutline,
                        layoutDirection,
                        density
                    )
                )
            }
                .flowOn(Dispatchers.Default)
                .onStart {
                    onCropStart()
                    onCropRect?.invoke(cropRect)
                    delay(400)
                }
                .onEach {
                    onCropSuccess(it)
                }
                .launchIn(this)
        }
    }
}

@Composable
private fun getResetKeys(
    cropEnabled: Boolean,
    scaledImageBitmap: ImageBitmap,
    imageWidthPx: Int,
    imageHeightPx: Int,
    containerWidthPx: Int,
    containerHeightPx: Int,
    contentScale: ContentScale,
    cropType: CropType,
    fixedAspectRatio: Boolean,
) = remember(
    cropEnabled,
    scaledImageBitmap,
    imageWidthPx,
    imageHeightPx,
    containerWidthPx,
    containerHeightPx,
    contentScale,
    cropType,
    fixedAspectRatio,
) {
    arrayOf(
        cropEnabled,
        scaledImageBitmap,
        imageWidthPx,
        imageHeightPx,
        containerWidthPx,
        containerHeightPx,
        contentScale,
        cropType,
        fixedAspectRatio,
    )
}
