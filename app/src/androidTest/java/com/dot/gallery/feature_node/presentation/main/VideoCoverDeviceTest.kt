package com.dot.gallery.feature_node.presentation.main

import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
import android.os.SystemClock
import android.provider.MediaStore
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dot.gallery.core.dataStore
import com.dot.gallery.feature_node.presentation.mediaview.components.video.VideoPlayerViewModel
import com.dot.gallery.feature_node.presentation.standalone.StandaloneActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(UnstableApi::class)
internal class VideoCoverDeviceTest {
    @Test
    fun emptyVideoSurfaceMatchesViewerInEveryThemeAndBlurMode() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val resolver = context.contentResolver
        val keys = listOf("force_theme", "dark_mode", "allow_blur", "secure_mode").map { booleanPreferencesKey(it) }
        val previous = runBlocking { context.dataStore.data.first() }
        val uri = requireNotNull(resolver.insert(
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "video-cover-${System.nanoTime()}.mp4")
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/GalleryPortCheck")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            },
        ))
        try {
            instrumentation.context.assets.open("GalleryDecoderTest.mp4").use { input ->
                requireNotNull(resolver.openOutputStream(uri)).use { output -> input.copyTo(output) }
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            runBlocking {
                context.dataStore.edit { preferences ->
                    preferences[keys[0]] = true
                    preferences[keys[1]] = false
                    preferences[keys[2]] = true
                    preferences[keys[3]] = false
                }
            }
            val intent = Intent(context, StandaloneActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                setDataAndType(uri, "video/mp4")
            }
            ActivityScenario.launch<StandaloneActivity>(intent).use { scenario ->
                var viewModel: VideoPlayerViewModel? = null
                waitUntil {
                    scenario.onActivity { activity ->
                        viewModel = activity.viewModelStore["video:${ContentUris.parseId(uri)}"] as? VideoPlayerViewModel
                    }
                    viewModel != null
                }
                scenario.onActivity { activity ->
                    assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE == 0)
                    requireNotNull(viewModel).player.clearMediaItems()
                }
                for (dark in listOf(false, true)) {
                    for (blur in listOf(true, false)) {
                        runBlocking {
                            context.dataStore.edit { preferences ->
                                preferences[keys[1]] = dark
                                preferences[keys[2]] = blur
                            }
                        }
                        // Let the background animation settle while the empty SurfaceView cover remains visible.
                        SystemClock.sleep(700)
                        val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                        try {
                            val color = screenshot.getPixel(screenshot.width / 3, screenshot.height / 2)
                            val channels = listOf(Color.red(color), Color.green(color), Color.blue(color))
                            assertTrue("dark=$dark blur=$blur cover=$channels",
                                when {
                                    dark || blur -> channels.all { it < 5 }
                                    else -> channels.all { it > 250 }
                                })
                        } finally {
                            screenshot.recycle()
                        }
                    }
                }
            }
        } finally {
            resolver.delete(uri, null, null)
            runBlocking {
                context.dataStore.edit { preferences ->
                    for (key in keys) {
                        val value = previous[key]
                        when (value) {
                            null -> preferences.remove(key)
                            else -> preferences[key] = value
                        }
                    }
                }
            }
        }
    }

    private fun waitUntil(predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10000L
        while (!predicate()) {
            check(SystemClock.uptimeMillis() < deadline) { "Video ViewModel did not appear" }
            SystemClock.sleep(50)
        }
    }
}
