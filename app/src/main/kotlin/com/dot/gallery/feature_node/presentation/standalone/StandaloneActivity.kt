/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.feature_node.presentation.standalone

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dot.gallery.core.DefaultEventHandler
import com.dot.gallery.core.MediaDistributor
import com.dot.gallery.core.MediaHandler
import com.dot.gallery.core.MediaSelector
import com.dot.gallery.core.Settings.Misc.rememberAllowBlur
import com.dot.gallery.core.util.SetupMediaProviders
import com.dot.gallery.core.util.enforceSecureMode
import com.dot.gallery.feature_node.domain.model.UIEvent
import com.dot.gallery.feature_node.domain.util.EventHandler
import com.dot.gallery.feature_node.presentation.mediaview.MediaViewScreenRoute
import com.dot.gallery.feature_node.presentation.exif.MetadataViewScreen
import com.dot.gallery.feature_node.presentation.exif.MetadataViewViewModel
import com.dot.gallery.feature_node.presentation.util.Screen
import com.dot.gallery.feature_node.presentation.util.LocalHazeState
import com.dot.gallery.feature_node.presentation.util.toggleOrientation
import com.dot.gallery.ui.theme.GalleryTheme
import dagger.hilt.android.AndroidEntryPoint
import dev.chrisbanes.haze.LocalHazeStyle
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import dev.chrisbanes.haze.rememberHazeState
import javax.inject.Inject

private const val CAMERA_ACTION_REVIEW = "com.android.camera.action.REVIEW"

@AndroidEntryPoint
class StandaloneActivity : AppCompatActivity() {

    private val eventHandler: EventHandler = DefaultEventHandler()

    @Inject
    lateinit var mediaDistributor: MediaDistributor

    @Inject
    lateinit var mediaHandler: MediaHandler

    @Inject
    lateinit var mediaSelector: MediaSelector

    @OptIn(ExperimentalSharedTransitionApi::class, ExperimentalHazeMaterialsApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enforceSecureMode()
        if (intent.action == MediaStore.ACTION_REVIEW_SECURE) {
            finish()
            return
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        enableEdgeToEdge()
        val isReview = isReviewAction(reviewIntent = intent)
        val uriList = getReviewUris(reviewIntent = intent)
        setContent {
            GalleryTheme {
                val allowBlur by rememberAllowBlur()
                val hazeState = rememberHazeState(
                    blurEnabled = allowBlur
                )
                val viewModel =
                    hiltViewModel<StandaloneViewModel, StandaloneViewModel.Factory> { factory ->
                        factory.create(
                            reviewMode = isReview,
                            secureReviewMode = false,
                            dataList = uriList,
                        )
                    }
                CompositionLocalProvider(
                    LocalHazeState provides hazeState,
                    LocalHazeStyle provides HazeMaterials.thin(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                ) {
                    var metadataArgs by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
                    LaunchedEffect(Unit) {
                        eventHandler.navigateUpAction = {
                            when {
                                metadataArgs != null -> metadataArgs = null
                                else -> finish()
                            }
                        }
                    }
                    LaunchedEffect(eventHandler) {
                        eventHandler.updaterFlow.collect { event ->
                            when (event) {
                                UIEvent.NavigationUpEvent -> eventHandler.navigateUpAction()
                                is UIEvent.NavigationRouteEvent -> {
                                    val parsed = Uri.parse(event.route)
                                    if (parsed.path == Screen.MetadataViewScreen.route) {
                                        parsed.getQueryParameter("mediaUri")?.takeIf { it.isNotBlank() }?.let { uri ->
                                            metadataArgs = uri to (parsed.getQueryParameter("isVideo") == "true")
                                        }
                                    }
                                }
                                else -> Unit
                            }
                        }
                    }
                    SetupMediaProviders(
                        eventHandler = eventHandler,
                        mediaDistributor = mediaDistributor,
                        mediaHandler = mediaHandler,
                        mediaSelector = mediaSelector
                    ) {
                        Scaffold { paddingValues ->
                            val mediaState = viewModel.mediaState.collectAsStateWithLifecycle()
                            val albumsState = viewModel.albumsState.collectAsStateWithLifecycle()
                            val metadataState =
                                viewModel.metadataState.collectAsStateWithLifecycle()
                            val mediaId by viewModel.mediaId.collectAsStateWithLifecycle()
                            SharedTransitionLayout {
                                AnimatedContent(
                                    targetState = metadataArgs,
                                    label = "standalone"
                                ) { args ->
                                    if (args == null) {
                                        MediaViewScreenRoute(
                                            toggleRotate = ::toggleOrientation,
                                            paddingValues = paddingValues,
                                            isStandalone = true,
                                            isSecureReview = false,
                                            mediaId = mediaId,
                                            mediaState = mediaState,
                                            albumsState = albumsState,
                                            metadataState = metadataState,
                                            sharedTransitionScope = this@SharedTransitionLayout,
                                            animatedContentScope = this
                                        )
                                    } else {
                                        val (uri, isVideo) = args
                                        val metadataViewModel = hiltViewModel<MetadataViewViewModel>()
                                        val state by metadataViewModel.state.collectAsStateWithLifecycle()
                                        LaunchedEffect(uri, isVideo) {
                                            metadataViewModel.loadMetadata(mediaUri = uri, isVideo = isVideo)
                                        }
                                        MetadataViewScreen(state = state)
                                    }
                                }
                            }
                        }
                        BackHandler {
                            eventHandler.navigateUpAction()
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == MediaStore.ACTION_REVIEW_SECURE) {
            finish()
            return
        }
        setIntent(intent)
        viewModelStore.clear()
        recreate()
    }

    private fun isReviewAction(reviewIntent: Intent): Boolean {
        return reviewIntent.action == MediaStore.ACTION_REVIEW ||
                reviewIntent.action == CAMERA_ACTION_REVIEW
    }

    private fun getReviewUris(reviewIntent: Intent): List<Uri> {
        val uriList = linkedSetOf<Uri>()
        reviewIntent.data?.let(uriList::add)
        reviewIntent.clipData?.let { clipData ->
            for (i in 0 until clipData.itemCount) {
                clipData.getItemAt(i).uri?.let(uriList::add)
            }
        }

        return uriList.toList()
    }

}
