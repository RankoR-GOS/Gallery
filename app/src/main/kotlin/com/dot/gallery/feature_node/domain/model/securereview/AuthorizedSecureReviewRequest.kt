package com.dot.gallery.feature_node.domain.model.securereview

import android.net.Uri
import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList

@Immutable
data class AuthorizedSecureReviewRequest(
    val uris: ImmutableList<Uri>,
) {
    init {
        require(uris.isNotEmpty()) { "Secure review requires a primary URI" }
    }
}
