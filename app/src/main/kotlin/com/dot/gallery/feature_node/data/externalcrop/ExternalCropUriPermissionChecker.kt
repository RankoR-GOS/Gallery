package com.dot.gallery.feature_node.data.externalcrop

import android.Manifest
import android.app.ComponentCaller
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.os.Process
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject

internal interface ExternalCropUriPermissionChecker {

    fun canReadContentUri(
        uri: Uri,
        caller: ComponentCaller,
    ): Boolean

    fun canWriteContentUri(uri: Uri, caller: ComponentCaller): Boolean

    fun canReadFileUri(
        uri: Uri,
        caller: ComponentCaller,
    ): Boolean
}

internal class ComponentCallerExternalCropUriPermissionChecker @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val packageManager: PackageManager,
) : ExternalCropUriPermissionChecker {

    override fun canReadContentUri(
        uri: Uri,
        caller: ComponentCaller,
    ): Boolean {
        return hasContentUriPermission(
            caller = caller,
            uri = uri,
            modeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
    }

    /**
     * The caller's own write access to the output uri cannot be verified, so this only allowlists
     * the authority and confirms the row is reachable from here.
     *
     * The output uri arrives in [android.provider.MediaStore.EXTRA_OUTPUT], a plain extra, so it is
     * never part of the launch grant set that [ComponentCaller.checkContentUriPermission] accepts —
     * that method throws [IllegalArgumentException] for anything not passed via `Intent#getData`,
     * `EXTRA_STREAM` or `Intent#getClipData`. Uid-based [Context.checkUriPermission] is no
     * substitute either: it consults explicit uri grants and the provider's manifest permission
     * only, not MediaProvider's ownership model, so it denies media uris even for our own uid.
     *
     * Known risk, accepted to keep the legacy `ACTION_CROP` contract working: a hostile caller can
     * point [android.provider.MediaStore.EXTRA_OUTPUT] at media it cannot write itself and have us
     * overwrite it. The exposure is bounded — the authority allowlist keeps it inside shared media,
     * and nothing is written until the user confirms the crop in our own ui.
     */
    override fun canWriteContentUri(
        uri: Uri,
        caller: ComponentCaller,
    ): Boolean {
        if (caller.uid == Process.myUid()) {
            return true
        }

        return isMediaStoreUri(uri) && isReachable(uri)
    }

    private fun isMediaStoreUri(uri: Uri): Boolean {
        return uri.authority == MediaStore.AUTHORITY
    }

    /**
     * Deliberately probes with [ContentResolver.getType] rather than opening the descriptor: `"w"`
     * truncates the target on some providers, which would destroy the output before the user has
     * even confirmed the crop. A write that fails later is handled by the save path, which returns
     * no result intent and cancels.
     */
    private fun isReachable(uri: Uri): Boolean {
        return try {
            context.contentResolver.getType(uri) != null
        } catch (_: SecurityException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    override fun canReadFileUri(
        uri: Uri,
        caller: ComponentCaller,
    ): Boolean {
        return canReadExternalImages(caller) && isAllowedExternalCropFileSource(uri)
    }

    private fun hasContentUriPermission(
        caller: ComponentCaller,
        uri: Uri,
        modeFlags: Int,
    ): Boolean {
        if (caller.uid == Process.myUid()) {
            return true
        }

        return try {
            caller.checkContentUriPermission(uri, modeFlags) == PackageManager.PERMISSION_GRANTED
        } catch (_: IllegalArgumentException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    private fun canReadExternalImages(caller: ComponentCaller): Boolean {
        if (caller.uid == Process.myUid()) {
            return true
        }

        val callerPackageName = caller.getPackage() ?: return false

        return when {
            hasReadMediaImagesPermission(callerPackageName) -> true
            hasReadExternalStoragePermission(callerPackageName) -> true
            hasManageExternalStoragePermission(callerPackageName) -> true

            else -> false
        }
    }

    private fun hasReadMediaImagesPermission(callerPackageName: String): Boolean {
        return hasPackagePermission(
            packageName = callerPackageName,
            permission = Manifest.permission.READ_MEDIA_IMAGES,
        )
    }

    private fun hasReadExternalStoragePermission(callerPackageName: String): Boolean {
        return hasPackagePermission(
            packageName = callerPackageName,
            permission = Manifest.permission.READ_EXTERNAL_STORAGE,
        )
    }

    private fun hasManageExternalStoragePermission(callerPackageName: String): Boolean {
        return hasPackagePermission(
            packageName = callerPackageName,
            permission = Manifest.permission.MANAGE_EXTERNAL_STORAGE,
        )
    }

    private fun isAllowedExternalCropFileSource(uri: Uri): Boolean {
        return uri
            .path
            .takeIf { uri.scheme == ContentResolver.SCHEME_FILE }
            ?.let(::isAllowedExternalCropFileSourcePath)
            ?: false
    }

    private fun isAllowedExternalCropFileSourcePath(path: String): Boolean {
        return getCanonicalFile(path)
            ?.let(::isInExternalCropFileSourceDirectory)
            ?: false
    }

    private fun getCanonicalFile(path: String): File? {
        return try {
            File(path).canonicalFile
        } catch (_: Exception) {
            null
        }
    }

    private fun isInExternalCropFileSourceDirectory(sourceFile: File): Boolean {
        return getExternalCropFileSourceDirectories().any { directory ->
            isSameOrChildOf(
                sourceFile = sourceFile,
                parent = directory,
            )
        }
    }

    private fun getExternalCropFileSourceDirectories(): List<File> {
        val directories = listOf(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
        )

        return directories.mapNotNull { directory ->
            try {
                directory.canonicalFile
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun isSameOrChildOf(sourceFile: File, parent: File): Boolean {
        val parentPath = parent.path.trimEnd(File.separatorChar)
        val filePath = sourceFile.path
        return filePath == parentPath || filePath.startsWith("$parentPath${File.separator}")
    }

    private fun hasPackagePermission(
        packageName: String,
        permission: String,
    ): Boolean {
        return packageManager.checkPermission(permission, packageName) ==
                PackageManager.PERMISSION_GRANTED
    }
}
