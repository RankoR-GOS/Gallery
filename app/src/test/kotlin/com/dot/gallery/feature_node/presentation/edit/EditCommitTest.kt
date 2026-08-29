package com.dot.gallery.feature_node.presentation.edit

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.graphics.Path
import com.dot.gallery.feature_node.domain.model.editor.Adjustment
import com.dot.gallery.feature_node.domain.model.editor.PathProperties
import com.dot.gallery.feature_node.presentation.edit.adjustments.varfilter.Brightness
import io.mockk.mockk
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
internal class EditCommitTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun markupCompletionObservesCommittedPixelsAndClearedDrawing() {
        val viewModel = editorWithBitmap()
        viewModel.addPath(path = Path(), properties = PathProperties())
        val overlay = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        overlay.eraseColor(Color.RED)
        val finished = CompletableFuture<Boolean>()
        viewModel.applyDrawing(graphicsImage = overlay, onFinish = { applied ->
            finished.complete(applied && viewModel.paths.value.isEmpty() &&
                viewModel.currentBitmap.value?.getPixel(0, 0) == Color.RED &&
                viewModel.appliedAdjustments.value.size == 1)
        })
        assertTrue(finished.get(10, TimeUnit.SECONDS))
    }

    @Test
    fun failedMarkupKeepsDrawingAndReportsFailure() {
        val viewModel = EditViewModel(repository = mockk(), mediaHandler = mockk())
        viewModel.addPath(path = Path(), properties = PathProperties())
        val finished = CompletableFuture<Boolean>()
        viewModel.applyDrawing(
            graphicsImage = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888),
            onFinish = { finished.complete(it) },
        )
        assertFalse(finished.get(10, TimeUnit.SECONDS))
        assertEquals(1, viewModel.paths.value.size)
        assertTrue(viewModel.appliedAdjustments.value.isEmpty())
    }

    @Test
    fun resettingOnlyAdjustmentRestoresOriginalWithoutAddingHistory() {
        val viewModel = editorWithBitmap()
        applyAndWait(viewModel = viewModel, adjustment = Brightness(value = 0.2f))
        assertEquals(1, viewModel.appliedAdjustments.value.size)
        applyAndWait(viewModel = viewModel, adjustment = Brightness(value = 0f))
        assertTrue(viewModel.appliedAdjustments.value.isEmpty())
        assertEquals(Color.BLACK, viewModel.currentBitmap.value?.getPixel(0, 0))
    }

    private fun editorWithBitmap(): EditViewModel {
        val viewModel = EditViewModel(repository = mockk(), mediaHandler = mockk())
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLACK)
        for (name in listOf("_originalBitmap", "_currentBitmap", "_targetBitmap")) {
            val field = EditViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val state = field.get(viewModel) as MutableStateFlow<Bitmap?>
            state.value = bitmap
        }
        val field = EditViewModel::class.java.getDeclaredField("bitmaps").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val bitmaps = field.get(viewModel) as MutableList<Pair<Bitmap?, Adjustment?>>
        bitmaps.add(bitmap to null)
        return viewModel
    }

    private fun applyAndWait(viewModel: EditViewModel, adjustment: Adjustment) {
        viewModel.applyAdjustment(adjustment = adjustment)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            val committed = when {
                adjustment is Brightness && adjustment.value == 0f ->
                    viewModel.appliedAdjustments.value.isEmpty()
                else -> viewModel.appliedAdjustments.value.contains(adjustment)
            }
            if (committed && !viewModel.isProcessing.value) return
            Thread.sleep(10)
        }
        throw AssertionError("Adjustment did not finish")
    }
}
