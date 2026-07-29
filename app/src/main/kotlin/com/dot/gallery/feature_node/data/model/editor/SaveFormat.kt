package com.dot.gallery.feature_node.data.model.editor

import android.graphics.Bitmap.CompressFormat
import androidx.annotation.Keep

@Keep
sealed interface SaveFormat {

    val compressFormat: CompressFormat
    val fileExtension: String
    val mimeType: String

    data object PNG : SaveFormat {
        override val compressFormat: CompressFormat = CompressFormat.PNG
        override val fileExtension: String = "png"
        override val mimeType: String = "image/png"
    }

    data object JPEG : SaveFormat {
        override val compressFormat: CompressFormat = CompressFormat.JPEG
        override val fileExtension: String = "jpg"
        override val mimeType: String = "image/jpeg"
    }

    data object WEBP_LOSSLESS : SaveFormat {
        override val compressFormat: CompressFormat = CompressFormat.WEBP_LOSSLESS
        override val fileExtension: String = "webp"
        override val mimeType: String = "image/webp"
    }

    data object WEBP_LOSSY : SaveFormat {
        override val compressFormat: CompressFormat = CompressFormat.WEBP_LOSSY
        override val fileExtension: String = "webp"
        override val mimeType: String = "image/webp"
    }
}
