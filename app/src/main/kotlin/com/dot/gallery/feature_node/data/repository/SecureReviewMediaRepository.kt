package com.dot.gallery.feature_node.data.repository

import android.content.ContentResolver
import android.net.Uri
import com.dot.gallery.feature_node.data.model.Media
import com.dot.gallery.feature_node.data.model.securereview.AuthorizedSecureReviewRequest
import com.dot.gallery.injection.qualifier.IoDispatcher
import java.util.Locale
import javax.inject.Inject
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

internal interface SecureReviewMediaRepository {

    suspend fun loadMedia(
        request: AuthorizedSecureReviewRequest,
    ): ImmutableList<Media.UriMedia>?
}

internal class SecureReviewMediaRepositoryImpl @Inject constructor(
    private val contentResolver: ContentResolver,
    @param:IoDispatcher
    private val ioDispatcher: CoroutineDispatcher,
) : SecureReviewMediaRepository {

    override suspend fun loadMedia(
        request: AuthorizedSecureReviewRequest,
    ): ImmutableList<Media.UriMedia>? {
        return withContext(ioDispatcher) {
            val mediaItems = request.uris.mapIndexed { index, uri ->
                createReadOnlyMedia(
                    uri = uri,
                    index = index,
                )
            }

            when {
                mediaItems.any { it == null } -> null
                else -> mediaItems.filterNotNull().toImmutableList()
            }
        }
    }

    private fun createReadOnlyMedia(uri: Uri, index: Int): Media.UriMedia? {
        val mimeType = getMimeType(uri = uri)
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.takeIf { mimeType ->
                mimeType.startsWith("image/") || mimeType.startsWith("video/")
            }
            ?: return null

        return Media.UriMedia(
            id = -(index.toLong() + 1L),
            label = uri.lastPathSegment.orEmpty(),
            uri = uri,
            path = "",
            relativePath = "",
            albumID = SECURE_REVIEW_ALBUM_ID,
            albumLabel = "",
            timestamp = 0L,
            expiryTimestamp = null,
            takenTimestamp = null,
            fullDate = "",
            mimeType = mimeType,
            favorite = 0,
            trashed = 0,
            size = 0L,
            duration = null,
        )
    }

    private fun getMimeType(uri: Uri): String? {
        return try {
            contentResolver.getType(uri)
        } catch (_: RuntimeException) {
            null
        }
    }

    companion object {
        private const val SECURE_REVIEW_ALBUM_ID = -99L
    }
}
