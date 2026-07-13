package com.dot.gallery.feature_node.domain.securereview

import android.app.ComponentCaller
import android.content.ClipData
import android.content.ClipDescription
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
internal class AuthorizeSecureReviewRequestTest {

    private val authorizeSecureReviewRequest = AuthorizeSecureReviewRequestImpl()

    @Test
    fun invoke_acceptsAuthorizedPrimaryAndSecondaryUrisInOrder() {
        val primaryUri = Uri.parse("content://media/external/images/media/1")
        val secondaryUri = Uri.parse("content://camera/review/2")
        val caller = permissionCaller(
            primaryUri to PackageManager.PERMISSION_GRANTED,
            secondaryUri to PackageManager.PERMISSION_GRANTED,
        )
        val intent = secureReviewIntent(
            primaryUri = primaryUri,
            secondaryUris = listOf(secondaryUri),
        )

        val request = authorizeSecureReviewRequest(intent = intent, caller = caller)

        assertEquals(listOf(primaryUri, secondaryUri), request?.uris)
    }

    @Test
    fun invoke_deduplicatesUrisWithoutChangingOrder() {
        val primaryUri = Uri.parse("content://media/external/images/media/1")
        val secondaryUri = Uri.parse("content://camera/review/2")
        val caller = permissionCaller(
            primaryUri to PackageManager.PERMISSION_GRANTED,
            secondaryUri to PackageManager.PERMISSION_GRANTED,
        )
        val intent = secureReviewIntent(
            primaryUri = primaryUri,
            secondaryUris = listOf(primaryUri, secondaryUri, primaryUri),
        )

        val request = authorizeSecureReviewRequest(intent = intent, caller = caller)

        assertEquals(listOf(primaryUri, secondaryUri), request?.uris)
    }

    @Test
    fun invoke_rejectsWrongActionAndMissingPrimaryUri() {
        val caller = mockk<ComponentCaller>(relaxed = true)

        assertNull(
            authorizeSecureReviewRequest(
                intent = Intent(Intent.ACTION_VIEW).setData(Uri.parse("content://camera/1")),
                caller = caller,
            ),
        )
        assertNull(
            authorizeSecureReviewRequest(
                intent = Intent(MediaStore.ACTION_REVIEW_SECURE),
                caller = caller,
            ),
        )
    }

    @Test
    fun invoke_rejectsNonContentOpaqueBlankAuthorityAndFragmentUris() {
        val caller = mockk<ComponentCaller>(relaxed = true)
        val invalidUris = listOf(
            Uri.parse("file:///storage/emulated/0/DCIM/image.jpg"),
            Uri.parse("https://example.com/image.jpg"),
            Uri.parse("content:opaque"),
            Uri.parse("content:///image/1"),
            Uri.parse("content://camera/image/1#fragment"),
        )

        invalidUris.forEach { uri ->
            assertNull(
                authorizeSecureReviewRequest(
                    intent = secureReviewIntent(primaryUri = uri),
                    caller = caller,
                ),
            )
        }
    }

    @Test
    fun invoke_rejectsClipDataItemsContainingAnythingOtherThanUri() {
        val primaryUri = Uri.parse("content://camera/image/1")
        val caller = permissionCaller(primaryUri to PackageManager.PERMISSION_GRANTED)
        val malformedItems = listOf(
            ClipData.Item("text"),
            ClipData.Item(Intent(Intent.ACTION_VIEW)),
            ClipData.Item("text", "<b>text</b>"),
        )

        malformedItems.forEach { item ->
            val intent = secureReviewIntent(primaryUri = primaryUri).apply {
                clipData = ClipData(
                    ClipDescription("review", arrayOf("image/*")),
                    item,
                )
            }

            assertNull(authorizeSecureReviewRequest(intent = intent, caller = caller))
        }
    }

