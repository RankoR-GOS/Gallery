package com.dot.gallery.feature_node.presentation.mediaview.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.CopyAll
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.dot.gallery.R
import com.dot.gallery.core.Settings
import com.dot.gallery.core.Settings.Misc.rememberAllowBlur
import com.dot.gallery.feature_node.data.model.Media
import com.dot.gallery.feature_node.data.util.canMakeActions
import com.dot.gallery.feature_node.data.util.getUri
import com.dot.gallery.feature_node.data.util.isImage
import com.dot.gallery.feature_node.data.util.isLocalContent
import com.dot.gallery.feature_node.data.util.isVideo
import com.dot.gallery.feature_node.domain.model.AlbumState
import com.dot.gallery.feature_node.presentation.collection.CollectionViewModel
import com.dot.gallery.feature_node.presentation.collection.components.AddToCollectionSheet
import com.dot.gallery.feature_node.presentation.exif.CopyMediaSheet
import com.dot.gallery.feature_node.presentation.exif.MoveMediaSheet
import com.dot.gallery.feature_node.presentation.util.LocalHazeState
import com.dot.gallery.feature_node.presentation.util.copyMediaToClipboard
import com.dot.gallery.feature_node.presentation.util.launchEditImageIntent
import com.dot.gallery.feature_node.presentation.util.launchEditIntent
import com.dot.gallery.feature_node.presentation.util.launchOpenWithIntent
import com.dot.gallery.feature_node.presentation.util.launchUseAsIntent
import com.dot.gallery.feature_node.presentation.util.rememberAppBottomSheetState
import com.dot.gallery.feature_node.presentation.util.shareMedia
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import kotlinx.coroutines.launch

@Composable
fun <T : Media> MediaViewSheetActions(
    media: T,
    albumsState: State<AlbumState>,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val copySheetState = rememberAppBottomSheetState()
    val moveSheetState = rememberAppBottomSheetState()
    var showCollectionSheet by rememberSaveable { mutableStateOf(false) }

    val defaultEditor by Settings.Misc.rememberDefaultImageEditor()

    val shareText = stringResource(R.string.share)
    val copyToClipboardText = stringResource(R.string.copy_to_clipboard)
    val openWithText = stringResource(R.string.open_with)
    val useAsText = stringResource(R.string.use_as)
    val copyText = stringResource(R.string.copy)
    val moveText = stringResource(R.string.move)
    val editText = stringResource(R.string.edit)
    val addToCollectionText = stringResource(R.string.add_to_collection)

    val actions = buildList {
        add(
            ActionGridItem(
                icon = Icons.Outlined.Share,
                text = shareText,
                onClick = {
                    scope.launch {
                        context.shareMedia(media)
                    }
                },
            )
        )
        add(
            ActionGridItem(
                icon = Icons.Outlined.ContentCopy,
                text = copyToClipboardText,
                onClick = {
                    context.copyMediaToClipboard(media)
                },
            )
        )
        add(
            ActionGridItem(
                icon = Icons.AutoMirrored.Outlined.OpenInNew,
                text = if (media.isVideo) openWithText else useAsText,
                onClick = {
                    scope.launch {
                        if (media.isVideo) {
                            context.launchOpenWithIntent(media)
                        } else {
                            context.launchUseAsIntent(media)
                        }
                    }
                },
            )
        )
        if (albumsState.value.albums.isNotEmpty() && media.canMakeActions) {
            add(
                ActionGridItem(
                    icon = Icons.Outlined.CopyAll,
                    text = copyText,
                    onClick = { scope.launch { copySheetState.show() } },
                )
            )
            add(
                ActionGridItem(
                    icon = Icons.AutoMirrored.Outlined.DriveFileMove,
                    text = moveText,
                    onClick = { scope.launch { moveSheetState.show() } },
                )
            )
        }
        add(
            ActionGridItem(
                icon = Icons.Outlined.Edit,
                text = editText,
                onClick = {
                    if (media.isImage && defaultEditor != Settings.Misc.EDITOR_BUILTIN) {
                        val launched = try {
                            context.launchEditImageIntent(
                                packageName = defaultEditor,
                                uri = media.getUri(),
                                showError = false,
                            )
                        } catch (_: Exception) {
                            false
                        }
                        if (!launched) {
                            context.launchEditIntent(media)
                        }
                    } else {
                        context.launchEditIntent(media)
                    }
                },
            )
        )
        if (media.isLocalContent && media.canMakeActions) {
            add(
                ActionGridItem(
                    icon = Icons.Outlined.Collections,
                    text = addToCollectionText,
                    onClick = { showCollectionSheet = true },
                )
            )
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        actions.chunked(size = 2).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowItems.forEach { item ->
                    ActionGridCell(
                        modifier = Modifier.weight(1f),
                        icon = item.icon,
                        text = item.text,
                        enabled = item.enabled,
                        onClick = item.onClick,
                    )
                }
                if (rowItems.size < 2) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }

    if (albumsState.value.albums.isNotEmpty() && media.canMakeActions) {
        CopyMediaSheet(
            sheetState = copySheetState,
            mediaList = listOf(media),
            albumsState = albumsState,
            onFinish = { },
        )
        MoveMediaSheet(
            sheetState = moveSheetState,
            mediaList = listOf(media),
            albumState = albumsState,
            onFinish = { },
        )
    }

    if (media.isLocalContent && media.canMakeActions) {
        val collectionViewModel = hiltViewModel<CollectionViewModel>()
        AddToCollectionSheet(
            visible = showCollectionSheet,
            collections = albumsState.value.collections,
            onDismiss = { showCollectionSheet = false },
            onCollectionSelected = { collectionId ->
                collectionViewModel.addMediaToCollection(collectionId, media.id)
            },
            onCreateAndAdd = { name ->
                collectionViewModel.createCollectionAndAddMedia(name, listOf(media.id))
            },
        )
    }
}

private data class ActionGridItem(
    val icon: ImageVector,
    val text: String,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
private fun ActionGridCell(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    text: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val isBlurEnabled by rememberAllowBlur()
    val surfaceColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val backgroundModifier = remember(isBlurEnabled) {
        if (!isBlurEnabled) {
            Modifier.background(
                color = surfaceColor,
                shape = RoundedCornerShape(16.dp),
            )
        } else {
            Modifier
        }
    }
    val hazeStyle = HazeMaterials.regular(
        containerColor = MaterialTheme.colorScheme.surface,
    )
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .then(backgroundModifier)
            .hazeEffect(
                state = LocalHazeState.current,
                style = hazeStyle,
            )
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f)
            .padding(horizontal = 12.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
