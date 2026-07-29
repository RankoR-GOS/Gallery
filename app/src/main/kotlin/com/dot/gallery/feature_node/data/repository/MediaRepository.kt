/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.feature_node.data.repository

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.location.Geocoder
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.core.app.ActivityOptionsCompat
import androidx.datastore.preferences.core.Preferences
import androidx.work.WorkManager
import com.dot.gallery.core.Resource
import com.dot.gallery.core.Settings
import com.dot.gallery.core.dataStore
import com.dot.gallery.core.sandbox.IsolatedMetadataParser
import com.dot.gallery.core.util.MediaStoreBuckets
import com.dot.gallery.core.util.SdkCompat
import com.dot.gallery.core.util.ext.deleteGpsMetadata
import com.dot.gallery.core.util.ext.deleteMetadata
import com.dot.gallery.core.util.ext.mapAsResource
import com.dot.gallery.core.util.ext.renameMedia
import com.dot.gallery.core.util.ext.saveImage
import com.dot.gallery.core.util.ext.updateImageDescription
import com.dot.gallery.core.util.ext.updateMedia
import com.dot.gallery.core.util.ext.updateMediaExif
import com.dot.gallery.core.workers.MediaCopyRequest
import com.dot.gallery.core.workers.MediaCopyScheduler
import com.dot.gallery.core.workers.updateDatabase
import com.dot.gallery.feature_node.data.data_source.CategoryWithMediaCount
import com.dot.gallery.feature_node.data.data_source.InternalDatabase
import com.dot.gallery.feature_node.data.data_source.flatMapIdChunks
import com.dot.gallery.feature_node.data.data_source.mediastore.queries.AlbumsFlow
import com.dot.gallery.feature_node.data.data_source.mediastore.queries.MediaFlow
import com.dot.gallery.feature_node.data.data_source.mediastore.queries.MediaUriFlow
import com.dot.gallery.feature_node.data.model.Album
import com.dot.gallery.feature_node.data.model.AlbumGroup
import com.dot.gallery.feature_node.data.model.AlbumGroupMember
import com.dot.gallery.feature_node.data.model.AlbumThumbnail
import com.dot.gallery.feature_node.data.model.Category
import com.dot.gallery.feature_node.data.model.Collection
import com.dot.gallery.feature_node.data.model.CollectionMedia
import com.dot.gallery.feature_node.data.model.CollectionWithCount
import com.dot.gallery.feature_node.data.model.IgnoredAlbum
import com.dot.gallery.feature_node.data.model.ImageEmbedding
import com.dot.gallery.feature_node.data.model.ImageEmbeddingStamp
import com.dot.gallery.feature_node.data.model.LockedAlbum
import com.dot.gallery.feature_node.data.model.Media
import com.dot.gallery.feature_node.data.model.Media.ClassifiedMedia
import com.dot.gallery.feature_node.data.model.Media.UriMedia
import com.dot.gallery.feature_node.data.model.MediaCategory
import com.dot.gallery.feature_node.data.model.MediaMetadata
import com.dot.gallery.feature_node.data.model.MergedSubfolderAlbum
import com.dot.gallery.feature_node.data.model.PinnedAlbum
import com.dot.gallery.feature_node.data.model.TimelineSettings
import com.dot.gallery.feature_node.data.repository.MediaRepository
import com.dot.gallery.feature_node.data.util.MediaOrder
import com.dot.gallery.feature_node.data.util.OrderType
import com.dot.gallery.feature_node.data.util.resolveMediaStoreVolume
import com.dot.gallery.feature_node.data.model.retrieveExtraMediaMetadata
import com.dot.gallery.feature_node.data.model.toMediaMetadata
import com.dot.gallery.feature_node.data.util.getUri
import com.dot.gallery.feature_node.data.util.isVideo
import com.dot.gallery.feature_node.presentation.picker.AllowedMedia
import com.dot.gallery.feature_node.presentation.picker.AllowedMedia.BOTH
import com.dot.gallery.feature_node.presentation.picker.AllowedMedia.PHOTOS
import com.dot.gallery.feature_node.presentation.picker.AllowedMedia.VIDEOS
import com.dot.gallery.feature_node.presentation.util.printWarning
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

interface MediaRepository {

    suspend fun updateInternalDatabase()

    fun getMedia(): Flow<Resource<List<UriMedia>>>

    fun getCompleteMedia(): Flow<Resource<List<UriMedia>>>

    suspend fun getCompleteMediaPage(afterId: Long, limit: Int): List<UriMedia>

    fun getMediaByType(allowedMedia: AllowedMedia): Flow<Resource<List<UriMedia>>>

    fun getFavorites(mediaOrder: MediaOrder): Flow<Resource<List<UriMedia>>>

    fun getTrashed(): Flow<Resource<List<UriMedia>>>

    fun getAlbums(mediaOrder: MediaOrder): Flow<Resource<List<Album>>>

    fun getAlbum(albumId: Long): Flow<Resource<Album>>

    suspend fun insertPinnedAlbum(pinnedAlbum: PinnedAlbum)

    suspend fun removePinnedAlbum(pinnedAlbum: PinnedAlbum)

    fun getPinnedAlbums(): Flow<List<PinnedAlbum>>

    suspend fun insertLockedAlbum(lockedAlbum: LockedAlbum)

    suspend fun removeLockedAlbum(lockedAlbum: LockedAlbum)

    fun getLockedAlbums(): Flow<List<LockedAlbum>>

    suspend fun addBlacklistedAlbum(ignoredAlbum: IgnoredAlbum)

    suspend fun removeBlacklistedAlbum(ignoredAlbum: IgnoredAlbum)

