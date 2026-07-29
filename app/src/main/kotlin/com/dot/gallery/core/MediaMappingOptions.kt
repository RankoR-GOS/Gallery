package com.dot.gallery.core

import com.dot.gallery.feature_node.data.util.MediaGroupType

internal data class MediaMappingOptions(
    val groupByMonth: Boolean,
    val groupSimilarMedia: Boolean,
    val enabledGroupTypes: Set<MediaGroupType>,
    val defaultDateFormat: String,
    val extendedDateFormat: String,
    val weeklyDateFormat: String,
)
