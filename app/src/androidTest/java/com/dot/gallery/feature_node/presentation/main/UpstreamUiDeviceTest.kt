package com.dot.gallery.feature_node.presentation.main

import android.content.ContentValues
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dot.gallery.R
import com.dot.gallery.core.Constants.mosaicColumnsList
import com.dot.gallery.core.dataStore
import com.dot.gallery.feature_node.presentation.edit.EditActivity
import com.dot.gallery.feature_node.presentation.edit.EditViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Uses disposable owned images; no screenshots or accessibility trees leave the device. */
@RunWith(AndroidJUnit4::class)
internal class UpstreamUiDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation = instrumentation.uiAutomation
    private val fixtureNames = mutableSetOf<String>()
    private val fixtureUris = mutableListOf<Uri>()

    @Before
    fun createFixtures() {
        val bitmap = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(40, 100, 160))
        val prefix = "GalleryPortCheck-${System.nanoTime()}"
        repeat(32) { index ->
            val name = "$prefix-$index.png"
            fixtureNames.add(name)
            val uri = requireNotNull(context.contentResolver.insert(
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/GalleryPortCheck")
                    put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis() + index)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                },
            ))
            fixtureUris.add(uri)
            requireNotNull(context.contentResolver.openOutputStream(uri)).use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
            context.contentResolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null)
        }
        bitmap.recycle()
    }

    @After
    fun deleteFixtures() {
        fixtureUris.forEach { context.contentResolver.delete(it, null, null) }
    }

    @Test
    fun gridPinchNavigationStyleAndViewerReturnRemainUsable() {
        val layoutKey = stringPreferencesKey("timeline_layout_type")
        val navbarKey = booleanPreferencesKey("old_navbar")
        val previous = runBlocking { context.dataStore.data.first() }
        val gridPreferences = context.getSharedPreferences("ui_settings", 0)
        val gridKeys = listOf("mosaic_grid_size", "mosaic_grid_size_landscape")
        val previousGrid = gridKeys.associateWith { key ->
            when {
                gridPreferences.contains(key) -> gridPreferences.getInt(key, 0)
                else -> null
            }
        }
        try {
            runBlocking { context.dataStore.edit { it[layoutKey] = "mosaic" } }
            ActivityScenario.launch(MainActivity::class.java).use {
                waitUntil { findFixture() != null }
                val gridKey = when (context.resources.configuration.orientation) {
                    Configuration.ORIENTATION_LANDSCAPE -> "mosaic_grid_size_landscape"
                    else -> "mosaic_grid_size"
                }
                repeat(4) {
                    val before = gridPreferences.getInt(gridKey, mosaicColumnsList.indexOf(4))
                    pinch(
                        bounds = boundsOf(requireNotNull(findFixture())),
                        expand = before < mosaicColumnsList.lastIndex,
                    )
                    waitUntil { gridPreferences.getInt(gridKey, before) != before }
                    waitUntil { findFixture() != null }
                }
                for (classic in listOf(true, false)) {
                    runBlocking { context.dataStore.edit { it[navbarKey] = classic } }
                    instrumentation.waitForIdleSync()
                    waitUntil { findFixture() != null }
                }
                runBlocking { context.dataStore.edit { it[layoutKey] = "grid" } }
                instrumentation.waitForIdleSync()
                waitUntil { findFixture() != null }
                SystemClock.sleep(1000)
                val media = requireNotNull(findFixture())
                val clickTarget = requireNotNull(media.parent)
                assertTrue(clickTarget.isClickable)
                assertTrue(clickTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                waitUntil { findLabel("Go back") != null }
                pressBack()
                waitUntil { findFixture() != null }
            }
        } finally {
            runBlocking {
                context.dataStore.edit { preferences ->
                    previous[layoutKey]?.let { preferences[layoutKey] = it } ?: preferences.remove(layoutKey)
                    previous[navbarKey]?.let { preferences[navbarKey] = it } ?: preferences.remove(navbarKey)
                }
            }
            gridPreferences.edit().apply {
                previousGrid.forEach { (key, value) ->
                    when (value) {
                        null -> remove(key)
                        else -> putInt(key, value)
                    }
                }
            }.commit()
        }
    }

    @Test
    fun drawingBackCommitsBeforeLeavingMarkup() {
        val intent = Intent(context, EditActivity::class.java).apply {
            data = fixtureUris.first()
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ActivityScenario.launch<EditActivity>(intent).use { scenario ->
            lateinit var viewModel: EditViewModel
            scenario.onActivity { activity -> viewModel = ViewModelProvider(activity)[EditViewModel::class.java] }
            waitUntil { !viewModel.isSaving.value && viewModel.currentBitmap.value != null }
            clickLabel(context.getString(R.string.markup))
            clickLabel(context.getString(R.string.type_stylus))
            waitUntil { findLabel(context.getString(R.string.editor_cancel_markup)) != null }
            SystemClock.sleep(1000)
            val display = Rect()
            requireNotNull(automation.rootInActiveWindow).getBoundsInScreen(display)
            val downTime = SystemClock.uptimeMillis()
            val startX = display.width() * 0.35f
            val endX = display.width() * 0.65f
            val y = display.height() * 0.40f
            sendSingle(downTime = downTime, action = MotionEvent.ACTION_DOWN, x = startX, y = y)
            repeat(12) { index ->
                sendSingle(downTime = downTime, action = MotionEvent.ACTION_MOVE,
                    x = startX + (endX - startX) * (index + 1) / 12f, y = y)
                SystemClock.sleep(16)
            }
            sendSingle(downTime = downTime, action = MotionEvent.ACTION_UP, x = endX, y = y)
            waitUntil { viewModel.paths.value.isNotEmpty() }
            val drawnBounds = viewModel.paths.value.first().first.getBounds()
            assertTrue("Stroke has no width: $drawnBounds", drawnBounds.width > 20f)
            pressBack()
            try {
                waitUntil { viewModel.paths.value.isEmpty() && viewModel.appliedAdjustments.value.isNotEmpty() }
            } catch (error: AssertionError) {
                throw AssertionError("Markup state: paths=${viewModel.paths.value.size}, adjustments=${viewModel.appliedAdjustments.value.size}, processing=${viewModel.isProcessing.value}, drawingVisible=${findLabel(context.getString(R.string.editor_cancel_markup)) != null}, pickerVisible=${findLabel(context.getString(R.string.type_stylus)) != null}", error)
            }
            assertTrue(!viewModel.isProcessing.value)
            waitUntil { findLabel(context.getString(R.string.type_stylus)) != null }
        }
    }

    private fun findFixture(): AccessibilityNodeInfo? {
        val display = Rect()
        automation.rootInActiveWindow?.getBoundsInScreen(display) ?: return null
        return findNode { node ->
            val bounds = boundsOf(node)
            node.contentDescription?.toString() in fixtureNames &&
                bounds.centerY() in (display.height() * 0.3f).toInt()..(display.height() * 0.7f).toInt()
        }
    }

    private fun findLabel(label: String): AccessibilityNodeInfo? {
        return findNode { node -> node.text?.toString() == label || node.contentDescription?.toString() == label }
    }

    private fun findNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        automation.clearCache()
        val root = automation.rootInActiveWindow ?: return null
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        pending.add(root)
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            if (node.packageName?.toString() == context.packageName && node.isVisibleToUser && predicate(node)) {
                return node
            }
            repeat(node.childCount) { node.getChild(it)?.let(pending::add) }
        }
        return null
    }

    private fun boundsOf(node: AccessibilityNodeInfo): Rect {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return bounds
    }

    private fun clickLabel(label: String) {
        waitUntil { findLabel(label) != null }
        tap(bounds = boundsOf(requireNotNull(findLabel(label))))
    }

    private fun tap(bounds: Rect) {
        val time = SystemClock.uptimeMillis()
        sendSingle(downTime = time, action = MotionEvent.ACTION_DOWN,
            x = bounds.exactCenterX(), y = bounds.exactCenterY())
        SystemClock.sleep(60)
        sendSingle(downTime = time, action = MotionEvent.ACTION_UP,
            x = bounds.exactCenterX(), y = bounds.exactCenterY())
    }

    private fun sendSingle(downTime: Long, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        try {
            assertTrue(automation.injectInputEvent(event, true))
        } finally {
            event.recycle()
        }
    }

    private fun pinch(bounds: Rect, expand: Boolean) {
        val downTime = SystemClock.uptimeMillis()
        val centerX = bounds.exactCenterX()
        val centerY = bounds.exactCenterY()
        val radius = bounds.width().coerceAtMost(bounds.height()) * 0.3f
        val properties = Array(2) { index -> MotionEvent.PointerProperties().apply {
            id = index
            toolType = MotionEvent.TOOL_TYPE_FINGER
        } }
        fun send(action: Int, fraction: Float, count: Int = 2) {
            val coordinates = Array(count) { index -> MotionEvent.PointerCoords().apply {
                x = centerX + (when (index) { 0 -> -1f; else -> 1f }) * radius * fraction
                y = centerY
                pressure = 1f
                size = 1f
            } }
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, count,
                properties, coordinates, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        val start = when { expand -> 0.35f; else -> 1f }
        val end = when { expand -> 1f; else -> 0.35f }
        send(action = MotionEvent.ACTION_DOWN, fraction = start, count = 1)
        send(action = MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), fraction = start)
        repeat(16) { index ->
            send(action = MotionEvent.ACTION_MOVE, fraction = start + (end - start) * (index + 1) / 16f)
            SystemClock.sleep(16)
        }
        send(action = MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), fraction = end)
        send(action = MotionEvent.ACTION_UP, fraction = end, count = 1)
        instrumentation.waitForIdleSync()
    }

    private fun pressBack() {
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        instrumentation.waitForIdleSync()
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        throw AssertionError("Expected UI-test state was not reached")
    }
}
