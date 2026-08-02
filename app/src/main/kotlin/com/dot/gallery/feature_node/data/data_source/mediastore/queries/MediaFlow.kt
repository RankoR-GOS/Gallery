/*
 * SPDX-FileCopyrightText: 2023 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.feature_node.data.data_source.mediastore.queries

import android.content.ContentResolver
import android.database.Cursor
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import com.dot.gallery.core.Constants
import com.dot.gallery.core.util.MediaStoreBuckets
import com.dot.gallery.core.util.PickerUtils
import com.dot.gallery.core.util.Query
import com.dot.gallery.core.util.SdkCompat
import com.dot.gallery.core.util.and
import com.dot.gallery.core.util.eq
import com.dot.gallery.core.util.ext.mapEachRow
import com.dot.gallery.core.util.ext.queryFlow
import com.dot.gallery.core.util.ext.querySteppedFlow
import com.dot.gallery.core.util.ext.tryGetLong
import com.dot.gallery.core.util.ext.tryGetString
import com.dot.gallery.core.util.join
import com.dot.gallery.feature_node.data.data_source.mediastore.MediaQuery
import com.dot.gallery.feature_node.data.model.Media
import com.dot.gallery.feature_node.data.util.mediaStoreItemUri
import com.dot.gallery.feature_node.data.model.MediaType
import com.dot.gallery.feature_node.presentation.util.getDate
import com.dot.gallery.feature_node.presentation.util.parseTimestampFromFilename
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Media flow
 *
 * This class is responsible for fetching media from the media store
 *
 * @property contentResolver
 * @property buckedId
 * @property mimeType
 */
