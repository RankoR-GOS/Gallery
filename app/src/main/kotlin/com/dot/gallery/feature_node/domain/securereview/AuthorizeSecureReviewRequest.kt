package com.dot.gallery.feature_node.domain.securereview

import android.app.ComponentCaller
import android.content.ClipData
import android.content.ContentResolver
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import com.dot.gallery.feature_node.data.model.securereview.AuthorizedSecureReviewRequest
import kotlinx.collections.immutable.toImmutableList
import javax.inject.Inject

internal fun interface AuthorizeSecureReviewRequest {
    operator fun invoke(
        intent: Intent,
        caller: ComponentCaller,
    ): AuthorizedSecureReviewRequest?

    companion object {
        const val MAXIMUM_URI_COUNT = 100
    }
}

internal class AuthorizeSecureReviewRequestImpl @Inject constructor() :
    AuthorizeSecureReviewRequest {

    override fun invoke(
        intent: Intent,
        caller: ComponentCaller,
    ): AuthorizedSecureReviewRequest? {
        if (intent.action != MediaStore.ACTION_REVIEW_SECURE) {
            return null
        }

        val distinctUris = getSuppliedUris(intent = intent)?.distinct()

        return when {
            distinctUris == null -> null
            distinctUris.any { uri -> !isSupportedUri(uri = uri) } -> null
            distinctUris.any { uri -> !canCallerReadUri(caller = caller, uri = uri) } -> null
            else -> {
                AuthorizedSecureReviewRequest(
                    uris = distinctUris
                        .toImmutableList(),
                )
            }
        }
    }

    private fun getSuppliedUris(intent: Intent): List<Uri>? {
        val primaryUri = intent.data
        val clipData = intent.clipData

        return when {
            primaryUri == null -> null
            clipData == null -> listOf(primaryUri)
            exceedsMaximumUriCount(clipData = clipData) -> null

            else -> {
                getSecondaryUris(clipData = clipData)?.let { secondaryUris ->
                    listOf(primaryUri) + secondaryUris
                }
            }
        }
    }

    private fun exceedsMaximumUriCount(clipData: ClipData): Boolean {
        val primaryUriCount = 1
        val suppliedUriCount = primaryUriCount + clipData.itemCount

        return suppliedUriCount > AuthorizeSecureReviewRequest.MAXIMUM_URI_COUNT
    }

    private fun getSecondaryUris(clipData: ClipData): List<Uri>? {
        val secondaryUris = (0 until clipData.itemCount).map { index ->
            getClipItemUri(item = clipData.getItemAt(index))
        }

        return when {
            secondaryUris.any { uri -> uri == null } -> null
            else -> secondaryUris.filterNotNull()
        }
    }

    private fun getClipItemUri(item: ClipData.Item): Uri? {
        return item.uri?.takeIf {
            item.intent == null && item.text == null && item.htmlText == null
        }
    }

    private fun isSupportedUri(uri: Uri): Boolean {
        return uri.scheme == ContentResolver.SCHEME_CONTENT &&
                !uri.isOpaque &&
                !uri.authority.isNullOrBlank() &&
                uri.fragment == null
    }

    private fun canCallerReadUri(caller: ComponentCaller, uri: Uri): Boolean {
        return try {
            caller.checkContentUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            ) == PackageManager.PERMISSION_GRANTED
        } catch (_: IllegalArgumentException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}
