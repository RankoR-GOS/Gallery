package com.dot.gallery.feature_node.presentation.setup

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PermMedia
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.dot.gallery.BuildConfig
import com.dot.gallery.R
import com.dot.gallery.core.Constants
import com.dot.gallery.core.Settings.Misc.rememberIsMediaManager
import com.dot.gallery.core.presentation.components.SetupButton
import com.dot.gallery.core.presentation.components.SetupWizard
import com.dot.gallery.core.util.hasMediaAccess
import com.dot.gallery.feature_node.presentation.common.components.OptionItem
import com.dot.gallery.feature_node.presentation.common.components.OptionLayout
import com.dot.gallery.feature_node.presentation.util.launchManageMedia
import com.dot.gallery.feature_node.presentation.util.tryStartActivity
import com.dot.gallery.ui.theme.GalleryTheme
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.google.accompanist.permissions.rememberPermissionState

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun SetupScreen(modifier: Modifier = Modifier, onPermissionGranted: () -> Unit = {}) {
    val context = LocalContext.current
    val resources = LocalResources.current
    var accessDenied by rememberSaveable { mutableStateOf(false) }
    val mediaPermissions = rememberMultiplePermissionsState(Constants.PERMISSIONS) { result ->
        accessDenied = !hasMediaAccess(
            grantedPermissions = result.filterValues { it }.keys,
        )
    }
    LaunchedEffect(mediaPermissions.hasMediaAccess) {
        if (mediaPermissions.hasMediaAccess) {
            onPermissionGranted()
        }
    }
    var useMediaManager by rememberIsMediaManager()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        useMediaManager = MediaStore.canManageMedia(context)
    }
    val notificationPermission = rememberPermissionState(Manifest.permission.POST_NOTIFICATIONS)
    val notificationsGranted = notificationPermission.status.isGranted
    val secondaryContainer = MaterialTheme.colorScheme.secondaryContainer
    val onSecondaryContainer = MaterialTheme.colorScheme.onSecondaryContainer
    val optionalPermissions = remember(
        resources, useMediaManager, notificationsGranted, secondaryContainer, onSecondaryContainer,
    ) {
        mutableStateListOf(
            OptionItem(
                icon = Icons.Rounded.Notifications,
                text = resources.getString(R.string.post_notifications),
                summary = when {
                    notificationsGranted -> resources.getString(R.string.granted)
                    else -> resources.getString(R.string.post_notifications_summary)
                },
                enabled = !notificationsGranted,
                onClick = { notificationPermission.launchPermissionRequest() },
                containerColor = secondaryContainer,
                contentColor = onSecondaryContainer,
            ),
            OptionItem(
                icon = Icons.Rounded.PermMedia,
                text = resources.getString(R.string.setup_media_management_title),
                summary = when {
                    useMediaManager -> resources.getString(R.string.granted)
                    else -> resources.getString(R.string.setup_media_management_summary)
                },
                enabled = !useMediaManager,
                onClick = { context.launchManageMedia() },
                containerColor = secondaryContainer,
                contentColor = onSecondaryContainer,
            ),
        )
    }

    SetupWizard(
        modifier = modifier,
        title = stringResource(R.string.welcome),
        subtitle = "${stringResource(R.string.app_name)} v${BuildConfig.VERSION_NAME}",
        contentPadding = 0.dp,
        bottomBar = {
            SetupButton(
                onClick = { (context as? Activity)?.finish() },
                modifier = Modifier.weight(1f),
                applyHorizontalPadding = false,
                applyBottomPadding = false,
                applyInsets = false,
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                text = stringResource(R.string.action_cancel),
            )
            SetupButton(
                onClick = { mediaPermissions.launchMultiplePermissionRequest() },
                modifier = Modifier.weight(1f),
                applyHorizontalPadding = false,
                applyBottomPadding = false,
                applyInsets = false,
                text = stringResource(R.string.action_continue),
            )
        },
        content = {
            PhotoAccessExplanation()
            if (accessDenied) {
                Text(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    text = stringResource(R.string.setup_photo_access_denied),
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(
                    onClick = {
                        context.tryStartActivity(
                            intent = Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null),
                            ),
                            errorMessage = resources.getString(R.string.error_toast),
                        )
                    },
                ) {
                    Text(text = stringResource(R.string.setup_open_settings))
                }
            }
            Text(
                modifier = Modifier.padding(16.dp),
                text = stringResource(R.string.optional),
            )
            OptionLayout(
                modifier = Modifier.fillMaxWidth(),
                optionList = optionalPermissions,
            )
        },
    )
}

@Composable
private fun PhotoAccessExplanation(modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            modifier = Modifier.padding(horizontal = 16.dp),
            text = stringResource(R.string.setup_photo_access_intro),
        )
        Text(
            modifier = Modifier.padding(16.dp),
            text = stringResource(R.string.setup_photo_access_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            modifier = Modifier.padding(horizontal = 16.dp),
            text = stringResource(R.string.setup_photo_access_summary),
        )
        Text(
            modifier = Modifier.padding(16.dp),
            text = stringResource(R.string.setup_photo_location_summary),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PhotoAccessExplanationPreview() {
    GalleryTheme {
        PhotoAccessExplanation()
    }
}
