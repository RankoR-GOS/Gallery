package com.dot.gallery.feature_node.presentation.securereview

import android.content.Intent
import android.provider.MediaStore
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dot.gallery.feature_node.presentation.standalone.StandaloneActivity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
internal class SecureReviewEntryPointTest {

    @Test
    fun standaloneActivity_rejectsExplicitSecureReviewIntent() {
        val intent = Intent(
            ApplicationProvider.getApplicationContext(),
            StandaloneActivity::class.java,
        ).apply {
            action = MediaStore.ACTION_REVIEW_SECURE
        }

        ActivityScenario.launch<StandaloneActivity>(intent).use { scenario ->
            assertEquals(Lifecycle.State.DESTROYED, scenario.state)
        }
    }

    @Test
    fun secureReviewActivity_rejectsWrongAction() {
        val intent = Intent(
            ApplicationProvider.getApplicationContext(),
            SecureReviewActivity::class.java,
        ).apply {
            action = Intent.ACTION_VIEW
        }

        ActivityScenario.launch<SecureReviewActivity>(intent).use { scenario ->
            assertEquals(Lifecycle.State.DESTROYED, scenario.state)
        }
    }
}
