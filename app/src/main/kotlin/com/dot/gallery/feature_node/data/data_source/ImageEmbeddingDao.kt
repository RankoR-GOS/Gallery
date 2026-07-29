package com.dot.gallery.feature_node.data.data_source

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.dot.gallery.feature_node.data.model.ImageEmbedding
import com.dot.gallery.feature_node.data.model.ImageEmbeddingStamp
import kotlinx.coroutines.flow.Flow

@Dao
interface ImageEmbeddingDao {
    @Upsert
    suspend fun addImageEmbedding(imageEmbedding: ImageEmbedding)

    @Upsert
    suspend fun addImageEmbeddings(imageEmbeddings: List<ImageEmbedding>)

    @Query("SELECT * FROM image_embeddings WHERE id = :id LIMIT 1")
    suspend fun getRecord(id: Long): ImageEmbedding?

    @Query("SELECT * FROM image_embeddings")
    fun getRecords(): Flow<List<ImageEmbedding>>

    @Query("SELECT id, date FROM image_embeddings WHERE id > :afterId ORDER BY id LIMIT :limit")
    suspend fun getStampPage(afterId: Long, limit: Int): List<ImageEmbeddingStamp>

    @Query("SELECT * FROM image_embeddings WHERE id > :afterId ORDER BY id LIMIT :limit")
    suspend fun getPage(afterId: Long, limit: Int): List<ImageEmbedding>

    @Query("SELECT * FROM image_embeddings WHERE id IN (:ids)")
    suspend fun getByIds(ids: Set<Long>): List<ImageEmbedding>

    @Query("SELECT COUNT(*) FROM image_embeddings")
    suspend fun getCount(): Int

    @Query("DELETE FROM image_embeddings")
    suspend fun deleteAll()

    @Query("DELETE FROM image_embeddings WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: Set<Long>)
}
