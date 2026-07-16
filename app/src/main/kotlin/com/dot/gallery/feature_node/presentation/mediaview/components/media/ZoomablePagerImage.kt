/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.feature_node.presentation.mediaview.components.media

import android.graphics.drawable.Drawable
import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.integration.compose.GlideImage
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi as ExperimentalGlideImageComposeApi
import com.bumptech.glide.load.resource.gif.GifDrawable
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.dot.gallery.R
import com.dot.gallery.core.Constants.DEFAULT_TOP_BAR_ANIMATION_DURATION
import com.dot.gallery.core.Settings
import com.dot.gallery.core.decoder.glide.galleryMediaSubsamplingImageGenerators
import com.dot.gallery.core.presentation.components.util.LocalBatteryStatus
import com.dot.gallery.core.presentation.components.util.ProvideBatteryStatus
import com.dot.gallery.core.presentation.components.util.swipe
import com.dot.gallery.feature_node.domain.model.Media
import com.dot.gallery.feature_node.presentation.util.GlideInvalidation
import com.dot.gallery.feature_node.presentation.util.rememberFeedbackManager
import com.dot.gallery.feature_node.presentation.util.toGlideModel
import com.github.panpf.zoomimage.GlideZoomAsyncImage
import com.github.panpf.zoomimage.compose.glide.ExperimentalGlideComposeApi
import com.github.panpf.zoomimage.rememberGlideZoomState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalGlideImageComposeApi::class)
@Composable
fun <T: Media> BlurredMediaBackground(
    media: T,
    uiEnabled: Boolean,
) {
    ProvideBatteryStatus {
        val allowBlur by Settings.Misc.rememberAllowBlur()
        val isPowerSavingMode = LocalBatteryStatus.current.isPowerSavingMode
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && allowBlur && !isPowerSavingMode) {
            val blurAlpha by animateFloatAsState(
                animationSpec = tween(DEFAULT_TOP_BAR_ANIMATION_DURATION),
                targetValue = if (uiEnabled) 0.7f else 0f,
                label = "blurAlpha"
            )
            GlideImage(
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(blurAlpha)
                    .blur(100.dp),
                model = media.toGlideModel(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                requestBuilderTransform = {
                    it.override(600)
                        .signature(GlideInvalidation.signature(media))
                        .thumbnail(it.clone().sizeMultiplier(0.1f))
                }
            )
        }
    }
}

@OptIn(
    ExperimentalGlideComposeApi::class,
    ExperimentalGlideImageComposeApi::class,
)
@Stable
@Composable
fun <T: Media> BoxScope.ZoomablePagerImage(
    modifier: Modifier = Modifier,
    media: T,
    uiEnabled: Boolean,
    rotationDisabled: Boolean,
    onImageRotated: (newRotation: Int) -> Unit,
    onItemClick: () -> Unit,
    onSwipeDown: () -> Unit
) {
    val feedbackManager = rememberFeedbackManager()
    var isRotating by rememberSaveable(media) { mutableStateOf(false) }
    var currentRotation by rememberSaveable(media) { mutableIntStateOf(0) }
    var loadFailed by rememberSaveable(media) { mutableStateOf(false) }
    var retryAttempt by rememberSaveable(media) { mutableIntStateOf(0) }
    val rotationAnimation by animateFloatAsState(
        targetValue = if (isRotating) 90f else 0f,
        label = "rotationAnimation"
    )
    val subsamplingImageGenerators = remember {
        galleryMediaSubsamplingImageGenerators()
    }
    val zoomState = rememberGlideZoomState(subsamplingImageGenerators = subsamplingImageGenerators)
    val scope = rememberCoroutineScope()
    val requestListener = remember(media) {
        object : RequestListener<Drawable> {
            override fun onLoadFailed(
                exception: GlideException?,
                model: Any?,
                target: Target<Drawable>,
                isFirstResource: Boolean,
            ): Boolean {
                loadFailed = true
                return false
            }

            override fun onResourceReady(
                resource: Drawable,
                model: Any,
                target: Target<Drawable>?,
                dataSource: DataSource,
                isFirstResource: Boolean,
            ): Boolean {
                loadFailed = false
                return false
            }
        }
    }

    GlideZoomAsyncImage(
        zoomState = zoomState,
        model = media.toGlideModel(),
        modifier = Modifier
            .fillMaxSize()
            .swipe(
                onSwipeDown = onSwipeDown
            )
            .graphicsLayer {
                rotationZ = if (isRotating) rotationAnimation else 0f
            }
            .then(modifier),
        onTap = { onItemClick() },
        onLongPress = {
            if (!rotationDisabled) {
                scope.launch {
                    isRotating = true
                    feedbackManager.vibrate()
                    currentRotation += 90
                    onImageRotated(currentRotation)
                    delay(350)
                    zoomState.zoomable.rotate(currentRotation)
                    isRotating = false
                }
            }
        },
        alignment = Alignment.Center,
        contentDescription = media.label,
        requestBuilderTransform = {
            var builder = it
                .signature(GlideInvalidation.signature(obj = media, variant = retryAttempt))
                .thumbnail(it.clone().sizeMultiplier(0.1f))
                .addListener(requestListener)

            if (media.label.contains(".gif", ignoreCase = true)) {
                builder = builder.decode(GifDrawable::class.java)
            }

            builder
        },
        scrollBar = null
    )

    if (loadFailed) {
        ImageLoadFailure(
            modifier = Modifier.align(Alignment.Center),
            onRetry = {
                loadFailed = false
                retryAttempt += 1
            },
        )
    }
}

@Composable
private fun ImageLoadFailure(modifier: Modifier = Modifier, onRetry: () -> Unit) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.image_load_failed),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Button(onClick = onRetry) {
            Text(text = stringResource(R.string.retry))
        }
    }
}