    fun getBlacklistedAlbums(): Flow<List<IgnoredAlbum>>

    suspend fun getBlacklistedAlbumsAsync(): List<IgnoredAlbum>

    fun getMediaByAlbumId(albumId: Long): Flow<Resource<List<UriMedia>>>

    fun getMediaByAlbumIdWithType(
        albumId: Long,
        allowedMedia: AllowedMedia
    ): Flow<Resource<List<UriMedia>>>

    fun getAlbumsWithType(allowedMedia: AllowedMedia): Flow<Resource<List<Album>>>

    fun getMediaListByUris(
        listOfUris: List<Uri>,
        reviewMode: Boolean,
        onlyMatching: Boolean = false,
    ): Flow<Resource<List<UriMedia>>>

    suspend fun <T: Media> toggleFavorite(
        result: ActivityResultLauncher<IntentSenderRequest>,
        mediaList: List<T>,
        favorite: Boolean
    )

    suspend fun <T: Media> trashMedia(
        result: ActivityResultLauncher<IntentSenderRequest>,
        mediaList: List<T>,
        trash: Boolean
    )

    suspend fun <T: Media> copyMedia(
        from: T,
        path: String
    )

    suspend fun <T: Media> copyMedia(vararg sets: Pair<T, String>)

    suspend fun <T: Media> deleteMedia(
        result: ActivityResultLauncher<IntentSenderRequest>,
        mediaList: List<T>
    )

    suspend fun <T: Media> renameMedia(
        media: T,
        newName: String
    ): Boolean

    suspend fun <T: Media> moveMedia(
        media: T,
        newPath: String
    ): Boolean

    suspend fun <T: Media> deleteMediaMetadata(media: T): Boolean

    suspend fun <T: Media> deleteMediaGPSMetadata(media: T): Boolean

    suspend fun <T: Media> updateMediaDescription(
        media: T,
        description: String
    ): Boolean

    suspend fun saveImage(
        bitmap: Bitmap,
        format: Bitmap.CompressFormat,
        mimeType: String,
        relativePath: String,
        displayName: String
    ): Uri?

    fun getTimelineSettings(): Flow<TimelineSettings?>

    suspend fun updateTimelineSettings(settings: TimelineSettings)

    fun <Result> getSetting(key: Preferences.Key<Result>, defaultValue: Result): Flow<Result>

    // ============ Legacy Classification (to be deprecated) ============
    fun getClassifiedCategories(): Flow<List<String>>

    fun getClassifiedMediaByCategory(category: String?): Flow<List<ClassifiedMedia>>

    fun getClassifiedMediaByMostPopularCategory(): Flow<List<ClassifiedMedia>>

    fun getCategoriesWithMedia(): Flow<List<ClassifiedMedia>>

    fun getClassifiedMediaCount(): Flow<Int>

    fun getClassifiedMediaCountAtCategory(category: String): Flow<Int>

    fun getClassifiedMediaThumbnailByCategory(category: String): Flow<ClassifiedMedia?>

    suspend fun getCategoryForMediaId(mediaId: Long): String?
    suspend fun changeCategory(mediaId: Long, newCategory: String)

    suspend fun deleteClassifications()

    // ============ New Category System ============
    
    // Category CRUD
    suspend fun createCategory(category: Category): Long
    suspend fun updateCategory(category: Category)
    suspend fun deleteCategory(categoryId: Long)
    fun getCategory(categoryId: Long): Flow<Category?>
    suspend fun getCategoryAsync(categoryId: Long): Category?
    fun getAllCategories(): Flow<List<Category>>
    suspend fun getAllCategoriesAsync(): List<Category>
    fun getCategoriesWithMediaCount(): Flow<List<CategoryWithMediaCount>>
    fun getCategoryCount(): Flow<Int>
    fun getTopCategories(limit: Int = 10): Flow<List<CategoryWithMediaCount>>
    
    // Category settings
    suspend fun updateCategoryThreshold(categoryId: Long, threshold: Float)
    suspend fun updateCategoryName(categoryId: Long, name: String)
    suspend fun toggleCategoryPinned(categoryId: Long, isPinned: Boolean)
    
    // Media-Category associations
    fun getMediaIdsInCategory(categoryId: Long): Flow<List<Long>>
    suspend fun getMediaIdsInCategoryAsync(categoryId: Long): List<Long>
    fun getCategoriesForMedia(mediaId: Long): Flow<List<Category>>
    suspend fun addMediaToCategory(mediaId: Long, categoryId: Long, similarity: Float = 1f, isManual: Boolean = true)
    suspend fun removeMediaFromCategory(mediaId: Long, categoryId: Long)
    fun getMediaCountInCategory(categoryId: Long): Flow<Int>
    fun getThumbnailMediaIdForCategory(categoryId: Long): Flow<Long?>
    
    // Category initialization and management
    suspend fun initializeDefaultCategories()
    suspend fun resetCategoryData()

    fun getMetadata(): Flow<List<MediaMetadata>>

    fun getMetadata(media: Media): Flow<MediaMetadata>

    suspend fun updateAlbumThumbnail(albumId: Long, thumbnail: Uri)

    suspend fun deleteAlbumThumbnail(albumId: Long)

    fun getAlbumThumbnail(albumId: Long): Flow<AlbumThumbnail?>

    fun getAlbumThumbnails(): Flow<List<AlbumThumbnail>>

    fun hasAlbumThumbnail(albumId: Long): Flow<Boolean>

    suspend fun collectMetadataFor(media: Media)

    suspend fun addImageEmbedding(imageEmbedding: ImageEmbedding)

