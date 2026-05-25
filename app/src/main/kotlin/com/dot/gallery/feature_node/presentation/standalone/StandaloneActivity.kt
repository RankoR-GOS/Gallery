/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.feature_node.presentation.standalone

import android.app.KeyguardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import com.dot.gallery.feature_node.domain.model.UIEvent
import com.dot.gallery.feature_node.domain.util.EventHandler
import com.dot.gallery.feature_node.presentation.mediaview.MediaViewScreenRoute
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
class StandaloneActivity : ComponentActivity() {

    private val eventHandler: EventHandler = DefaultEventHandler()
    private var showWhenLockedForCurrentIntent = false

    @Inject
    lateinit var mediaDistributor: MediaDistributor

    @Inject
    lateinit var mediaHandler: MediaHandler

    @Inject
    lateinit var mediaSelector: MediaSelector

    @OptIn(ExperimentalSharedTransitionApi::class, ExperimentalHazeMaterialsApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        enableEdgeToEdge()
        val isReviewSecure = isReviewSecureAction(reviewIntent = intent)
        val isReview = isReviewAction(reviewIntent = intent)
        val uriList = getReviewUris(reviewIntent = intent)
        applyShowWhenLockedForIntent(reviewIntent = intent)
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
                            secureReviewMode = isReviewSecure,
                            dataList = uriList,
                        )
                    }
                CompositionLocalProvider(
                    LocalHazeState provides hazeState,
                    LocalHazeStyle provides HazeMaterials.thin(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                ) {
                    LaunchedEffect(Unit) {
                        eventHandler.navigateUpAction = { finish() }
                    }
                    LaunchedEffect(eventHandler) {
                        eventHandler.updaterFlow.collect {
                            if (it == UIEvent.NavigationUpEvent) {
                                finish()
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
                            val staticState by remember { mutableStateOf(true) }
                            SharedTransitionLayout {
                                AnimatedContent(
                                    targetState = staticState,
                                    label = "standalone"
                                ) { staticState ->
                                    if (staticState) {
                                        MediaViewScreenRoute(
                                            toggleRotate = ::toggleOrientation,
                                            paddingValues = paddingValues,
                                            isStandalone = true,
                                            isSecureReview = isReviewSecure,
                                            mediaId = mediaId,
                                            mediaState = mediaState,
                                            albumsState = albumsState,
                                            metadataState = metadataState,
                                            sharedTransitionScope = this@SharedTransitionLayout,
                                            animatedContentScope = this
                                        )
                                    }
                                }
                            }
                        }
                        BackHandler {
                            finish()
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyShowWhenLockedForIntent(reviewIntent = intent)
        recreate()
    }

    override fun onResume() {
        super.onResume()
        if (!isDeviceLocked()) {
            showWhenLockedForCurrentIntent = false
        }
        setShowWhenLocked(showWhenLockedForCurrentIntent)
    }

    private fun isDeviceLocked(): Boolean {
        val keyguardManager = getSystemService(KeyguardManager::class.java)
        return keyguardManager?.isDeviceLocked == true
    }

    private fun applyShowWhenLockedForIntent(reviewIntent: Intent) {
        showWhenLockedForCurrentIntent = isReviewSecureAction(reviewIntent = reviewIntent) &&
                isDeviceLocked()
        setShowWhenLocked(showWhenLockedForCurrentIntent)
    }

    private fun isReviewAction(reviewIntent: Intent): Boolean {
        return reviewIntent.action == MediaStore.ACTION_REVIEW ||
                reviewIntent.action == MediaStore.ACTION_REVIEW_SECURE ||
                reviewIntent.action == CAMERA_ACTION_REVIEW
    }

    private fun isReviewSecureAction(reviewIntent: Intent): Boolean {
        return reviewIntent.action == MediaStore.ACTION_REVIEW_SECURE
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
