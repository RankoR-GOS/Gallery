package com.dot.gallery.feature_node.data.model

internal data class AiMediaAnalysisPreferences(
    val analysisEnabled: Boolean,
    val categoryClassificationEnabled: Boolean,
    val analysisCleanupPending: Boolean,
    val categoryCleanupPending: Boolean,
)
