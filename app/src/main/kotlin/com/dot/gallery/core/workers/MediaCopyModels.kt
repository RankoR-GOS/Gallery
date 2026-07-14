package com.dot.gallery.core.workers

import android.net.Uri

internal data class MediaCopyRequest(
    val sourceUri: Uri,
    val destinationPath: String,
)

internal data class MediaCopyBatch(
    val tag: String,
    val workRequestCount: Int,
    val itemCount: Int,
)

internal sealed interface MediaCopyBatchStatus {

    data class Copying(val progress: Float) : MediaCopyBatchStatus

    data class Finished(
        val copiedCount: Int,
        val failedCount: Int,
        val successful: Boolean,
    ) : MediaCopyBatchStatus

    data object Unavailable : MediaCopyBatchStatus
}
