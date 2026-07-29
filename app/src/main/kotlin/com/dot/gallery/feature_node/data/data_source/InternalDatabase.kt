/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.feature_node.data.data_source

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.dot.gallery.feature_node.data.model.AlbumGroup
import com.dot.gallery.feature_node.data.model.AlbumGroupMember
import com.dot.gallery.feature_node.data.model.AlbumThumbnail
import com.dot.gallery.feature_node.data.model.Category
import com.dot.gallery.feature_node.data.model.Collection
import com.dot.gallery.feature_node.data.model.CollectionAlbum
import com.dot.gallery.feature_node.data.model.CollectionMedia
import com.dot.gallery.feature_node.data.model.IgnoredAlbum
import com.dot.gallery.feature_node.data.model.ImageEmbedding
import com.dot.gallery.feature_node.data.model.LockedAlbum
import com.dot.gallery.feature_node.data.model.Media
import com.dot.gallery.feature_node.data.model.MediaCategory
import com.dot.gallery.feature_node.data.model.MediaMetadataCore
import com.dot.gallery.feature_node.data.model.MediaMetadataFlags
import com.dot.gallery.feature_node.data.model.MediaMetadataVideo
import com.dot.gallery.feature_node.data.model.MediaVersion
import com.dot.gallery.feature_node.data.model.MergedSubfolderAlbum
import com.dot.gallery.feature_node.data.model.PinnedAlbum
import com.dot.gallery.feature_node.data.model.ScannedMedia
import com.dot.gallery.feature_node.data.model.TimelineSettings
import com.dot.gallery.feature_node.data.util.Converters
import com.dot.gallery.feature_node.data.util.EmbeddingBlobConverter

@Database(
    entities = [
        PinnedAlbum::class,
        IgnoredAlbum::class,
        Media.UriMedia::class,
        MediaVersion::class,
        TimelineSettings::class,
        Media.ClassifiedMedia::class,
        MediaMetadataCore::class,
        MediaMetadataVideo::class,
        MediaMetadataFlags::class,
        AlbumThumbnail::class,
        ImageEmbedding::class,
        Category::class,
        MediaCategory::class,
        LockedAlbum::class,
        AlbumGroup::class,
        AlbumGroupMember::class,
        MergedSubfolderAlbum::class,
        Collection::class,
        CollectionMedia::class,
        CollectionAlbum::class,
        ScannedMedia::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class, EmbeddingBlobConverter::class)
abstract class InternalDatabase : RoomDatabase() {

    abstract fun getPinnedDao(): PinnedDao

    abstract fun getBlacklistDao(): BlacklistDao

    abstract fun getMediaDao(): MediaDao

    abstract fun getClassifierDao(): ClassifierDao

    abstract fun getMetadataDao(): MetadataDao

    abstract fun getAlbumThumbnailDao(): AlbumThumbnailDao

    abstract fun getImageEmbeddingDao(): ImageEmbeddingDao

    abstract fun getCategoryDao(): CategoryDao

    abstract fun getLockedAlbumDao(): LockedAlbumDao

    abstract fun getAlbumGroupDao(): AlbumGroupDao

    abstract fun getMergedSubfolderDao(): MergedSubfolderDao

    abstract fun getCollectionDao(): CollectionDao

    abstract fun getScannedMediaDao(): ScannedMediaDao

    companion object {
        const val NAME = "internal_db"
    }
}