    @Test
    fun invoke_rejectsMoreThanMaximumUris() {
        val primaryUri = Uri.parse("content://camera/image/primary")
        val secondaryUris = (1..AuthorizeSecureReviewRequest.MAXIMUM_URI_COUNT).map { index ->
            Uri.parse("content://camera/image/$index")
        }

        assertNull(
            authorizeSecureReviewRequest(
                intent = secureReviewIntent(
                    primaryUri = primaryUri,
                    secondaryUris = secondaryUris,
                ),
                caller = mockk(relaxed = true),
            ),
        )
    }

    @Test
    fun invoke_acceptsMaximumNumberOfUris() {
        val primaryUri = Uri.parse("content://camera/image/primary")
        val secondaryUris = (1 until AuthorizeSecureReviewRequest.MAXIMUM_URI_COUNT).map { index ->
            Uri.parse("content://camera/image/$index")
        }
        val caller = permissionCaller(
            *(listOf(primaryUri) + secondaryUris)
                .map { uri -> uri to PackageManager.PERMISSION_GRANTED }
                .toTypedArray(),
        )

        val request = authorizeSecureReviewRequest(
            intent = secureReviewIntent(
                primaryUri = primaryUri,
                secondaryUris = secondaryUris,
            ),
            caller = caller,
        )

        assertEquals(AuthorizeSecureReviewRequest.MAXIMUM_URI_COUNT, request?.uris?.size)
    }

    @Test
    fun invoke_rejectsRequestWhenAnyUriIsNotReadableByCaller() {
        val primaryUri = Uri.parse("content://camera/image/1")
        val deniedUri = Uri.parse("content://camera/image/2")
        val caller = permissionCaller(
            primaryUri to PackageManager.PERMISSION_GRANTED,
            deniedUri to PackageManager.PERMISSION_DENIED,
        )

        assertNull(
            authorizeSecureReviewRequest(
                intent = secureReviewIntent(
                    primaryUri = primaryUri,
                    secondaryUris = listOf(deniedUri),
                ),
                caller = caller,
            ),
        )
    }

    @Test
    fun invoke_rejectsPermissionCheckExceptions() {
        val uri = Uri.parse("content://camera/image/1")
        val exceptions = listOf(
            IllegalArgumentException("not launch tracked"),
            SecurityException("receiver cannot access URI"),
        )

        exceptions.forEach { exception ->
            val caller = mockk<ComponentCaller>()
            every {
                caller.checkContentUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } throws exception

            assertNull(
                authorizeSecureReviewRequest(
                    intent = secureReviewIntent(primaryUri = uri),
                    caller = caller,
                ),
            )
        }
    }

    @Test
    fun invoke_preservesExactUriObject() {
        val primaryUri = Uri.parse("content://camera/image/1?token=secret")
        val caller = permissionCaller(primaryUri to PackageManager.PERMISSION_GRANTED)

        val request = authorizeSecureReviewRequest(
            intent = secureReviewIntent(primaryUri = primaryUri),
            caller = caller,
        )

        assertSame(primaryUri, request?.uris?.single())
    }

    private fun secureReviewIntent(
        primaryUri: Uri,
        secondaryUris: List<Uri> = emptyList(),
    ): Intent {
        return Intent(MediaStore.ACTION_REVIEW_SECURE).apply {
            data = primaryUri
            if (secondaryUris.isNotEmpty()) {
                clipData = ClipData(
                    ClipDescription("review", arrayOf("image/*")),
                    ClipData.Item(secondaryUris.first()),
                ).apply {
                    secondaryUris.drop(1).forEach { uri ->
                        addItem(ClipData.Item(uri))
                    }
                }
            }
        }
    }

    private fun permissionCaller(
        vararg permissions: Pair<Uri, Int>,
    ): ComponentCaller {
        val caller = mockk<ComponentCaller>()
        permissions.forEach { (uri, permission) ->
            every {
                caller.checkContentUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } returns permission
        }
        return caller
    }
}
