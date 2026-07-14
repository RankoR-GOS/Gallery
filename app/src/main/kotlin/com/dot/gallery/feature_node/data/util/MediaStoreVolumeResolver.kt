package com.dot.gallery.feature_node.data.util

import android.os.Environment
import android.provider.MediaStore

/**
 * Resolves a destination path (absolute or relative) into a pair of
 * (MediaStore volume name, relative path).
 *
 * - Absolute paths like `/storage/emulated/0/DCIM/Camera/` resolve to
 *   `(VOLUME_EXTERNAL_PRIMARY, "DCIM/Camera/")`
 * - SD card paths like `/storage/71F8-2C0A/DCIM/Camera/` resolve to
 *   `("71f8-2c0a", "DCIM/Camera/")`
 * - Relative paths like `DCIM/Camera/` resolve to
 *   `(VOLUME_EXTERNAL_PRIMARY, "DCIM/Camera/")`
 */
internal fun resolveMediaStoreVolume(path: String): Pair<String, String> {
    val primaryStorage = Environment.getExternalStorageDirectory().absolutePath.trimEnd('/')

    return when {
        path.startsWith("$primaryStorage/") -> {
            val relativePath = path.removePrefix("$primaryStorage/")
            MediaStore.VOLUME_EXTERNAL_PRIMARY to relativePath
        }

        path.startsWith("/storage/") -> {
            val afterStorage = path.removePrefix("/storage/")
            val volumeId = afterStorage.substringBefore("/").lowercase()
            val relativePath = afterStorage.substringAfter("/", "")
            volumeId to relativePath
        }

        else -> {
            MediaStore.VOLUME_EXTERNAL_PRIMARY to path
        }
    }
}
