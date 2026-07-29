package com.dot.gallery.feature_node.data.data_source

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "category_classification_generation")
data class CategoryClassificationGeneration(
    @PrimaryKey val singletonId: Int = SINGLETON_ID,
    val generationId: String,
) {
    companion object {
        const val SINGLETON_ID = 0
    }
}
