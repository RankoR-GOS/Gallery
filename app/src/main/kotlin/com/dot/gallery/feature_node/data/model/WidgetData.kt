package com.dot.gallery.feature_node.data.model

import kotlinx.serialization.Serializable

@Serializable
data class WidgetData(
    val widgetId: Int,
    val type: WidgetType,
    val mediaUris: List<String>,
)

@Serializable
enum class WidgetType {
    SINGLE,
    GRID,
}
