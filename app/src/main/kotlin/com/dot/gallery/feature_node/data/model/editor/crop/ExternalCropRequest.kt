package com.dot.gallery.feature_node.data.model.editor.crop

import android.net.Uri
import androidx.compose.runtime.Immutable
import com.dot.gallery.feature_node.data.model.editor.SaveFormat

@Immutable
internal data class ExternalCropRequest(
    val sourceUri: Uri,
    val outputUri: Uri?,
    val outputX: Int,
    val outputY: Int,
    val scale: Boolean,
    val scaleUpIfNeeded: Boolean,
    val aspectX: Int,
    val aspectY: Int,
    val returnData: Boolean,
    val outputFormat: String?,
) {
    val saveFormat: SaveFormat
        get() {
            return when (normalizedOutputFormatToken()) {
                FORMAT_PNG, FORMAT_X_PNG, FORMAT_GIF -> SaveFormat.PNG
                FORMAT_WEBP, FORMAT_WEBP_LOSSY -> SaveFormat.WEBP_LOSSY
                FORMAT_WEBP_LOSSLESS -> SaveFormat.WEBP_LOSSLESS
                FORMAT_JPEG, FORMAT_JPG -> SaveFormat.JPEG
                else -> SaveFormat.JPEG
            }
        }

    val aspectRatio: Float?
        get() {
            return when {
                aspectX > 0 && aspectY > 0 -> aspectX.toFloat() / aspectY.toFloat()
                outputX > 0 && outputY > 0 -> outputX.toFloat() / outputY.toFloat()
                else -> null
            }
        }

    private fun normalizedOutputFormatToken(): String? {
        return outputFormat
            ?.trim()
            ?.substringBefore(delimiter = MIME_PARAMETER_DELIMITER)
            ?.trim()
            ?.substringAfterLast(delimiter = MIME_TYPE_DELIMITER)
            ?.substringAfterLast(delimiter = FILE_EXTENSION_DELIMITER)
            ?.lowercase()
    }

    private companion object {
        private const val FILE_EXTENSION_DELIMITER = '.'
        private const val FORMAT_GIF = "gif"
        private const val FORMAT_JPEG = "jpeg"
        private const val FORMAT_JPG = "jpg"
        private const val FORMAT_PNG = "png"
        private const val FORMAT_WEBP = "webp"
        private const val FORMAT_WEBP_LOSSLESS = "webp_lossless"
        private const val FORMAT_WEBP_LOSSY = "webp_lossy"
        private const val FORMAT_X_PNG = "x-png"
        private const val MIME_PARAMETER_DELIMITER = ';'
        private const val MIME_TYPE_DELIMITER = '/'
    }
}
