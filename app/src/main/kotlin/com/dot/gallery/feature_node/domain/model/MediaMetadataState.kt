package com.dot.gallery.feature_node.domain.model

import com.dot.gallery.feature_node.data.model.MediaMetadata
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentMapOf

data class MediaMetadataState(
    val metadata: List<MediaMetadata> = emptyList(),
    val metadataById: ImmutableMap<Long, MediaMetadata> = persistentMapOf(),
    val isLoading: Boolean = false,
    val isLoadingProgress: Int = 0,
)
