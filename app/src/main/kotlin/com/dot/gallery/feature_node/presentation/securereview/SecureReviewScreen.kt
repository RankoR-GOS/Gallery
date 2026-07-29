package com.dot.gallery.feature_node.presentation.securereview

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.dot.gallery.R
import com.dot.gallery.feature_node.data.model.Media
import com.dot.gallery.feature_node.data.model.securereview.AuthorizedSecureReviewRequest
import com.dot.gallery.feature_node.presentation.mediaview.components.media.MediaPreviewComponent
import com.dot.gallery.feature_node.presentation.mediaview.components.video.VideoPlayerController
import com.dot.gallery.ui.theme.GalleryTheme
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

@Composable
internal fun SecureReviewScreen(
    request: AuthorizedSecureReviewRequest,
    onFinish: () -> Unit,
    onToggleOrientation: () -> Unit,
    modifier: Modifier = Modifier,
    screenModel: SecureReviewScreenModel = hiltViewModel<SecureReviewViewModel>(),
) {
    val uiState by screenModel.uiState.collectAsStateWithLifecycle()
    val currentOnFinish by rememberUpdatedState(onFinish)

    LaunchedEffect(request, screenModel) {
        screenModel.onLaunchRequest(request = request)
    }

    LaunchedEffect(screenModel) {
        screenModel.effects.collect { effect ->
            when (effect) {
                SecureReviewEffect.Finish -> currentOnFinish()
            }
        }
    }

    BackHandler {
        screenModel.onCloseClick()
    }

    SecureReviewScreenContent(
        uiState = uiState,
        onClose = screenModel::onCloseClick,
        onToggleOrientation = onToggleOrientation,
        modifier = modifier,
    )
}

@Composable
internal fun SecureReviewScreenContent(
    uiState: SecureReviewUiState,
    onClose: () -> Unit,
    onToggleOrientation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        when (uiState) {
            SecureReviewUiState.Loading -> {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }

            is SecureReviewUiState.Ready -> {
                SecureReviewMediaPager(
                    media = uiState.media,
                    onClose = onClose,
                    onToggleOrientation = onToggleOrientation,
                )
            }
        }

        IconButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopStart)
                .systemBarsPadding(),
        ) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = stringResource(id = R.string.close),
            )
        }
    }
}

@Composable
@OptIn(UnstableApi::class)
private fun SecureReviewMediaPager(
    media: ImmutableList<Media.UriMedia>,
    onClose: () -> Unit,
    onToggleOrientation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pagerState = rememberPagerState(pageCount = media::size)

    HorizontalPager(
        state = pagerState,
        modifier = modifier.fillMaxSize(),
        beyondViewportPageCount = 0,
        key = { index -> media[index].id },
    ) { page ->
        val playWhenReady = remember(pagerState, page) {
            derivedStateOf { pagerState.currentPage == page }
        }

        MediaPreviewComponent(
            media = media[page],
            modifier = Modifier,
            containerModifier = Modifier,
            uiEnabled = true,
            playWhenReady = playWhenReady,
            onItemClick = {},
            onSwipeDown = onClose,
            rotationDisabled = true,
            onImageRotated = {},
            offset = IntOffset.Zero,
            videoController = {
                    player,
                    isPlaying,
                    currentTime,
                    totalTime,
                    buffer,
                    frameRate,
                ->
                VideoPlayerController(
                    paddingValues = PaddingValues(),
                    player = player,
                    isPlaying = isPlaying,
                    currentTime = currentTime,
                    totalTime = totalTime,
                    buffer = buffer,
                    toggleRotate = onToggleOrientation,
                    frameRate = frameRate,
                )
            },
        )
    }
}

@Preview
@Composable
private fun SecureReviewScreenLoadingPreview() {
    GalleryTheme {
        SecureReviewScreenContent(
            uiState = SecureReviewUiState.Loading,
            onClose = {},
            onToggleOrientation = {},
        )
    }
}

@Preview
@Composable
private fun SecureReviewScreenReadyPreview() {
    GalleryTheme {
        SecureReviewScreenContent(
            uiState = SecureReviewUiState.Ready(
                media = persistentListOf(
                    Media.UriMedia(
                        id = -1L,
                        label = "Preview image",
                        uri = Uri.parse("content://preview/image/1"),
                        path = "",
                        relativePath = "",
                        albumID = -99L,
                        albumLabel = "",
                        timestamp = 0L,
                        expiryTimestamp = null,
                        takenTimestamp = null,
                        fullDate = "",
                        mimeType = "image/jpeg",
                        favorite = 0,
                        trashed = 0,
                        size = 0L,
                        duration = null,
                    ),
                ),
            ),
            onClose = {},
            onToggleOrientation = {},
        )
    }
}
