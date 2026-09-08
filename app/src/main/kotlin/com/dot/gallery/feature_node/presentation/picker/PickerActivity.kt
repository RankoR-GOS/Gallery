/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dot.gallery.feature_node.presentation.picker

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.dot.gallery.R
import com.dot.gallery.core.Constants
import com.dot.gallery.core.DefaultEventHandler
import com.dot.gallery.core.MediaDistributor
import com.dot.gallery.core.MediaHandler
import com.dot.gallery.core.MediaSelector
import com.dot.gallery.core.MediaSelectorImpl
import com.dot.gallery.core.util.SetupMediaProviders
import com.dot.gallery.core.util.enforceSecureMode
import com.dot.gallery.core.util.hasMediaAccess
import com.dot.gallery.feature_node.data.model.Media
import com.dot.gallery.feature_node.domain.model.UIEvent
import com.dot.gallery.feature_node.domain.util.EventHandler
import com.dot.gallery.feature_node.presentation.picker.components.PickerScreen
import com.dot.gallery.feature_node.presentation.setup.SetupScreen
import com.dot.gallery.ui.theme.GalleryTheme
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class PickerActivityContract(
    private val mediaType: String = "*/*",
    private val allowMultiple: Boolean = true,
) : ActivityResultContract<Any?, List<String>>() {

    override fun createIntent(context: Context, input: Any?): Intent {
        return Intent(context, PickerActivity::class.java).apply {
            type = mediaType
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple)
        }
    }

    override fun parseResult(resultCode: Int, intent: Intent?): List<String> {
        if (resultCode != Activity.RESULT_OK || intent == null) {
            return emptyList()
        }


        return mutableListOf<String>().apply {
            intent.clipData?.let { clipData ->
                for (i in 0 until clipData.itemCount) {
                    add(clipData.getItemAt(i).uri.toString())
                }
            } ?: intent.data?.let { add(it.toString()) }
        }
    }
}

@AndroidEntryPoint
class PickerActivity : FragmentActivity() {

    // Navigation callbacks belong to this activity, not the process-wide gallery handler.
    private val eventHandler: EventHandler = DefaultEventHandler()

    @Inject
    lateinit var mediaDistributor: MediaDistributor

    @Inject
    lateinit var mediaHandler: MediaHandler

    val mediaSelector: MediaSelector = MediaSelectorImpl()

    private val exportAsMedia: Boolean
        get() {
            return callingPackage == packageName && intent.getBooleanExtra(EXPORT_AS_MEDIA, false)
        }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        mediaSelector.clearSelection()
        viewModelStore.clear()
        recreate()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enforceSecureMode()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        enableEdgeToEdge()
        val type = intent.type
        val allowMultiple = intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
        var title = getString(R.string.select)
        title += " " + if (allowMultiple) {
            if (type.pickAny) getString(R.string.photos_and_videos)
            else if (type.pickImage) getString(R.string.photos)
            else getString(R.string.videos)
        } else {
            if (type.pickImage) getString(R.string.photo)
            else if (type.pickVideo) getString(R.string.video)
            else getString(R.string.photos_and_videos)
        }
        setContent {
            LaunchedEffect(Unit) {
                eventHandler.navigateUpAction = { finish() }
            }
            LaunchedEffect(eventHandler) {
                withContext(Dispatchers.Main.immediate) {
                    eventHandler.updaterFlow.collectLatest { event ->
                        when (event) {
                            UIEvent.UpdateDatabase -> {
                            }

                            UIEvent.NavigationUpEvent -> eventHandler.navigateUpAction()
                            is UIEvent.NavigationRouteEvent -> eventHandler.navigateAction(event.route)
                            is UIEvent.ToggleNavigationBarEvent -> eventHandler.toggleNavigationBarAction(
                                event.isVisible
                            )

                            is UIEvent.SetFollowThemeEvent -> eventHandler.setFollowThemeAction(
                                event.followTheme
                            )
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
                    PickerRootScreen(title, type.allowedMedia, allowMultiple)
                }
            }
        }
    }

    @OptIn(ExperimentalPermissionsApi::class)
    @Composable
    fun PickerRootScreen(title: String, allowedMedia: AllowedMedia, allowMultiple: Boolean) {
        val mediaPermissions =
            rememberMultiplePermissionsState(Constants.PERMISSIONS)
        if (!mediaPermissions.hasMediaAccess) {
            SetupScreen()
            return
        }
        LaunchedEffect(mediaPermissions.hasMediaAccess) {
            mediaDistributor.hasPermission.value = true
        }
        PickerScreen(
            title = title,
            allowedMedia = allowedMedia,
            mimeTypes = requestedMimeTypes(intent = intent),
            allowSelection = allowMultiple,
            onClose = ::finish,
            sendMediaAsResult = ::sendMediaAsResult,
            sendMediaAsMediaResult = ::sendMediaAsMediaResult
        )
    }

    private fun sendMediaAsMediaResult(selectedMedia: List<Media>) {
        if (exportAsMedia) {
            val newIntent = Intent().apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                putExtra(MEDIA_LIST, Json.encodeToString(selectedMedia.toTypedArray()))
            }
            setResult(RESULT_OK, newIntent)
            finish()
        }
    }

