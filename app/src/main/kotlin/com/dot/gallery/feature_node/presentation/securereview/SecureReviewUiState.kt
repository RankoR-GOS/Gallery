package com.dot.gallery.feature_node.presentation.securereview

import androidx.compose.runtime.Immutable
import com.dot.gallery.feature_node.data.model.Media
import kotlinx.collections.immutable.ImmutableList

@Immutable
internal sealed interface SecureReviewUiState {

    data object Loading : SecureReviewUiState

    @Immutable
    data class Ready(
        val media: ImmutableList<Media.UriMedia>,
    ) : SecureReviewUiState
}
