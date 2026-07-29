/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.feature_node.data.data_source

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.dot.gallery.feature_node.data.model.Collection
import com.dot.gallery.feature_node.data.model.CollectionAlbum
import com.dot.gallery.feature_node.data.model.CollectionMedia
import com.dot.gallery.feature_node.data.model.CollectionWithThumbnail
import kotlinx.coroutines.flow.Flow

@Dao
interface CollectionDao {

    // ============ Collection CRUD ============

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCollection(collection: Collection): Long

    @Update
    suspend fun updateCollection(collection: Collection)

    @Query("DELETE FROM collections WHERE id = :collectionId")
    suspend fun deleteCollection(collectionId: Long)

    @Query("SELECT * FROM collections WHERE id = :collectionId")
    fun getCollectionFlow(collectionId: Long): Flow<Collection?>

    @Query("SELECT * FROM collections WHERE id = :collectionId")
    suspend fun getCollectionAsync(collectionId: Long): Collection?

    @Query("SELECT * FROM collections ORDER BY isPinned DESC, sortOrder ASC, updatedAt DESC")
    fun getAllCollections(): Flow<List<Collection>>

    @Query("SELECT * FROM collections ORDER BY isPinned DESC, sortOrder ASC, updatedAt DESC")
    suspend fun getAllCollectionsAsync(): List<Collection>

    @Query("UPDATE collections SET label = :label, updatedAt = :updatedAt WHERE id = :collectionId")
    suspend fun updateCollectionLabel(collectionId: Long, label: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE collections SET isPinned = :isPinned, updatedAt = :updatedAt WHERE id = :collectionId")
    suspend fun updateCollectionPinned(collectionId: Long, isPinned: Boolean, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE collections SET coverMediaId = :mediaId, updatedAt = :updatedAt WHERE id = :collectionId")
    suspend fun updateCollectionCover(collectionId: Long, mediaId: Long?, updatedAt: Long = System.currentTimeMillis())

    @Query("SELECT COUNT(*) FROM collections")
    fun getCollectionCount(): Flow<Int>

    // ============ CollectionMedia operations ============

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addMediaToCollection(collectionMedia: CollectionMedia)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addMediaListToCollection(collectionMedia: List<CollectionMedia>)

    @Query("DELETE FROM collection_media WHERE collectionId = :collectionId AND mediaId = :mediaId")
    suspend fun removeMediaFromCollection(collectionId: Long, mediaId: Long)

    @Query("DELETE FROM collection_media WHERE collectionId = :collectionId")
    suspend fun removeAllMediaFromCollection(collectionId: Long)

    @Query("DELETE FROM collection_media WHERE mediaId NOT IN (:validMediaIds)")
    suspend fun cleanupOrphanedCollectionMedia(validMediaIds: List<Long>)

    @Query("SELECT mediaId FROM collection_media WHERE collectionId = :collectionId ORDER BY addedAt DESC")
    fun getMediaIdsInCollection(collectionId: Long): Flow<List<Long>>

    @Query("SELECT mediaId FROM collection_media WHERE collectionId = :collectionId ORDER BY addedAt DESC")
    suspend fun getMediaIdsInCollectionAsync(collectionId: Long): List<Long>

    @Query("SELECT COUNT(*) FROM collection_media WHERE collectionId = :collectionId")
    fun getMediaCountInCollection(collectionId: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM collection_media WHERE collectionId = :collectionId")
    suspend fun getMediaCountInCollectionAsync(collectionId: Long): Int

    @Query("SELECT EXISTS(SELECT 1 FROM collection_media WHERE collectionId = :collectionId AND mediaId = :mediaId)")
    suspend fun isMediaInCollection(collectionId: Long, mediaId: Long): Boolean

    @Query("SELECT collectionId FROM collection_media WHERE mediaId = :mediaId")
    fun getCollectionIdsForMedia(mediaId: Long): Flow<List<Long>>

    // Get the thumbnail media id: use coverMediaId if set, otherwise most recently added media
    @Query("""
        SELECT COALESCE(
            c.coverMediaId,
            (SELECT cm.mediaId FROM collection_media cm WHERE cm.collectionId = c.id ORDER BY cm.addedAt DESC LIMIT 1)
        )
        FROM collections c WHERE c.id = :collectionId
    """)
    fun getThumbnailMediaId(collectionId: Long): Flow<Long?>

    // ============ CollectionAlbum operations ============

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addAlbumToCollection(collectionAlbum: CollectionAlbum)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addAlbumsToCollection(collectionAlbums: List<CollectionAlbum>)

    @Query("DELETE FROM collection_albums WHERE collectionId = :collectionId AND albumId = :albumId")
    suspend fun removeAlbumFromCollection(collectionId: Long, albumId: Long)

    @Query("SELECT DISTINCT albumId FROM collection_albums")
    fun getAllAlbumIdsInCollections(): Flow<List<Long>>

    @Query("SELECT albumId FROM collection_albums WHERE collectionId = :collectionId")
    fun getAlbumIdsInCollection(collectionId: Long): Flow<List<Long>>

    @Query("SELECT * FROM collection_media")
    fun getAllCollectionMedia(): Flow<List<CollectionMedia>>

    // Collections with their resolved thumbnail for UI display. The count and total size are
    // derived from live media in the repository — see [CollectionWithCount].
    @Query("""
        SELECT c.*,
               COALESCE(
                   c.coverMediaId,
                   (SELECT cm.mediaId FROM collection_media cm WHERE cm.collectionId = c.id ORDER BY cm.addedAt DESC LIMIT 1)
               ) as thumbnailMediaId
        FROM collections c
        ORDER BY c.isPinned DESC, c.sortOrder ASC, c.updatedAt DESC
    """)
    fun getCollectionsWithCount(): Flow<List<CollectionWithThumbnail>>
}
