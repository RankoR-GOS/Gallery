package com.dot.gallery.feature_node.data.model

import androidx.room.Entity

@Entity(tableName = "media_version", primaryKeys = ["version"])
data class MediaVersion(
    val version: String
)