class MediaFlow(
    private val contentResolver: ContentResolver,
    private val buckedId: Long,
    private val mimeType: String? = null,
    private val skipBatching: Boolean = false,
    private val afterId: Long? = null,
    private val limit: Int? = null,
) : QueryFlow<Media.UriMedia>() {
    init {
        assert(buckedId != MediaStoreBuckets.MEDIA_STORE_BUCKET_PLACEHOLDER.id) {
            "MEDIA_STORE_BUCKET_PLACEHOLDER found"
        }
    }

    override fun flowCursor(): Flow<Cursor?> {
        // Trash and Favorites are not supported on API 29
        if (!SdkCompat.supportsTrash && buckedId == MediaStoreBuckets.MEDIA_STORE_BUCKET_TRASH.id) {
            return flowOf(null)
        }
        if (!SdkCompat.supportsFavorites && buckedId == MediaStoreBuckets.MEDIA_STORE_BUCKET_FAVORITES.id) {
            return flowOf(null)
        }

        val uri = MediaQuery.MediaStoreFileUri
        val projection = when(buckedId) {
            MediaStoreBuckets.MEDIA_STORE_BUCKET_TRASH.id ->
                MediaQuery.MediaProjectionTrash
            else ->
                MediaQuery.MediaProjection
        }
        val imageOrVideo = PickerUtils.mediaTypeFromGenericMimeType(mimeType)?.let {
            when (it) {
                MediaType.IMAGE -> MediaQuery.Selection.image
                MediaType.VIDEO -> MediaQuery.Selection.video
            }
        } ?: when (buckedId) {
            MediaStoreBuckets.MEDIA_STORE_BUCKET_PHOTOS.id -> MediaQuery.Selection.image
            MediaStoreBuckets.MEDIA_STORE_BUCKET_VIDEOS.id -> MediaQuery.Selection.video
            else -> MediaQuery.Selection.imageOrVideo
        }
        val albumFilter = when (buckedId) {
            MediaStoreBuckets.MEDIA_STORE_BUCKET_FAVORITES.id ->
                if (SdkCompat.supportsFavorites)
                    MediaStore.Files.FileColumns.IS_FAVORITE eq 1
                else null

            MediaStoreBuckets.MEDIA_STORE_BUCKET_TRASH.id ->
                if (SdkCompat.supportsTrash)
                    MediaStore.Files.FileColumns.IS_TRASHED eq 1
                else null

            MediaStoreBuckets.MEDIA_STORE_BUCKET_TIMELINE.id,
            MediaStoreBuckets.MEDIA_STORE_BUCKET_PHOTOS.id,
            MediaStoreBuckets.MEDIA_STORE_BUCKET_VIDEOS.id -> null

            else -> MediaStore.Files.FileColumns.BUCKET_ID eq Query.ARG
        }
        val rawMimeType = mimeType?.takeIf { PickerUtils.isMimeTypeNotGeneric(it) }
        val mimeTypeQuery = rawMimeType?.let {
            MediaStore.Files.FileColumns.MIME_TYPE eq Query.ARG
        }

        // Join all the non-null queries
        val mediaSelection = listOfNotNull(
            imageOrVideo,
            albumFilter,
            mimeTypeQuery,
        ).join(Query::and)

        val idSelection = afterId?.let {
            "${MediaStore.Files.FileColumns._ID} > ${Query.ARG}"
        }
        val selection = listOfNotNull(
            mediaSelection?.build(),
            idSelection,
        )
            .takeIf { clauses -> clauses.isNotEmpty() }
            ?.joinToString(separator = " AND ") { clause -> "($clause)" }

        val selectionArgs = buildList {
            buckedId.takeIf {
                MediaStoreBuckets.entries.toTypedArray().none { bucket -> it == bucket.id }
            }
                ?.toString()
                ?.let(::add)
            rawMimeType?.let(::add)
            afterId?.toString()?.let(::add)
        }.toTypedArray()

        val sortOrder = when {
            afterId != null -> "${MediaStore.Files.FileColumns._ID} ASC"
            buckedId == MediaStoreBuckets.MEDIA_STORE_BUCKET_TRASH.id ->
                if (SdkCompat.supportsTrash) "${MediaStore.Files.FileColumns.DATE_EXPIRES} DESC"
                else "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
            else -> "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
        }

        val queryArgs = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs)
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sortOrder)
            limit?.let { pageLimit ->
                putInt(ContentResolver.QUERY_ARG_LIMIT, pageLimit)
            }

            // Exclude trashed media unless we want data for the trashed album
            // QUERY_ARG_MATCH_TRASHED is only available on API 30+
            if (SdkCompat.supportsTrash) {
                putInt(
                    MediaStore.QUERY_ARG_MATCH_TRASHED, when (buckedId) {
                        MediaStoreBuckets.MEDIA_STORE_BUCKET_TRASH.id -> MediaStore.MATCH_ONLY

                        else -> MediaStore.MATCH_EXCLUDE
                    }
                )
            }
        }
        return if (skipBatching) {
            contentResolver.queryFlow(
                uri = uri,
                projection = projection,
                queryArgs = queryArgs,
            )
        } else {
            contentResolver.querySteppedFlow(
                uri = uri,
                projection = projection,
                queryArgs = queryArgs,
            )
        }
    }

    override fun flowData(): Flow<List<Media.UriMedia>> {
        return flowCursor().mapEachRow(
            projection = when (buckedId) {
                MediaStoreBuckets.MEDIA_STORE_BUCKET_TRASH.id -> MediaQuery.MediaProjectionTrash
                else -> MediaQuery.MediaProjection
            },
        ) { it, indexCache ->
            var i = 0

            val id = it.getLong(indexCache[i++])
            val path = it.tryGetString(indexCache[i++]).orEmpty()
            val relativePath = it.tryGetString(indexCache[i++]).orEmpty()
            val title = it.tryGetString(indexCache[i++]).orEmpty()
            val albumID = it.getLong(indexCache[i++])
            val albumLabel = it.tryGetString(indexCache[i++], Build.MODEL)
            val takenTimestamp = it.tryGetLong(indexCache[i++])
                ?: title.parseTimestampFromFilename()
            val modifiedTimestamp = it.getLong(indexCache[i++])
            val duration = it.tryGetString(indexCache[i++])
            val size = it.getLong(indexCache[i++])
            val mimeType = it.tryGetString(indexCache[i++]).orEmpty()
            val volumeName = it.tryGetString(indexCache[i++]).orEmpty()
            // IS_FAVORITE and IS_TRASHED are only available on API 30+
            val isFavorite = if (SdkCompat.supportsFavorites) it.getInt(indexCache[i++]) else 0
            val isTrashAlbum = buckedId == MediaStoreBuckets.MEDIA_STORE_BUCKET_TRASH.id
            val isTrashed = if (SdkCompat.supportsTrash) {
                it.getInt(indexCache[if (isTrashAlbum) i++ else i])
            } else {
                0
            }
            val expiryTimestamp = if (isTrashAlbum && SdkCompat.supportsTrash) {
                it.tryGetLong(indexCache[i])
            } else {
                null
            }
            val uri = mediaStoreItemUri(id = id, mimeType = mimeType, volumeName = volumeName)
            val formattedDate = (takenTimestamp?.div(1000) ?: modifiedTimestamp).getDate(
                Constants.FULL_DATE_FORMAT,
            )
            Media.UriMedia(
                id = id,
                label = title,
                uri = uri,
                path = path,
                relativePath = relativePath,
                albumID = albumID,
                albumLabel = albumLabel ?: Build.MODEL,
                timestamp = modifiedTimestamp,
                takenTimestamp = takenTimestamp,
                expiryTimestamp = expiryTimestamp,
                fullDate = formattedDate,
                duration = duration,
                favorite = isFavorite,
                trashed = isTrashed,
                size = size,
                mimeType = mimeType,
            )
        }
    }
}
