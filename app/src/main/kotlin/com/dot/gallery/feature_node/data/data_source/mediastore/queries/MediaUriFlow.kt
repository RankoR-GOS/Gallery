/*
 * SPDX-FileCopyrightText: 2023 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.feature_node.data.data_source.mediastore.queries

import android.content.ContentResolver
import android.content.ContentUris
import android.database.Cursor
import android.net.Uri
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
import com.dot.gallery.core.util.ext.tryGetLong
import com.dot.gallery.core.util.ext.tryGetString
import com.dot.gallery.core.util.join
import com.dot.gallery.feature_node.data.data_source.mediastore.MediaQuery
import com.dot.gallery.feature_node.data.model.Media
import com.dot.gallery.feature_node.data.model.MediaType
import com.dot.gallery.feature_node.data.util.isTrashed
import com.dot.gallery.feature_node.presentation.util.getDate
import com.dot.gallery.feature_node.presentation.util.parseTimestampFromFilename
import com.dot.gallery.feature_node.presentation.util.printWarning
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Media uri flow
 *
 * This class is responsible for fetching media from the media store based on the provided uris
 *
 * @property contentResolver
 * @property mimeType
 * @property uris
 * @property onlyMatchingUris If true, only media that matches the provided uris will be returned
 */
class MediaUriFlow(
    private val contentResolver: ContentResolver,
    private val mimeType: String? = null,
    private val uris: List<Uri>,
    private val onlyMatchingUris: Boolean = false,
) : QueryFlow<Media.UriMedia>() {

    private var buckedId: Long = MediaStoreBuckets.MEDIA_STORE_BUCKET_TIMELINE.id
    private val mediaStoreIds: List<Long> by lazy {
        uris
            .mapNotNull { uri ->
                parseCandidateId(uri)
            }
            .distinct()
    }

    override fun flowCursor(): Flow<Cursor?> {
        if (onlyMatchingUris && mediaStoreIds.isEmpty()) {
            return flowOf(null)
        }

        val uri = MediaQuery.MediaStoreFileUri
        val projection = MediaQuery.MediaProjection
        val imageOrVideo = PickerUtils.mediaTypeFromGenericMimeType(mimeType)?.let {
            when (it) {
                MediaType.IMAGE -> MediaQuery.Selection.image
                MediaType.VIDEO -> MediaQuery.Selection.video
            }
        } ?: MediaQuery.Selection.imageOrVideo
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

        val mediaStoreIdFilter = when {
            onlyMatchingUris -> {
                val placeholders = mediaStoreIds.joinToString(separator = ",") { Query.ARG }
                "${MediaStore.Files.FileColumns._ID} IN ($placeholders)"
            }

            else -> null
        }

        // Join all the non-null queries
        val selection = listOfNotNull(
            imageOrVideo,
            albumFilter,
            mimeTypeQuery,
        ).join(Query::and)

        val sqlSelection = listOfNotNull(
            selection?.build(),
            mediaStoreIdFilter,
        )
            .takeIf { it.isNotEmpty() }
            ?.joinToString(separator = " AND ") { "($it)" }

        val selectionArgs = buildList {
            buckedId.takeIf {
                MediaStoreBuckets.entries.toTypedArray().none { bucket -> it == bucket.id }
            }
                ?.toString()
                ?.let(::add)

            rawMimeType?.let(::add)

            if (onlyMatchingUris) {
                mediaStoreIds.map { it.toString() }.let(::addAll)
            }
        }.toTypedArray()

        val sortOrder = when (buckedId) {
            MediaStoreBuckets.MEDIA_STORE_BUCKET_TRASH.id ->
                if (SdkCompat.supportsTrash) "${MediaStore.Files.FileColumns.DATE_EXPIRES} DESC"
                else "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"

            else -> "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
        }

        val queryArgs = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, sqlSelection)
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs)
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sortOrder)

            // Exclude trashed media unless we want data for the trashed album
            // QUERY_ARG_MATCH_TRASHED is only available on API 30+
            if (SdkCompat.supportsTrash) {
                putInt(
                    MediaStore.QUERY_ARG_MATCH_TRASHED, when (buckedId) {
                        MediaStoreBuckets.MEDIA_STORE_BUCKET_TRASH.id -> MediaStore.MATCH_ONLY

                        else -> MediaStore.MATCH_EXCLUDE
                    },
                )
            }
        }

        return contentResolver.queryFlow(
            uri,
            projection,
            queryArgs,
        )
    }

    override fun flowData(): Flow<List<Media.UriMedia>> {
        return flowCursor().mapEachRow(MediaQuery.MediaProjection) { it, indexCache ->
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
            // IS_FAVORITE and IS_TRASHED are only available on API 30+
            val isFavorite = if (SdkCompat.supportsFavorites) it.getInt(indexCache[i++]) else 0
            val isTrashed = if (SdkCompat.supportsTrash) it.getInt(indexCache[i]) else 0
            val contentUri = if (mimeType.contains("image")) {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            } else {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }
            val uri = ContentUris.withAppendedId(contentUri, id)
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
                expiryTimestamp = null,
                fullDate = formattedDate,
                duration = duration,
                favorite = isFavorite,
                trashed = isTrashed,
                size = size,
                mimeType = mimeType,
            )
        }.let { flow ->
            // Derive candidate media IDs from provided MediaStore content:// URIs.
            val ids = mediaStoreIds
            if (onlyMatchingUris) {
                flow.map { mediaList ->
                    mediaList
                        .filter { media -> ids.contains(media.id) && !media.isTrashed }
                        .sortedBy { media -> ids.indexOf(media.id) }
                }
            } else {
                val bucketId = getBucketIdFromFirstUri()
                flow.map { mediaList ->
                    mediaList.filter { media ->
                        bucketId?.let {
                            media.albumID == bucketId && !media.isTrashed
                        } ?: ids.contains(media.id) && !media.isTrashed
                    }
                }
            }
        }
    }

    private fun getBucketIdFromFirstUri(): Long? {
        val firstUri = uris.firstOrNull() ?: return null
        // Bucket lookup only makes sense for MediaStore content URIs.
        if (!isMediaStoreContentUri(firstUri)) return null
        val id = try {
            ContentUris.parseId(firstUri)
        } catch (e: NumberFormatException) {
            e.printStackTrace()
            return null
        }
        val projection = arrayOf(MediaStore.Files.FileColumns.BUCKET_ID)
        val selection = "${MediaStore.Files.FileColumns._ID} = ?"
        val selectionArgs = arrayOf(id.toString())
        contentResolver.query(
            MediaQuery.MediaStoreFileUri,
            projection,
            selection,
            selectionArgs,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.BUCKET_ID))
            }
        }
        return null
    }

    /**
     * Attempt to derive a stable numeric media ID from a supplied URI.
     *  - For MediaStore content:// URIs we delegate to [ContentUris.parseId].
     * Returns null (instead of a random fabricated ID) if parsing fails so the
     * caller can simply exclude the unmatched entry.
     */
    private fun parseCandidateId(uri: Uri): Long? {
        if (!isMediaStoreContentUri(uri)) {
            return null
        }
        return try {
            ContentUris.parseId(uri)
        } catch (e: NumberFormatException) {
            printWarning("MediaUriFlow: Failed to parse content URI id: $uri -> ${e.message}")
            null
        }
    }

    private fun isMediaStoreContentUri(uri: Uri): Boolean {
        return uri.scheme == ContentResolver.SCHEME_CONTENT &&
                uri.authority == MediaStore.AUTHORITY
    }
}
