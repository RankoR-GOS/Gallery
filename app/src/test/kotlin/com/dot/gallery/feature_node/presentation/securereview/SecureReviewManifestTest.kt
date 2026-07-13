package com.dot.gallery.feature_node.presentation.securereview

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import com.dot.gallery.feature_node.presentation.standalone.StandaloneActivity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
internal class SecureReviewManifestTest {

    @Test
    fun secureReviewIntentWithMimeType_resolvesOnlyToSecureReviewActivity() {
        val activityNames = resolveActivityNames(action = MediaStore.ACTION_REVIEW_SECURE)

        assertEquals(setOf(SecureReviewActivity::class.java.name), activityNames)
    }

    @Test
    fun secureReviewIntentWithoutMimeType_resolvesOnlyToSecureReviewActivity() {
        val activityNames = resolveActivityNames(
            action = MediaStore.ACTION_REVIEW_SECURE,
            mimeType = null,
        )

        assertEquals(setOf(SecureReviewActivity::class.java.name), activityNames)
    }

    @Test
    fun ordinaryReviewIntent_resolvesOnlyToStandaloneActivity() {
        val activityNames = resolveActivityNames(action = MediaStore.ACTION_REVIEW)

        assertEquals(setOf(StandaloneActivity::class.java.name), activityNames)
    }

    @Suppress("DEPRECATION")
    private fun resolveActivityNames(
        action: String,
        mimeType: String? = "image/jpeg",
    ): Set<String> {
        val application = RuntimeEnvironment.getApplication()
        val uri = Uri.parse("content://camera/image/1")
        val intent = Intent(action).apply {
            when (mimeType) {
                null -> data = uri
                else -> setDataAndType(uri, mimeType)
            }
            setPackage(application.packageName)
        }

        return application.packageManager
            .queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .map { resolveInfo -> resolveInfo.activityInfo.name }
            .toSet()
    }
}
