package com.dot.gallery.feature_node.domain.repository

import android.content.Intent
import android.net.Uri
import com.dot.gallery.feature_node.domain.model.editor.crop.CropImage
import com.dot.gallery.feature_node.domain.model.editor.crop.ExternalCropRequest
import com.dot.gallery.feature_node.domain.model.editor.crop.NormalizedCropRect

internal interface ExternalCropRepository {

    suspend fun loadImage(uri: Uri): CropImage?

    suspend fun saveCropResult(
        request: ExternalCropRequest,
        image: CropImage,
        normalizedRect: NormalizedCropRect,
    ): Intent?
}
