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
     * The caller must be able to write the output itself. We must never write where the caller could
     * not: the crop ui only ever shows the source, so a user confirming the crop is not consenting
     * to the destination, and a hostile caller pointing the output at media it cannot write would
     * make us its deputy.
     *
     * Which api can establish that depends on the authority.
     *
     * For a non-MediaStore output, [ComponentCaller.checkContentUriPermission] answers, provided the
     * caller also passed the uri via `Intent#getData`, `EXTRA_STREAM` or `Intent#getClipData` with a
     * write grant — that is the launch grant set the method consults. AvatarPicker does exactly
     * that: it puts one uri from its own `FileProvider` in both the intent data and
     * [android.provider.MediaStore.EXTRA_OUTPUT], with
     * [Intent.FLAG_GRANT_WRITE_URI_PERMISSION]. An output uri that arrives *only* as the plain
     * extra is outside the launch grant set, the platform throws [IllegalArgumentException], and we
     * reject — the caller proved nothing, so that is the right answer rather than a shortcoming.
     *
     * For a MediaStore output the launch grant api genuinely cannot answer, because legacy callers
     * pass media uris in the extra alone. Uid-based [Context.checkUriPermission] is no substitute
     * either: it consults explicit uri grants and the provider's manifest permission only, not
     * MediaProvider's ownership model, so it denies media uris even for our own uid. So ownership is
     * resolved directly, from [android.provider.MediaStore.MediaColumns.OWNER_PACKAGE_NAME]. This
     * deliberately narrows the legacy `ACTION_CROP` contract: an output row owned by a third party,
     * or with no recorded owner (a legacy file picked up by the media scanner), is refused, and no
     * generic uri check can widen it back.
     */
    override fun canWriteContentUri(
        uri: Uri,
        caller: ComponentCaller,
    ): Boolean {
        if (caller.uid == Process.myUid()) {
            return true
        }

        return when {
            isMediaStoreUri(uri) -> isReachable(uri) && isOwnedByCallerOrUs(uri, caller)

            else -> hasContentUriPermission(
                caller = caller,
                uri = uri,
                modeFlags = Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }

    private fun isOwnedByCallerOrUs(uri: Uri, caller: ComponentCaller): Boolean {
        val owner = getOwnerPackageName(uri)?.takeIf { it.isNotBlank() } ?: return false

        return owner == context.packageName || owner in getCallerPackageNames(caller)
    }

    private fun getOwnerPackageName(uri: Uri): String? {
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns.OWNER_PACKAGE_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (_: SecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun getCallerPackageNames(caller: ComponentCaller): Set<String> {
        val uidPackages = packageManager.getPackagesForUid(caller.uid)?.toSet().orEmpty()

        return uidPackages + setOfNotNull(caller.getPackage())
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
