/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dot.gallery.feature_node.presentation.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.dot.gallery.R
import com.dot.gallery.core.Constants
import com.dot.gallery.core.MediaDistributor
import com.dot.gallery.core.MediaHandler
import com.dot.gallery.core.MediaSelector
import com.dot.gallery.core.MediaSelectorImpl
import com.dot.gallery.core.util.SetupMediaProviders
import com.dot.gallery.feature_node.data.model.WidgetType
import com.dot.gallery.feature_node.data.repository.WidgetRepository
import com.dot.gallery.feature_node.domain.model.UIEvent
import com.dot.gallery.feature_node.domain.util.EventHandler
import com.dot.gallery.feature_node.presentation.picker.AllowedMedia
import com.dot.gallery.feature_node.presentation.picker.components.PickerScreen
import com.dot.gallery.feature_node.presentation.widget.data.WidgetBitmapLoader
import com.dot.gallery.ui.theme.GalleryTheme
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@AndroidEntryPoint
class WidgetConfigActivity : FragmentActivity() {

    @Inject
    lateinit var eventHandler: EventHandler

    @Inject
    lateinit var mediaDistributor: MediaDistributor

    @Inject
    lateinit var mediaHandler: MediaHandler

    @Inject
    internal lateinit var widgetRepository: WidgetRepository

    val mediaSelector: MediaSelector = MediaSelectorImpl()

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var isReconfigure = false

    private val widgetType: WidgetType by lazy {
        // Determine widget type from the provider info
        val appWidgetManager = AppWidgetManager.getInstance(this)
        val info = appWidgetManager.getAppWidgetInfo(appWidgetId)
        if (info?.provider?.className?.contains("GridMediaWidgetReceiver") == true) {
            WidgetType.GRID
        } else {
            WidgetType.SINGLE
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Get the widget ID from the intent
        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        // Detect reconfigure: widget already has saved data
        isReconfigure = widgetRepository.getWidgetData(widgetId = appWidgetId) != null

        // For initial config, set CANCELED so backing out doesn't add the widget.
        // For reconfigure, the widget already exists — just finish normally on back.
        if (!isReconfigure) {
            setResult(Activity.RESULT_CANCELED)
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        enableEdgeToEdge()

        val allowMultiple = widgetType == WidgetType.GRID
        val title = if (allowMultiple) {
            getString(R.string.widget_select_photos)
        } else {
            getString(R.string.widget_select_photo)
        }

        setContent {
            LaunchedEffect(Unit) {
                eventHandler.navigateUpAction = { finish() }
            }
            LaunchedEffect(eventHandler) {
                withContext(Dispatchers.Main.immediate) {
                    eventHandler.updaterFlow.collectLatest { event ->
                        when (event) {
                            UIEvent.UpdateDatabase -> {}
                            UIEvent.NavigationUpEvent -> eventHandler.navigateUpAction()
                            is UIEvent.NavigationRouteEvent -> eventHandler.navigateAction(event.route)
                            is UIEvent.ToggleNavigationBarEvent -> eventHandler.toggleNavigationBarAction(event.isVisible)
                            is UIEvent.SetFollowThemeEvent -> eventHandler.setFollowThemeAction(event.followTheme)
                        }
                    }
                }
            }
            SetupMediaProviders(
                eventHandler = eventHandler,
                mediaDistributor = mediaDistributor,
                mediaHandler = mediaHandler,
                mediaSelector = mediaSelector
            ) {
                GalleryTheme {
                    WidgetConfigScreen(
                        title = title,
                        allowMultiple = allowMultiple
                    )
                }
            }
        }
    }

    @OptIn(ExperimentalPermissionsApi::class)
    @Composable
    private fun WidgetConfigScreen(
        title: String,
        allowMultiple: Boolean
    ) {
        val mediaPermissions = rememberMultiplePermissionsState(Constants.PERMISSIONS)
        if (!mediaPermissions.allPermissionsGranted) {
            LaunchedEffect(Unit) {
                mediaPermissions.launchMultiplePermissionRequest()
            }
        }
        PickerScreen(
            title = title,
            allowedMedia = AllowedMedia.PHOTOS,
            allowSelection = allowMultiple,
            onClose = ::finish,
            sendMediaAsResult = ::onMediaSelected,
            sendMediaAsMediaResult = { /* not used */ }
        )
    }

    private fun onMediaSelected(selectedMedia: List<Uri>) {
        if (selectedMedia.isEmpty()) {
            finish()
            return
        }

        val applicationContext = applicationContext
        val widgetId = appWidgetId
        val selectedWidgetType = widgetType
        val distinctMedia = selectedMedia.distinct()
        lifecycleScope.launch(Dispatchers.IO) {
            val saved = widgetRepository.replaceWidgetData(
                widgetId = widgetId,
                type = selectedWidgetType,
                uris = distinctMedia,
            )
            if (!saved) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@WidgetConfigActivity,
                        R.string.widget_save_failed,
                        Toast.LENGTH_LONG,
                    ).show()
                }
                return@launch
            }

            if (isReconfigure) {
                WidgetBitmapLoader.clearCache(applicationContext, widgetId)
            }

            distinctMedia.forEachIndexed { index, uri ->
                WidgetBitmapLoader.loadAndCacheBitmap(applicationContext, uri, widgetId, index)
            }

            val appWidgetManager = AppWidgetManager.getInstance(applicationContext)
            when (selectedWidgetType) {
                WidgetType.SINGLE -> SingleMediaWidgetReceiver.updateWidget(
                    context = applicationContext,
                    appWidgetManager = appWidgetManager,
                    appWidgetId = widgetId,
                )
                WidgetType.GRID -> GridMediaWidgetReceiver.updateWidget(
                    context = applicationContext,
                    appWidgetManager = appWidgetManager,
                    appWidgetId = widgetId,
                    uris = distinctMedia,
                )
            }

            val resultValue = Intent().apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            }
            withContext(Dispatchers.Main) {
                setResult(Activity.RESULT_OK, resultValue)
                finish()
            }
        }
    }
}