    private fun sendMediaAsResult(selectedMedia: List<Uri>) {
        if (exportAsMedia || selectedMedia.isEmpty()) return
        val requestIntent = intent
        val mimeTypes = requestedMimeTypes(intent = requestIntent)
        lifecycleScope.launch(Dispatchers.IO) {
            val resultIntent = try {
                check(selectedMedia.all { uri ->
                    matchesPickerMimeType(
                        mimeType = contentResolver.getType(uri),
                        requestedTypes = mimeTypes,
                    )
                })
                Intent().apply {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    data = selectedMedia.first()
                    if (selectedMedia.size > 1) {
                        clipData = ClipData.newUri(contentResolver, null, selectedMedia.first()).apply {
                            selectedMedia.drop(1).forEach { uri -> addItem(ClipData.Item(uri)) }
                        }
                    }
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                null
            }
            withContext(Dispatchers.Main) {
                if (intent !== requestIntent) return@withContext
                when (resultIntent) {
                    null -> Toast.makeText(this@PickerActivity, R.string.error_toast, Toast.LENGTH_SHORT).show()
                    else -> {
                        setResult(RESULT_OK, resultIntent)
                        finish()
                    }
                }
            }
        }
    }

    private val String?.pickImage: Boolean get() = this?.startsWith("image") == true
    private val String?.pickVideo: Boolean get() = this?.startsWith("video") == true
    private val String?.pickAny: Boolean get() = this == "*/*"
    private val String?.allowedMedia: AllowedMedia
        get() = if (pickImage) AllowedMedia.PHOTOS
        else if (pickVideo) AllowedMedia.VIDEOS
        else AllowedMedia.BOTH

    companion object {
        const val EXPORT_AS_MEDIA = "EXPORT_AS_MEDIA"
        const val MEDIA_LIST = "MEDIA_LIST"
    }
}

internal fun requestedMimeTypes(intent: Intent): List<String> {
    val primaryType = Intent.normalizeMimeType(intent.type) ?: "*/*"
    val extraTypes = intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.toList().orEmpty()
    if (extraTypes.isEmpty()) return listOf(primaryType)
    return extraTypes.mapNotNull { type ->
        val extraType = Intent.normalizeMimeType(type) ?: return@mapNotNull null
        when {
            ClipDescription.compareMimeTypes(extraType, primaryType) -> extraType
            ClipDescription.compareMimeTypes(primaryType, extraType) -> primaryType
            else -> null
        }
    }.distinct()
}

internal fun matchesPickerMimeType(mimeType: String?, requestedTypes: List<String>): Boolean {
    val normalizedType = Intent.normalizeMimeType(mimeType) ?: return false
    return requestedTypes.any { requestedType ->
        ClipDescription.compareMimeTypes(normalizedType, requestedType)
    }
}
