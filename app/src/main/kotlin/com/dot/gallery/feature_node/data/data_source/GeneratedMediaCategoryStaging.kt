package com.dot.gallery.feature_node.data.data_source

import androidx.room.Entity

@Entity(
    tableName = "generated_media_category_staging",
    primaryKeys = ["generationId", "mediaId", "categoryId"],
)
data class GeneratedMediaCategoryStaging(
    val generationId: String,
    val mediaId: Long,
    val categoryId: Long,
    val similarityScore: Float,
    val addedAt: Long,
)
