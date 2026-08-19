package com.dot.gallery.feature_node.data.data_source

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.dot.gallery.feature_node.data.model.FullMediaMetadata
import com.dot.gallery.feature_node.data.model.MediaMetadata
import com.dot.gallery.feature_node.data.model.MediaMetadataCore
import com.dot.gallery.feature_node.data.model.MediaMetadataFlags
import com.dot.gallery.feature_node.data.model.MediaMetadataVideo
import com.dot.gallery.feature_node.data.model.MediaVersion
import com.dot.gallery.feature_node.data.model.toCore
import com.dot.gallery.feature_node.data.model.toFlags
import com.dot.gallery.feature_node.data.model.toVideo
import kotlinx.coroutines.flow.Flow

@Dao
interface MetadataDao {

    @Transaction
    suspend fun setMediaVersion(version: MediaVersion) {
        clearMediaVersion()
        insertMediaVersion(version = MediaVersion(version = "metadata:${version.version}"))
    }

    @Query("DELETE FROM media_version WHERE version LIKE 'metadata:%'")
    suspend fun clearMediaVersion()

    @Upsert(entity = MediaVersion::class)
    suspend fun insertMediaVersion(version: MediaVersion)

    @Query("SELECT EXISTS(SELECT * FROM media_version WHERE version = 'metadata:' || :version) LIMIT 1")
    suspend fun isMediaVersionUpToDate(version: String): Boolean

    @Transaction
    suspend fun addMetadata(mediaMetadata: MediaMetadata, isVideo: Boolean) {
        val core = mediaMetadata.toCore()
        // Video descriptions are local user data, not extracted file metadata.
        val description = when {
            isVideo -> getCoreMetadata(id = mediaMetadata.mediaId)?.imageDescription ?: core.imageDescription
            else -> core.imageDescription
        }
        upsertCore(core = core.copy(imageDescription = description))
        upsertVideo(mediaMetadata.toVideo())
        upsertFlags(mediaMetadata.toFlags())
    }

    @Upsert fun upsertCore(core: MediaMetadataCore)
    @Upsert fun upsertVideo(video: MediaMetadataVideo)
    @Upsert fun upsertFlags(flags: MediaMetadataFlags)

    @Transaction
    suspend fun deleteForgottenMetadata(ids: List<Long>) {
        deleteOrphansCore(ids)
        deleteOrphansVideo(ids)
        deleteOrphansFlags(ids)
    }

    @Query("DELETE FROM media_metadata_core WHERE mediaId NOT IN (:ids)")
    suspend fun deleteOrphansCore(ids: List<Long>)

    @Query("DELETE FROM media_metadata_video WHERE mediaId NOT IN (:ids)")
    suspend fun deleteOrphansVideo(ids: List<Long>)

    @Query("DELETE FROM media_metadata_flags WHERE mediaId NOT IN (:ids)")
    suspend fun deleteOrphansFlags(ids: List<Long>)

    // Flags are written after successful extraction; description-only entries have no flags.
    @Query("SELECT mediaId FROM media_metadata_flags")
    fun getProcessedMediaIds(): Flow<List<Long>>

    @Transaction
    @Query("SELECT * FROM media_metadata_core")
    fun getFullMetadata(): Flow<List<FullMediaMetadata>>

    @Transaction
    @Query("SELECT * FROM media_metadata_core WHERE mediaId = :id")
    fun getFullMetadata(id: Long): Flow<FullMediaMetadata>

    @Query("SELECT * FROM media_metadata_core WHERE mediaId = :id")
    suspend fun getCoreMetadata(id: Long): MediaMetadataCore?

    @Query("UPDATE media_metadata_core SET imageDescription = :description WHERE mediaId = :mediaId")
    suspend fun updateImageDescription(mediaId: Long, description: String)

    @Transaction
    suspend fun upsertImageDescription(mediaId: Long, description: String, imageWidth: Int, imageHeight: Int) {
        val existing = getCoreMetadata(mediaId)
        if (existing != null) {
            updateImageDescription(mediaId, description)
        } else {
            // Create minimal core entry with just the description
            upsertCore(MediaMetadataCore(
                mediaId = mediaId,
                imageDescription = description,
                dateTimeOriginal = null,
                manufacturerName = null,
                modelName = null,
                aperture = null,
                exposureTime = null,
                iso = null,
                gpsLatitude = null,
                gpsLongitude = null,
                gpsLocationName = null,
                gpsLocationNameCountry = null,
                gpsLocationNameCity = null,
                imageWidth = imageWidth,
                imageHeight = imageHeight,
                imageResolutionX = null,
                imageResolutionY = null,
                resolutionUnit = null
            ))
        }
    }
}