    suspend fun getRecord(id: Long): ImageEmbedding?

    suspend fun getImageEmbeddingStampPage(afterId: Long, limit: Int): List<ImageEmbeddingStamp>

    suspend fun getImageEmbeddingPage(afterId: Long, limit: Int): List<ImageEmbedding>

    suspend fun getImageEmbeddingsByIds(ids: Set<Long>): List<ImageEmbedding>

    // ============ Album Groups ============

    suspend fun insertAlbumGroup(group: AlbumGroup): Long

    suspend fun updateAlbumGroup(group: AlbumGroup)

    suspend fun deleteAlbumGroup(groupId: Long)

    fun getAllAlbumGroups(): Flow<List<AlbumGroup>>

    fun getAlbumGroup(groupId: Long): Flow<AlbumGroup?>

    suspend fun getAlbumGroupAsync(groupId: Long): AlbumGroup?

    suspend fun addAlbumToGroup(member: AlbumGroupMember)

    suspend fun removeAlbumFromGroup(member: AlbumGroupMember)

    suspend fun removeAllAlbumsFromGroup(groupId: Long)

    fun getAlbumIdsInGroup(groupId: Long): Flow<List<Long>>

    fun getAllGroupMembers(): Flow<List<AlbumGroupMember>>

    suspend fun getGroupIdForAlbum(albumId: Long): Long?

    // ============ Merged Subfolder Albums ============

    suspend fun insertMergedSubfolderAlbum(mergedSubfolderAlbum: MergedSubfolderAlbum)

    suspend fun removeMergedSubfolderAlbum(mergedSubfolderAlbum: MergedSubfolderAlbum)

    fun getMergedSubfolderAlbums(): Flow<List<MergedSubfolderAlbum>>

    // ============ Collections ============

    suspend fun insertCollection(collection: Collection): Long

    suspend fun updateCollection(collection: Collection)

    suspend fun deleteCollection(collectionId: Long)

    fun getCollection(collectionId: Long): Flow<Collection?>

    suspend fun getCollectionAsync(collectionId: Long): Collection?

    fun getAllCollections(): Flow<List<Collection>>

    fun getCollectionsWithCount(): Flow<List<CollectionWithCount>>

    suspend fun updateCollectionLabel(collectionId: Long, label: String)

    suspend fun toggleCollectionPinned(collectionId: Long, isPinned: Boolean)

    suspend fun updateCollectionCover(collectionId: Long, mediaId: Long?)

    suspend fun addMediaToCollection(collectionId: Long, mediaId: Long)

    suspend fun addMediaListToCollection(collectionId: Long, mediaIds: List<Long>)

    suspend fun removeMediaFromCollection(collectionId: Long, mediaId: Long)

    fun getMediaIdsInCollection(collectionId: Long): Flow<List<Long>>

    suspend fun getMediaIdsInCollectionAsync(collectionId: Long): List<Long>

    fun getMediaCountInCollection(collectionId: Long): Flow<Int>

    fun getCollectionIdsForMedia(mediaId: Long): Flow<List<Long>>

    suspend fun cleanupOrphanedCollectionMedia(validMediaIds: List<Long>)

    suspend fun addAlbumsToCollection(collectionId: Long, albumIds: List<Long>)

    suspend fun removeAlbumFromCollection(collectionId: Long, albumId: Long)

    fun getAllAlbumIdsInCollections(): Flow<List<Long>>

    fun getAlbumIdsInCollection(collectionId: Long): Flow<List<Long>>

}

