package com.dot.gallery.core.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import com.dot.gallery.core.Constants
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.MultiplePermissionsState
import com.google.accompanist.permissions.isGranted

internal fun hasMediaAccess(grantedPermissions: Set<String>): Boolean {
    return Manifest.permission.READ_MEDIA_IMAGES in grantedPermissions ||
            Manifest.permission.READ_MEDIA_VIDEO in grantedPermissions ||
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED in grantedPermissions
}

internal fun Context.hasMediaAccess(): Boolean {
    return hasMediaAccess(
        grantedPermissions = Constants.PERMISSIONS
            .filter { permission ->
                checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
            }
            .toSet(),
    )
}

@OptIn(ExperimentalPermissionsApi::class)
internal val MultiplePermissionsState.hasMediaAccess: Boolean
    get() {
        return hasMediaAccess(
            grantedPermissions = permissions.filter { it.status.isGranted }
                .map { it.permission }.toSet(),
        )
    }

internal fun hasFullMediaAccess(grantedPermissions: Set<String>): Boolean {
    return Manifest.permission.READ_MEDIA_IMAGES in grantedPermissions &&
            Manifest.permission.READ_MEDIA_VIDEO in grantedPermissions
}

internal fun Context.hasFullMediaAccess(): Boolean {
    return hasFullMediaAccess(
        grantedPermissions = Constants.PERMISSIONS
            .filter { permission ->
                checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
            }
            .toSet(),
    )
}
