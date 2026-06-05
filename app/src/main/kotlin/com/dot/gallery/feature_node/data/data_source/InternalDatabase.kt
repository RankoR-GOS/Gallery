/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.feature_node.data.data_source

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.dot.gallery.feature_node.domain.model.AlbumGroup
import com.dot.gallery.feature_node.domain.model.AlbumGroupMember
import com.dot.gallery.feature_node.domain.model.AlbumThumbnail
import com.dot.gallery.feature_node.domain.model.Category
import com.dot.gallery.feature_node.domain.model.Collection
import com.dot.gallery.feature_node.domain.model.CollectionAlbum
import com.dot.gallery.feature_node.domain.model.CollectionMedia
import com.dot.gallery.feature_node.domain.model.IgnoredAlbum
import com.dot.gallery.feature_node.domain.model.LockedAlbum
import com.dot.gallery.feature_node.domain.model.ImageEmbedding
import com.dot.gallery.feature_node.domain.model.Media
import com.dot.gallery.feature_node.domain.model.MediaCategory
import com.dot.gallery.feature_node.domain.model.MediaMetadataCore
import com.dot.gallery.feature_node.domain.model.MediaMetadataFlags
import com.dot.gallery.feature_node.domain.model.MediaMetadataVideo
import com.dot.gallery.feature_node.domain.model.MergedSubfolderAlbum
import com.dot.gallery.feature_node.domain.model.MediaVersion
import com.dot.gallery.feature_node.domain.model.PinnedAlbum
import com.dot.gallery.feature_node.domain.model.ScannedMedia
import com.dot.gallery.feature_node.domain.model.TimelineSettings
import com.dot.gallery.feature_node.domain.util.Converters

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
@TypeConverters(Converters::class)
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
