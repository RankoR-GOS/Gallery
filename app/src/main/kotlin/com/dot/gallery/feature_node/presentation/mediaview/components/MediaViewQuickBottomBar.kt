package com.dot.gallery.feature_node.presentation.mediaview.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.dot.gallery.R
import com.dot.gallery.core.LocalEventHandler
import com.dot.gallery.core.LocalMediaHandler
import com.dot.gallery.core.Settings.Misc.rememberAllowBlur
import com.dot.gallery.core.Settings.Misc.rememberShowFavoriteButton
import com.dot.gallery.core.setFollowTheme
import com.dot.gallery.core.util.SdkCompat
import com.dot.gallery.feature_node.data.model.Media
import com.dot.gallery.feature_node.data.util.canMakeActions
import com.dot.gallery.feature_node.data.util.isTrashed
import com.dot.gallery.feature_node.data.util.isVideo
import com.dot.gallery.feature_node.data.util.readUriOnly
import com.dot.gallery.feature_node.presentation.mediaview.components.actionbuttons.CopyToClipboardButton
import com.dot.gallery.feature_node.presentation.mediaview.components.actionbuttons.EditButton
import com.dot.gallery.feature_node.presentation.mediaview.components.actionbuttons.FavoriteButton
import com.dot.gallery.feature_node.presentation.mediaview.components.actionbuttons.MediaViewButton
import com.dot.gallery.feature_node.presentation.mediaview.components.actionbuttons.OpenAsButton
import com.dot.gallery.feature_node.presentation.mediaview.components.actionbuttons.ShareButton
import com.dot.gallery.feature_node.presentation.mediaview.components.actionbuttons.TrashButton
import com.dot.gallery.feature_node.presentation.mediaview.rememberedDerivedState
import com.dot.gallery.feature_node.presentation.util.rememberActivityResult
import kotlinx.coroutines.launch

@Composable
fun <T : Media> MediaViewQuickBottomBar(
    currentMedia: T?,
    showDeleteButton: Boolean,
    enabled: Boolean,
    isImageDark: Boolean = false,
    autoContrast: Boolean = false
) {
    val handler = LocalMediaHandler.current
    val allowBlur by rememberAllowBlur()
    val isVideo by rememberedDerivedState(currentMedia) {
        currentMedia?.isVideo ?: false
    }
    val isDarkTheme = com.dot.gallery.ui.theme.isDarkTheme()
    val followTheme = remember(allowBlur, isVideo, isDarkTheme, autoContrast, isImageDark) {
        if (autoContrast) !isImageDark
        else !allowBlur && !isVideo
    }
    val contentColor by animateColorAsState(
        targetValue = when {
            autoContrast -> if (isImageDark) Color.White else Color.Black
            followTheme -> MaterialTheme.colorScheme.onSurface
            else -> Color.White
        },
        label = "BottomBarContentColor"
    )
    val eventHandler = LocalEventHandler.current
    LaunchedEffect(followTheme) {
        eventHandler.setFollowTheme(followTheme)
    }
    CompositionLocalProvider(LocalContentColor provides contentColor) {
        if (currentMedia != null) {
            if (currentMedia.isTrashed) {
                val scope = rememberCoroutineScope()
                val result = rememberActivityResult()
                // Restore Component
                MediaViewButton(
                    currentMedia = currentMedia,
                    imageVector = Icons.Outlined.RestoreFromTrash,
                    title = stringResource(id = R.string.trash_restore),
                    followTheme = followTheme,
                    enabled = enabled
                ) {
                    scope.launch {
                        handler.trashMedia(result = result, arrayListOf(it), trash = false)
                    }
                }
                // Delete Component
                MediaViewButton(
                    currentMedia = currentMedia,
                    imageVector = Icons.Outlined.DeleteOutline,
                    title = stringResource(id = R.string.trash_delete),
                    enabled = enabled
                ) {
                    scope.launch {
                        handler.deleteMedia(result = result, arrayListOf(it))
                    }
                }
            } else {
                // Share Component
                ShareButton(
                    media = currentMedia,
                    enabled = enabled,
                    followTheme = followTheme
                )
                // Copy to Clipboard
                CopyToClipboardButton(
                    media = currentMedia,
                    enabled = enabled,
                    followTheme = followTheme
                )
                // Favorite Component
                val showFavoriteButton by rememberShowFavoriteButton()
                if (showFavoriteButton && currentMedia.canMakeActions && SdkCompat.supportsFavorites) {
                    FavoriteButton(
                        media = currentMedia,
                        enabled = enabled,
                        followTheme = followTheme
                    )
                }
                if (currentMedia.readUriOnly) {
                    OpenAsButton(
                        media = currentMedia,
                        enabled = enabled,
                        followTheme = followTheme
                    )
                }
                // Edit
                EditButton(
                    media = currentMedia,
                    enabled = enabled,
                    followTheme = followTheme
                )
                // Trash Component
                if (showDeleteButton) {
                    TrashButton(
                        media = currentMedia,
                        enabled = enabled,
                        followTheme = followTheme
                    )
                }
            }
        }
    }
}
