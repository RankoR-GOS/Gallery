package com.dot.gallery.feature_node.presentation.mediaview

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * A rotation the user previewed by long pressing, but has not committed to disk yet.
 *
 * The angle is tagged with the media it belongs to. Grouped media share a single pager page, so
 * page-scoped rotation state would survive a member switch and let one photo's angle be written
 * to another.
 */
@Parcelize
data class PendingRotation(
    val mediaId: Long,
    val degrees: Int
) : Parcelable {

    /** The preview angle for [id], or 0 when this pending rotation belongs to another item. */
    fun degreesFor(id: Long): Int {
        return if (id == mediaId) degrees else 0
    }

    companion object {
        /**
         * A pending rotation of [degrees] for [mediaId], normalized into 0..359, or null once the
         * accumulated angle is back to zero and there is nothing left to commit.
         */
        fun of(mediaId: Long, degrees: Int): PendingRotation? {
            val normalized = ((degrees % 360) + 360) % 360
            return if (normalized == 0) null else PendingRotation(mediaId, normalized)
        }
    }
}