internal class MediaRepositoryImpl(
    private val context: Context,
    private val workManager: WorkManager,
    private val mediaCopyScheduler: MediaCopyScheduler,
    private val database: InternalDatabase,
    private val geocoder: Geocoder?,
    private val isolatedParser: IsolatedMetadataParser
) : MediaRepository {

    private val contentResolver = context.contentResolver

    /**
     * On-demand metadata operations use per-file isolation in both hybrid and per-file modes.
     */
    private suspend fun shouldUsePerFileIsolation(): Boolean {
        val mode = Settings.Security.getMetadataIsolationMode(context)
            .firstOrNull() ?: Settings.Security.DEFAULT_METADATA_ISOLATION_MODE
        return mode != Settings.Security.METADATA_ISOLATION_SHARED
    }

    private var updateDatabaseMutex = Mutex()

    override suspend fun updateInternalDatabase() {
        if (!updateDatabaseMutex.isLocked) {
            updateDatabaseMutex.withLock {
                delay(5000.milliseconds) // Delay to ensure the database is not updated too frequently
                workManager.updateDatabase()
            }
        }
        //workManager.scheduleMediaMigrationCheck()
    }

    /**
     * TODO: Add media reordering
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun getMedia(): Flow<Resource<List<UriMedia>>> =
        MediaFlow(
            contentResolver = contentResolver,
            buckedId = MediaStoreBuckets.MEDIA_STORE_BUCKET_TIMELINE.id
        ).flowData().map {
            Resource.Success(MediaOrder.Date(OrderType.Descending).sortMedia(it))
        }.flowOn(Dispatchers.IO)

    override fun getCompleteMedia(): Flow<Resource<List<UriMedia>>> =
        MediaFlow(
            contentResolver = contentResolver,
            buckedId = MediaStoreBuckets.MEDIA_STORE_BUCKET_TIMELINE.id,
            skipBatching = true
        ).flowData().map {
            Resource.Success(MediaOrder.Date(OrderType.Descending).sortMedia(it))
        }.flowOn(Dispatchers.IO)

    override suspend fun getCompleteMediaPage(afterId: Long, limit: Int): List<UriMedia> {
        return MediaFlow(
            contentResolver = contentResolver,
            buckedId = MediaStoreBuckets.MEDIA_STORE_BUCKET_TIMELINE.id,
            skipBatching = true,
            afterId = afterId,
            limit = limit,
        ).flowData().first()
    }

    override fun getMediaByType(allowedMedia: AllowedMedia): Flow<Resource<List<UriMedia>>> =
        MediaFlow(
            contentResolver = contentResolver,
            buckedId = when (allowedMedia) {
                PHOTOS -> MediaStoreBuckets.MEDIA_STORE_BUCKET_PHOTOS.id
                VIDEOS -> MediaStoreBuckets.MEDIA_STORE_BUCKET_VIDEOS.id
                BOTH -> MediaStoreBuckets.MEDIA_STORE_BUCKET_TIMELINE.id
            },
            mimeType = allowedMedia.toStringAny()
        ).flowData().map {
            Resource.Success(it)
        }.flowOn(Dispatchers.IO)

    override fun getFavorites(mediaOrder: MediaOrder): Flow<Resource<List<UriMedia>>> =
        MediaFlow(
            contentResolver = contentResolver,
            buckedId = MediaStoreBuckets.MEDIA_STORE_BUCKET_FAVORITES.id
        ).flowData().map {
            Resource.Success(it)
        }.flowOn(Dispatchers.IO)

    override fun getTrashed(): Flow<Resource<List<UriMedia>>> =
        MediaFlow(
            contentResolver = contentResolver,
            buckedId = MediaStoreBuckets.MEDIA_STORE_BUCKET_TRASH.id
        ).flowData().map { Resource.Success(it) }.flowOn(Dispatchers.IO)

    override fun getAlbums(mediaOrder: MediaOrder): Flow<Resource<List<Album>>> =
        AlbumsFlow(context).flowData().map {
            withContext(Dispatchers.IO) {
                val pinnedIds = database.getPinnedDao().getPinnedAlbumIds().toHashSet()
                val data = it.map { album ->
                    album.copy(isPinned = album.id in pinnedIds)
                }
                Resource.Success(mediaOrder.sortAlbums(data))
            }
        }.flowOn(Dispatchers.IO)

    override fun getAlbum(albumId: Long): Flow<Resource<Album>> =
        AlbumsFlow(context).flowData().map {
            withContext(Dispatchers.IO) {
                val pinnedIds = database.getPinnedDao().getPinnedAlbumIds().toHashSet()
                val album = it.firstOrNull { it -> it.id == albumId }
                    ?.copy(isPinned = albumId in pinnedIds)
                    ?: return@withContext Resource.Error("Album not found")
                Resource.Success(album)
            }
        }.flowOn(Dispatchers.IO)

    override suspend fun insertPinnedAlbum(pinnedAlbum: PinnedAlbum) =
        database.getPinnedDao().insertPinnedAlbum(pinnedAlbum)

    override suspend fun removePinnedAlbum(pinnedAlbum: PinnedAlbum) =
        database.getPinnedDao().removePinnedAlbum(pinnedAlbum)

    override fun getPinnedAlbums(): Flow<List<PinnedAlbum>> =
        database.getPinnedDao().getPinnedAlbums()

    override suspend fun insertLockedAlbum(lockedAlbum: LockedAlbum) =
        database.getLockedAlbumDao().insertLockedAlbum(lockedAlbum)

    override suspend fun removeLockedAlbum(lockedAlbum: LockedAlbum) =
        database.getLockedAlbumDao().removeLockedAlbum(lockedAlbum)

    override fun getLockedAlbums(): Flow<List<LockedAlbum>> =
        database.getLockedAlbumDao().getLockedAlbums()

    override suspend fun addBlacklistedAlbum(ignoredAlbum: IgnoredAlbum) =
        database.getBlacklistDao().addBlacklistedAlbum(ignoredAlbum)

    override suspend fun removeBlacklistedAlbum(ignoredAlbum: IgnoredAlbum) =
        database.getBlacklistDao().removeBlacklistedAlbum(ignoredAlbum)

    override fun getBlacklistedAlbums(): Flow<List<IgnoredAlbum>> =
        database.getBlacklistDao().getBlacklistedAlbums()

    override suspend fun getBlacklistedAlbumsAsync(): List<IgnoredAlbum> =
        database.getBlacklistDao().getBlacklistedAlbumsAsync()

    override fun getMediaByAlbumId(albumId: Long): Flow<Resource<List<UriMedia>>> =
        MediaFlow(
            contentResolver = contentResolver,
            buckedId = albumId,
        ).flowData().mapAsResource()

    override fun getMediaByAlbumIdWithType(
        albumId: Long,
        allowedMedia: AllowedMedia
    ): Flow<Resource<List<UriMedia>>> =
        MediaFlow(
            contentResolver = contentResolver,
            buckedId = albumId,
            mimeType = allowedMedia.toStringAny()
        ).flowData().mapAsResource()

    override fun getAlbumsWithType(allowedMedia: AllowedMedia): Flow<Resource<List<Album>>> =
        AlbumsFlow(
            context = context,
            mimeType = allowedMedia.toStringAny()
        ).flowData().mapAsResource()

    override fun getMediaListByUris(
        listOfUris: List<Uri>,
        reviewMode: Boolean,
        onlyMatching: Boolean
    ): Flow<Resource<List<UriMedia>>> {
        return MediaUriFlow(
            contentResolver = contentResolver,
            uris = listOfUris,
            onlyMatchingUris = onlyMatching,
        ).flowData().mapAsResource(
            errorOnEmpty = true,
            errorMessage = "Media could not be opened",
        )
    }

    override suspend fun <T : Media> toggleFavorite(
        result: ActivityResultLauncher<IntentSenderRequest>,
        mediaList: List<T>,
        favorite: Boolean
    ) {
        if (!SdkCompat.supportsMediaStoreRequests) {
            // Favorites not supported on API 29
            return
        }
        val intentSender = MediaStore.createFavoriteRequest(
            contentResolver,
            mediaList.map { it.getUri() },
            favorite
        ).intentSender
        val senderRequest: IntentSenderRequest = IntentSenderRequest.Builder(intentSender)
            .setFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION, 0)
            .build()
        result.launch(senderRequest)
    }

    override suspend fun <T : Media> trashMedia(
        result: ActivityResultLauncher<IntentSenderRequest>,
        mediaList: List<T>,
        trash: Boolean
    ) {
        if (!SdkCompat.supportsMediaStoreRequests) {
            // Trash not supported on API 29; delete directly instead
            if (trash) {
                deleteMedia(result, mediaList)
            }
            return
        }
        val intentSender = MediaStore.createTrashRequest(
            contentResolver,
            mediaList.map { it.getUri() },
            trash
        ).intentSender
        val senderRequest: IntentSenderRequest = IntentSenderRequest.Builder(intentSender)
            .setFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION, 0)
            .build()
        result.launch(senderRequest, ActivityOptionsCompat.makeTaskLaunchBehind())
    }

    override suspend fun <T : Media> deleteMedia(
        result: ActivityResultLauncher<IntentSenderRequest>,
        mediaList: List<T>
    ) {
        if (!SdkCompat.supportsMediaStoreRequests) {
            // On API 29, delete directly via ContentResolver
            // requestLegacyExternalStorage grants full write access
            withContext(Dispatchers.IO) {
                mediaList.forEach { media ->
                    runCatching {
                        contentResolver.delete(media.getUri(), null, null)
                    }.onFailure {
                        printWarning("Failed to delete media ${media.id}: ${it.message}")
                    }
                }
            }
            return
        }
        val intentSender =
            MediaStore.createDeleteRequest(
                contentResolver,
                mediaList.map { it.getUri() }).intentSender
        val senderRequest: IntentSenderRequest = IntentSenderRequest.Builder(intentSender)
            .setFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION, 0)
            .build()
        result.launch(senderRequest)
    }

    override suspend fun <T : Media> copyMedia(
        from: T,
        path: String
    ) {
        val requests = listOf(
            MediaCopyRequest(
                sourceUri = from.getUri(),
                destinationPath = path,
            ),
        )

        val batch = mediaCopyScheduler.prepareBatch(requests = requests)

        mediaCopyScheduler.enqueue(
            batch = batch,
            requests = requests,
        )
    }

    override suspend fun <T : Media> copyMedia(vararg sets: Pair<T, String>) {
        if (sets.isEmpty()) {
            return
        }

        val requests = sets.map { (media, path) ->
            MediaCopyRequest(
                sourceUri = media.getUri(),
                destinationPath = path,
            )
        }

        val batch = mediaCopyScheduler.prepareBatch(requests = requests)

        mediaCopyScheduler.enqueue(
            batch = batch,
            requests = requests,
        )
    }

    override suspend fun <T : Media> renameMedia(
        media: T,
        newName: String
    ): Boolean = context.renameMedia(
        media = media,
        newName = newName
    )

    override suspend fun <T : Media> moveMedia(
        media: T,
        newPath: String,
    ): Boolean {
        val (destVolume, destRelPath) = resolveMediaStoreVolume(newPath)
        val sourceVolume = resolveMediaStoreVolume(path = media.path).first

        if (destVolume == sourceVolume) {
            return context.updateMedia(
                media = media,
                contentValues = relativePath(destRelPath)
            )
        }

        return crossVolumeMove(
            media = media,
            destVolume = destVolume,
            destRelPath = destRelPath,
        )
    }

    private suspend fun <T : Media> crossVolumeMove(
        media: T,
        destVolume: String,
        destRelPath: String,
    ): Boolean {
        return withContext(Dispatchers.IO) {
            val cr = context.contentResolver
            var destinationUri: Uri? = null
            var destinationPublished = false
            try {
                val srcUri = media.getUri()
                val mediaType = cr.getType(srcUri) ?: return@withContext false
                val isVideo = mediaType.startsWith("video")

                destinationUri = cr.insert(
                    if (isVideo) MediaStore.Video.Media.getContentUri(destVolume)
                    else MediaStore.Images.Media.getContentUri(destVolume),
                    ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, media.label)
                        put(MediaStore.MediaColumns.MIME_TYPE, media.mimeType)
                        put(MediaStore.MediaColumns.RELATIVE_PATH, destRelPath)
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    },
                ) ?: return@withContext false

                val copied = cr.openInputStream(srcUri)?.use { input ->
                    cr.openOutputStream(destinationUri)?.use { output ->
                        input.copyTo(output)
                        true
                    }
                } == true

                if (!copied) {
                    cr.delete(destinationUri, null, null)
                    return@withContext false
                }

                val publishedRows = cr.update(
                    destinationUri,
                    ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                        put(
                            MediaStore.MediaColumns.DATE_MODIFIED,
                            System.currentTimeMillis() / 1000,
                        )
                    },
                    null, null
                )
                if (publishedRows <= 0) {
                    cr.delete(destinationUri, null, null)
                    return@withContext false
                }
                destinationPublished = true

                cr.delete(srcUri, null, null) > 0
            } catch (exception: Exception) {
                if (!destinationPublished) {
                    destinationUri?.let { uri ->
                        cr.delete(uri, null, null)
                    }
                }
                printWarning("Cross-volume move failed: ${exception.message}")
                false
            }
        }
    }

    override suspend fun <T : Media> deleteMediaGPSMetadata(media: T): Boolean =
        context.updateMediaExif(
            media = media,
            action = { deleteGpsMetadata() },
            postAction = {
                context.retrieveExtraMediaMetadata(
                    isolatedParser = isolatedParser,
                    geocoder = geocoder,
                    media = it,
                    usePerFileIsolation = shouldUsePerFileIsolation(),
                )?.let { metadata ->
                    database.getMetadataDao().addMetadata(metadata)
                }
            }
        )

    override suspend fun <T : Media> deleteMediaMetadata(media: T): Boolean =
        context.updateMediaExif(
            media = media,
            action = { deleteMetadata() },
            postAction = {
                context.retrieveExtraMediaMetadata(
                    isolatedParser = isolatedParser,
                    geocoder = geocoder,
                    media = it,
                    usePerFileIsolation = shouldUsePerFileIsolation(),
                )?.let { metadata ->
                    database.getMetadataDao().addMetadata(metadata)
                }
            }
        )

    override suspend fun <T : Media> updateMediaDescription(
        media: T,
        description: String
    ): Boolean {
        return if (media.isVideo) {
            // For videos, store description in the local database only
            // Video files don't support EXIF metadata like images do
            withContext(Dispatchers.IO) {
                runCatching {
                    database.getMetadataDao().upsertImageDescription(
                        mediaId = media.id,
                        description = description,
                        imageWidth = 0,
                        imageHeight = 0
                    )
                    true
                }.getOrElse {
                    printWarning("Failed to update video description in database: ${it.message}")
                    false
                }
            }
        } else {
            // For images, update EXIF metadata in the file
            context.updateMediaExif(
                media = media,
                action = { updateImageDescription(description) },
                postAction = {
                    context.retrieveExtraMediaMetadata(
                        isolatedParser = isolatedParser,
                        geocoder = geocoder,
                        media = it,
                        usePerFileIsolation = shouldUsePerFileIsolation(),
                    )?.let { metadata ->
                        database.getMetadataDao().addMetadata(metadata)
                    }
                }
            )
        }
    }

    override suspend fun saveImage(
        bitmap: Bitmap,
        format: Bitmap.CompressFormat,
        mimeType: String,
        relativePath: String,
        displayName: String
    ) = contentResolver.saveImage(bitmap, format, mimeType, relativePath, displayName)

    override fun getTimelineSettings(): Flow<TimelineSettings?> =
        database.getMediaDao().getTimelineSettings()

    override suspend fun updateTimelineSettings(settings: TimelineSettings) {
        database.getMediaDao().setTimelineSettings(settings)
    }

    override fun <Result> getSetting(
        key: Preferences.Key<Result>,
        defaultValue: Result
    ): Flow<Result> {
        return context.dataStore.data.map { it[key] ?: defaultValue }
    }

    override fun getClassifiedCategories(): Flow<List<String>> =
        database.getClassifierDao().getCategoriesFlow()

    override fun getClassifiedMediaByCategory(category: String?): Flow<List<ClassifiedMedia>> =
        if (!category.isNullOrEmpty())
            database.getClassifierDao().getClassifiedMediaByCategoryFlow(category)
        else emptyFlow()

    override fun getClassifiedMediaByMostPopularCategory(): Flow<List<ClassifiedMedia>> =
        database.getClassifierDao().getClassifiedMediaByMostPopularCategoryFlow()

    override suspend fun deleteClassifications() {
        database.getClassifierDao().deleteAllClassifiedMedia()
    }

    override fun getCategoriesWithMedia(): Flow<List<ClassifiedMedia>> =
        database.getClassifierDao().getCategoriesWithMedia()

    override fun getClassifiedMediaCount(): Flow<Int> =
        database.getClassifierDao().getClassifiedMediaCount()

    override suspend fun getCategoryForMediaId(mediaId: Long): String? {
        return database.getClassifierDao().getCategoryForMediaId(mediaId)
    }

    override fun getClassifiedMediaCountAtCategory(category: String): Flow<Int> =
        database.getClassifierDao().getClassifiedMediaCountAtCategory(category)

    override fun getClassifiedMediaThumbnailByCategory(category: String): Flow<ClassifiedMedia?> =
        database.getClassifierDao().getClassifiedMediaThumbnailByCategory(category)

    override suspend fun changeCategory(mediaId: Long, newCategory: String) =
        database.getClassifierDao().changeCategory(mediaId, newCategory)

    // ============ New Category System Implementation ============

    private val categoryDao get() = database.getCategoryDao()

    override suspend fun createCategory(category: Category): Long =
        categoryDao.insertCategory(category)

    override suspend fun updateCategory(category: Category) =
        categoryDao.updateCategory(category)

    override suspend fun deleteCategory(categoryId: Long) =
        categoryDao.deleteCategoryById(categoryId)

    override fun getCategory(categoryId: Long): Flow<Category?> =
        categoryDao.getCategoryByIdFlow(categoryId)

    override suspend fun getCategoryAsync(categoryId: Long): Category? =
        categoryDao.getCategoryById(categoryId)

    override fun getAllCategories(): Flow<List<Category>> =
        categoryDao.getAllCategories()

    override suspend fun getAllCategoriesAsync(): List<Category> =
        categoryDao.getAllCategoriesAsync()

    override fun getCategoriesWithMediaCount(): Flow<List<CategoryWithMediaCount>> =
        categoryDao.getCategoriesWithMediaCount()

    override fun getCategoryCount(): Flow<Int> =
        categoryDao.getCategoryCount()

    override fun getTopCategories(limit: Int): Flow<List<CategoryWithMediaCount>> =
        categoryDao.getTopCategoriesByMediaCount(limit)

    override suspend fun updateCategoryThreshold(categoryId: Long, threshold: Float) =
        categoryDao.updateCategoryThreshold(categoryId, threshold)

    override suspend fun updateCategoryName(categoryId: Long, name: String) =
        categoryDao.updateCategoryName(categoryId, name)

    override suspend fun toggleCategoryPinned(categoryId: Long, isPinned: Boolean) =
        categoryDao.updateCategoryPinned(categoryId, isPinned)

    override fun getMediaIdsInCategory(categoryId: Long): Flow<List<Long>> =
        categoryDao.getMediaIdsInCategory(categoryId)

    override suspend fun getMediaIdsInCategoryAsync(categoryId: Long): List<Long> =
        categoryDao.getMediaIdsInCategoryAsync(categoryId)

    override fun getCategoriesForMedia(mediaId: Long): Flow<List<Category>> =
        categoryDao.getCategoriesForMedia(mediaId)

    override suspend fun addMediaToCategory(
        mediaId: Long,
        categoryId: Long,
        similarity: Float,
        isManual: Boolean
    ) = categoryDao.insertMediaCategory(
        MediaCategory(
            mediaId = mediaId,
            categoryId = categoryId,
            similarityScore = similarity,
            isManuallyAdded = isManual
        )
    )

    override suspend fun removeMediaFromCategory(mediaId: Long, categoryId: Long) =
        categoryDao.removeMediaFromCategory(mediaId, categoryId)

    override fun getMediaCountInCategory(categoryId: Long): Flow<Int> =
        categoryDao.getMediaCountInCategory(categoryId)

    override fun getThumbnailMediaIdForCategory(categoryId: Long): Flow<Long?> =
        categoryDao.getThumbnailMediaIdForCategory(categoryId)

    override suspend fun initializeDefaultCategories() {
        val existingCategories = categoryDao.getAllCategoriesAsync()
        if (existingCategories.isEmpty()) {
            categoryDao.insertCategories(Category.DEFAULT_CATEGORIES)
        }
    }

    override suspend fun resetCategoryData() =
        categoryDao.resetAllCategoryData()

    override fun getMetadata(media: Media): Flow<MediaMetadata> {
        return database.getMetadataDao().getFullMetadata(media.id).map { it.toMediaMetadata() }
    }

    override fun getMetadata(): Flow<List<MediaMetadata>> {
        return database.getMetadataDao().getFullMetadata().map { list ->
            list.map { it.toMediaMetadata() }
        }
    }

    override suspend fun updateAlbumThumbnail(
        albumId: Long,
        thumbnail: Uri
    ) = database.getAlbumThumbnailDao().updateAlbumThumbnail(AlbumThumbnail(albumId, thumbnail))

    override suspend fun deleteAlbumThumbnail(albumId: Long) =
        database.getAlbumThumbnailDao().deleteAlbumThumbnail(albumId)

    override fun getAlbumThumbnail(albumId: Long): Flow<AlbumThumbnail?> =
        database.getAlbumThumbnailDao().getAlbumThumbnail(albumId)

    override fun hasAlbumThumbnail(albumId: Long): Flow<Boolean> =
        database.getAlbumThumbnailDao().hasAlbumThumbnail(albumId)

    override fun getAlbumThumbnails(): Flow<List<AlbumThumbnail>> =
        database.getAlbumThumbnailDao().getAlbumThumbnailsFlow()

    override suspend fun collectMetadataFor(media: Media) {
        context.retrieveExtraMediaMetadata(
            isolatedParser = isolatedParser,
            geocoder = geocoder,
            media = media,
            usePerFileIsolation = shouldUsePerFileIsolation(),
        )?.let { metadata ->
            database.getMetadataDao().addMetadata(metadata)
        }
    }

    override suspend fun addImageEmbedding(imageEmbedding: ImageEmbedding) {
        database.getImageEmbeddingDao().addImageEmbedding(imageEmbedding)
    }

    override suspend fun getRecord(id: Long): ImageEmbedding? {
        return database.getImageEmbeddingDao().getRecord(id)
    }

    override suspend fun getImageEmbeddingStampPage(
        afterId: Long,
        limit: Int,
    ): List<ImageEmbeddingStamp> {
        return database.getImageEmbeddingDao().getStampPage(afterId = afterId, limit = limit)
    }

    override suspend fun getImageEmbeddingPage(afterId: Long, limit: Int): List<ImageEmbedding> {
        return database.getImageEmbeddingDao().getPage(afterId = afterId, limit = limit)
    }

    override suspend fun getImageEmbeddingsByIds(ids: Set<Long>): List<ImageEmbedding> {
        return flatMapIdChunks(ids = ids) { idChunk ->
            database.getImageEmbeddingDao().getByIds(ids = idChunk)
        }
    }

    // ============ Album Groups ============

    override suspend fun insertAlbumGroup(group: AlbumGroup): Long =
        database.getAlbumGroupDao().insertGroup(group)

    override suspend fun updateAlbumGroup(group: AlbumGroup) =
        database.getAlbumGroupDao().updateGroup(group)

    override suspend fun deleteAlbumGroup(groupId: Long) =
        database.getAlbumGroupDao().deleteGroup(groupId)

    override fun getAllAlbumGroups(): Flow<List<AlbumGroup>> =
        database.getAlbumGroupDao().getAllGroups()

    override fun getAlbumGroup(groupId: Long): Flow<AlbumGroup?> =
        database.getAlbumGroupDao().getGroup(groupId)

    override suspend fun getAlbumGroupAsync(groupId: Long): AlbumGroup? =
        database.getAlbumGroupDao().getGroupAsync(groupId)

    override suspend fun addAlbumToGroup(member: AlbumGroupMember) =
        database.getAlbumGroupDao().addAlbumToGroup(member)

    override suspend fun removeAlbumFromGroup(member: AlbumGroupMember) =
        database.getAlbumGroupDao().removeAlbumFromGroup(member)

    override suspend fun removeAllAlbumsFromGroup(groupId: Long) =
        database.getAlbumGroupDao().removeAllAlbumsFromGroup(groupId)

    override fun getAlbumIdsInGroup(groupId: Long): Flow<List<Long>> =
        database.getAlbumGroupDao().getAlbumIdsInGroup(groupId)

    override fun getAllGroupMembers(): Flow<List<AlbumGroupMember>> =
        database.getAlbumGroupDao().getAllGroupMembers()

    override suspend fun getGroupIdForAlbum(albumId: Long): Long? =
        database.getAlbumGroupDao().getGroupIdForAlbum(albumId)

    // ============ Merged Subfolder Albums ============

    override suspend fun insertMergedSubfolderAlbum(mergedSubfolderAlbum: MergedSubfolderAlbum) =
        database.getMergedSubfolderDao().insertMergedSubfolderAlbum(mergedSubfolderAlbum)

    override suspend fun removeMergedSubfolderAlbum(mergedSubfolderAlbum: MergedSubfolderAlbum) =
        database.getMergedSubfolderDao().removeMergedSubfolderAlbum(mergedSubfolderAlbum)

    override fun getMergedSubfolderAlbums(): Flow<List<MergedSubfolderAlbum>> =
        database.getMergedSubfolderDao().getMergedSubfolderAlbums()

    // ============ Collections ============

    private val collectionDao get() = database.getCollectionDao()

    override suspend fun insertCollection(collection: Collection): Long =
        collectionDao.insertCollection(collection)

    override suspend fun updateCollection(collection: Collection) =
        collectionDao.updateCollection(collection)

    override suspend fun deleteCollection(collectionId: Long) =
        collectionDao.deleteCollection(collectionId)

    override fun getCollection(collectionId: Long): Flow<Collection?> =
        collectionDao.getCollectionFlow(collectionId)

    override suspend fun getCollectionAsync(collectionId: Long): Collection? =
        collectionDao.getCollectionAsync(collectionId)

    override fun getAllCollections(): Flow<List<Collection>> =
        collectionDao.getAllCollections()

    override fun getCollectionsWithCount(): Flow<List<CollectionWithCount>> =
        collectionDao.getCollectionsWithCount().map { list ->
            list.map { it.toCollectionWithCount() }
        }

    override suspend fun updateCollectionLabel(collectionId: Long, label: String) =
        collectionDao.updateCollectionLabel(collectionId, label)

    override suspend fun toggleCollectionPinned(collectionId: Long, isPinned: Boolean) =
        collectionDao.updateCollectionPinned(collectionId, isPinned)

    override suspend fun updateCollectionCover(collectionId: Long, mediaId: Long?) =
        collectionDao.updateCollectionCover(collectionId, mediaId)

    override suspend fun addMediaToCollection(collectionId: Long, mediaId: Long) =
        collectionDao.addMediaToCollection(CollectionMedia(collectionId, mediaId))

    override suspend fun addMediaListToCollection(collectionId: Long, mediaIds: List<Long>) =
        collectionDao.addMediaListToCollection(
            mediaIds.map { CollectionMedia(collectionId, it) }
        )

    override suspend fun removeMediaFromCollection(collectionId: Long, mediaId: Long) =
        collectionDao.removeMediaFromCollection(collectionId, mediaId)

    override fun getMediaIdsInCollection(collectionId: Long): Flow<List<Long>> =
        collectionDao.getMediaIdsInCollection(collectionId)

    override suspend fun getMediaIdsInCollectionAsync(collectionId: Long): List<Long> =
        collectionDao.getMediaIdsInCollectionAsync(collectionId)

    override fun getMediaCountInCollection(collectionId: Long): Flow<Int> =
        collectionDao.getMediaCountInCollection(collectionId)

    override fun getCollectionIdsForMedia(mediaId: Long): Flow<List<Long>> =
        collectionDao.getCollectionIdsForMedia(mediaId)

    override suspend fun cleanupOrphanedCollectionMedia(validMediaIds: List<Long>) =
        collectionDao.cleanupOrphanedCollectionMedia(validMediaIds)

    override suspend fun addAlbumsToCollection(collectionId: Long, albumIds: List<Long>) {
        collectionDao.addAlbumsToCollection(
            albumIds.map { com.dot.gallery.feature_node.data.model.CollectionAlbum(collectionId, it) }
        )
    }

    override suspend fun removeAlbumFromCollection(collectionId: Long, albumId: Long) =
        collectionDao.removeAlbumFromCollection(collectionId, albumId)

    override fun getAllAlbumIdsInCollections(): Flow<List<Long>> =
        collectionDao.getAllAlbumIdsInCollections()

    override fun getAlbumIdsInCollection(collectionId: Long): Flow<List<Long>> =
        collectionDao.getAlbumIdsInCollection(collectionId)

    companion object {
        private fun relativePath(newPath: String) = ContentValues().apply {
            put(MediaStore.MediaColumns.RELATIVE_PATH, newPath)
        }
    }
}
