package com.dot.gallery.feature_node.data.externalcrop

import android.Manifest
import android.app.ComponentCaller
import android.content.ContentResolver
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.os.Process
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

    override fun canWriteContentUri(
        uri: Uri,
        caller: ComponentCaller,
    ): Boolean {
        return hasContentUriPermission(
            caller = caller,
            uri = uri,
            modeFlags = Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
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
