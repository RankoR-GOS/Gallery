package com.dot.gallery.feature_node.domain.model

internal data class AiMediaAnalysisWorkState(
    val analysisProgress: Float? = null,
    val isAnalysisActive: Boolean = false,
    val categoryProgress: Float = 0f,
    val categoryStatus: String = "",
    val isCategoryActive: Boolean = false,
)
