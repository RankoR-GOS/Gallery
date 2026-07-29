/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dot.gallery.feature_node.data.model

import androidx.compose.runtime.Immutable
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Entity(tableName = "collections")
@Immutable
@Serializable
data class Collection(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val label: String,
    val coverMediaId: Long? = null,
    val isPinned: Boolean = false,
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * A collection row plus its resolved thumbnail, as returned by
 * `CollectionDao.getCollectionsWithCount`. The thumbnail column is computed by that query rather
 * than stored: it is `coverMediaId` when set, otherwise the most recently added member.
 */
data class CollectionWithThumbnail(
    @Embedded val collection: Collection,
    val thumbnailMediaId: Long?
)

/**
 * Represents a collection with its associated media count and thumbnail, for display in the UI.
 *
 * [mediaCount] and [totalSize] are derived in the repository from the live media set rather than
 * from SQL, because the `media` table is only the search index cache — it is populated by
 * `SearchIndexerUpdaterWorker`, which never runs unless AI media analysis is enabled. Deriving them
 * from live media also means members deleted outside the app drop out, so the card can never
 * disagree with what opening the collection shows.
 */
data class CollectionWithCount(
    @Embedded val collection: Collection,
    val mediaCount: Int,
    val thumbnailMediaId: Long?,
    val totalSize: Long = 0
)
