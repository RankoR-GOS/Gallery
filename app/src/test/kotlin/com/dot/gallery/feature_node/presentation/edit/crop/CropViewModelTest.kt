package com.dot.gallery.feature_node.presentation.edit.crop

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import com.dot.gallery.feature_node.data.model.editor.crop.CropImage
import com.dot.gallery.feature_node.data.model.editor.crop.ExternalCropRequest
import com.dot.gallery.feature_node.data.model.editor.crop.NormalizedCropRect
import com.dot.gallery.feature_node.data.repository.ExternalCropRepository
import com.dot.gallery.testutil.MainDispatcherRule
import io.mockk.CapturingSlot
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class CropViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun launchRequest_loadsImageIntoUiState() {
        runTest(context = mainDispatcherRule.testDispatcher) {
            val image = cropImage()
            val repository = mockExternalCropRepository(imageToLoad = image)
            val request = externalCropRequest()
            val viewModel = createViewModel(repository = repository)

            viewModel.onLaunchRequest(request = request)
            advanceUntilIdle()

            assertSame(image, viewModel.uiState.value.image)
            assertEquals(1f, viewModel.uiState.value.aspectRatio)
            coVerify(exactly = 1) {
                repository.loadImage(uri = request.sourceUri)
            }
        }
    }

    @Test
    fun duplicateLaunchRequest_doesNotReloadImage() {
        runTest(context = mainDispatcherRule.testDispatcher) {
            val repository = mockExternalCropRepository()
            val viewModel = createViewModel(repository = repository)
            val request = externalCropRequest()

            viewModel.onLaunchRequest(request = request)
            viewModel.onLaunchRequest(request = request)
            advanceUntilIdle()

            coVerify(exactly = 1) {
                repository.loadImage(uri = request.sourceUri)
            }
        }
    }

    @Test
    fun launchRequest_withWideImage_initializesCenteredAspectCropRect() {
        runTest(context = mainDispatcherRule.testDispatcher) {
            val repository = mockExternalCropRepository(
                imageToLoad = cropImage(
                    width = 512,
                    height = 256,
                ),
            )
            val viewModel = createLoadedViewModel(repository = repository)
            advanceUntilIdle()

            assertCropRect(
                expected = NormalizedCropRect(
                    left = 0.25f,
                    top = 0f,
                    right = 0.75f,
                    bottom = 1f,
                ),
                actual = requireNotNull(viewModel.uiState.value.normalizedCropRect),
            )
        }
    }

    @Test
    fun launchRequest_withTallImage_initializesCenteredAspectCropRect() {
        runTest(context = mainDispatcherRule.testDispatcher) {
            val repository = mockExternalCropRepository(
                imageToLoad = cropImage(
                    width = 256,
                    height = 512,
                ),
            )
            val viewModel = createLoadedViewModel(repository = repository)
            advanceUntilIdle()

            assertCropRect(
                expected = NormalizedCropRect(
                    left = 0f,
                    top = 0.25f,
                    right = 1f,
                    bottom = 0.75f,
                ),
                actual = requireNotNull(viewModel.uiState.value.normalizedCropRect),
            )
        }
    }

    @Test
    fun launchRequest_withoutAspectRatio_initializesFullImageCropRect() {
        runTest(context = mainDispatcherRule.testDispatcher) {
            val repository = mockExternalCropRepository(
                imageToLoad = cropImage(
                    width = 512,
                    height = 256,
                ),
            )
            val viewModel = createLoadedViewModel(
                repository = repository,
                request = externalCropRequest(
                    outputX = 0,
                    outputY = 0,
                    aspectX = 0,
                    aspectY = 0,
                ),
            )
            advanceUntilIdle()

            assertCropRect(
                expected = NormalizedCropRect.full(),
                actual = requireNotNull(viewModel.uiState.value.normalizedCropRect),
            )
        }
    }

    @Test
    fun loadFailure_emitsCanceledEffect() {
        runTest(context = mainDispatcherRule.testDispatcher) {
            val repository = mockExternalCropRepository(imageToLoad = null)
            val viewModel = createViewModel(repository = repository)

            viewModel.onLaunchRequest(request = externalCropRequest())
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.image)
            assertEquals(CropEffect.FinishCanceled, awaitEffect(viewModel = viewModel))
        }
    }

    @Test
    fun doneClick_beforeImageLoaded_doesNotSave() {
        runTest(context = mainDispatcherRule.testDispatcher) {
            val repository = mockExternalCropRepository()
            val viewModel = createViewModel(repository = repository)

            viewModel.onDoneClick()

            coVerify(exactly = 0) {
                repository.saveCropResult(
                    request = any(),
                    image = any(),
                    normalizedRect = any(),
                )
            }
            assertNull(nextEffectOrNull(viewModel = viewModel))
        }
    }

    @Test
    fun doneClick_withoutUserChange_savesInitialCropRect() {
        runTest(context = mainDispatcherRule.testDispatcher) {
            val resultIntent = Intent()
            val image = cropImage(
                width = 512,
                height = 256,
            )
            val savedRect = CapturingSlot<NormalizedCropRect>()
            val repository = mockExternalCropRepository(
                imageToLoad = image,
                saveResult = resultIntent,
                savedRect = savedRect,
            )
            val request = externalCropRequest()
            val viewModel = createLoadedViewModel(
                repository = repository,
                request = request,
            )
            advanceUntilIdle()

            viewModel.onDoneClick()
            advanceUntilIdle()

            coVerify(exactly = 1) {
                repository.saveCropResult(
                    request = request,
                    image = image,
                    normalizedRect = any(),
                )
            }
            assertCropRect(
                expected = NormalizedCropRect(
                    left = 0.25f,
                    top = 0f,
                    right = 0.75f,
                    bottom = 1f,
                ),
                actual = savedRect.captured,
            )

            val effect = awaitEffect(viewModel = viewModel)
            assertTrue(effect is CropEffect.FinishWithResult)
            assertSame(resultIntent, (effect as CropEffect.FinishWithResult).resultIntent)
            assertEquals(false, viewModel.uiState.value.isSaving)
        }
    }

    @Test
    fun doneClick_afterUserChange_savesChangedCropRect() {
        runTest(context = mainDispatcherRule.testDispatcher) {
            val savedRect = CapturingSlot<NormalizedCropRect>()
            val repository = mockExternalCropRepository(savedRect = savedRect)
            val viewModel = createLoadedViewModel(repository = repository)
            advanceUntilIdle()

            val normalizedRect = testCropRect()
            viewModel.onCropRectChanged(normalizedRect = normalizedRect)
            viewModel.onDoneClick()
            advanceUntilIdle()

            assertSame(normalizedRect, savedRect.captured)
        }
    }

    @Test
    fun doneClick_whileSaving_savesOnce() {
        runTest(context = mainDispatcherRule.testDispatcher) {
            val saveResult = CompletableDeferred<Intent?>()
            val repository = mockExternalCropRepository(saveResultDeferred = saveResult)
            val viewModel = createLoadedViewModel(repository = repository)
            advanceUntilIdle()

            viewModel.onDoneClick()
            advanceUntilIdle()
            viewModel.onDoneClick()
            advanceUntilIdle()

            assertEquals(true, viewModel.uiState.value.isSaving)
            coVerify(exactly = 1) {
                repository.saveCropResult(
                    request = any(),
                    image = any(),
                    normalizedRect = any(),
                )
            }

            saveResult.complete(Intent())
            advanceUntilIdle()

            assertTrue(awaitEffect(viewModel = viewModel) is CropEffect.FinishWithResult)
            coVerify(exactly = 1) {
                repository.saveCropResult(
                    request = any(),
                    image = any(),
                    normalizedRect = any(),
                )
            }
        }
    }

    @Test
    fun cropRectChanged_updatesUiStateWithCopy() {
        runTest(context = mainDispatcherRule.testDispatcher) {
            val repository = mockExternalCropRepository()
            val viewModel = createLoadedViewModel(repository = repository)
            advanceUntilIdle()

            val androidRect = RectF(0.1f, 0.2f, 0.7f, 0.8f)
            val normalizedRect = NormalizedCropRect(rect = androidRect)
            viewModel.onCropRectChanged(normalizedRect = normalizedRect)
            androidRect.left = 0.4f

            val stateRect = requireNotNull(viewModel.uiState.value.normalizedCropRect)
            assertEquals(0.1f, stateRect.left, 0f)
            assertEquals(0.2f, stateRect.top, 0f)
            assertEquals(0.7f, stateRect.right, 0f)
            assertEquals(0.8f, stateRect.bottom, 0f)
        }
    }

    @Test
    fun doneClick_whenSaveFails_emitsSaveErrorEffect() {
        runTest(context = mainDispatcherRule.testDispatcher) {
            val repository = mockExternalCropRepository(saveResult = null)
            val viewModel = createLoadedViewModel(repository = repository)
            advanceUntilIdle()

            viewModel.onDoneClick()
            advanceUntilIdle()

            assertEquals(CropEffect.ShowSaveErrorAndCancel, awaitEffect(viewModel = viewModel))
            assertEquals(false, viewModel.uiState.value.isSaving)
            coVerify(exactly = 1) {
                repository.saveCropResult(
                    request = any(),
                    image = any(),
                    normalizedRect = any(),
                )
            }
        }
    }

    @Test
    fun cancelClick_whileSaving_cancelsSaveAndFinishes() {
        runTest(context = mainDispatcherRule.testDispatcher) {
            val saveResult = CompletableDeferred<Intent?>()
            var saveWasCancelled = false
            val repository = mockExternalCropRepository(
                saveResultDeferred = saveResult,
                onSaveCancelled = {
                    saveWasCancelled = true
                },
            )
            val viewModel = createLoadedViewModel(repository = repository)
            advanceUntilIdle()

            viewModel.onDoneClick()
            advanceUntilIdle()

            assertEquals(true, viewModel.uiState.value.isSaving)
            viewModel.onCancelClick()
            advanceUntilIdle()

            assertEquals(CropEffect.FinishCanceled, awaitEffect(viewModel = viewModel))
            assertEquals(false, viewModel.uiState.value.isSaving)
            assertEquals(true, saveWasCancelled)
        }
    }

    @Test
    fun doneClick_afterFinish_doesNotSaveAgain() {
        runTest(context = mainDispatcherRule.testDispatcher) {
            val repository = mockExternalCropRepository()
            val viewModel = createLoadedViewModel(repository = repository)
            advanceUntilIdle()

            viewModel.onDoneClick()
            advanceUntilIdle()
            awaitEffect(viewModel = viewModel)

            viewModel.onDoneClick()
            advanceUntilIdle()

            coVerify(exactly = 1) {
                repository.saveCropResult(
                    request = any(),
                    image = any(),
                    normalizedRect = any(),
                )
            }
        }
    }

    private fun createLoadedViewModel(
        repository: ExternalCropRepository,
        request: ExternalCropRequest = externalCropRequest(),
    ): CropViewModel {
        val viewModel = createViewModel(repository = repository)
        viewModel.onLaunchRequest(request = request)
        return viewModel
    }

    private fun createViewModel(repository: ExternalCropRepository): CropViewModel {
        return CropViewModel(repository = repository)
    }

    private suspend fun awaitEffect(viewModel: CropViewModel): CropEffect {
        return withTimeout(timeMillis = 1_000L) {
            viewModel.effects.first()
        }
    }

    private suspend fun nextEffectOrNull(viewModel: CropViewModel): CropEffect? {
        return withTimeoutOrNull(timeMillis = 100L) {
            viewModel.effects.first()
        }
    }
}

private fun mockExternalCropRepository(
    imageToLoad: CropImage? = cropImage(),
    saveResult: Intent? = Intent(),
    saveResultDeferred: CompletableDeferred<Intent?>? = null,
    savedRect: CapturingSlot<NormalizedCropRect>? = null,
    onSaveCancelled: () -> Unit = {},
): ExternalCropRepository {
    val repository = mockk<ExternalCropRepository>()
    coEvery {
        repository.loadImage(uri = any())
    } returns imageToLoad
    configureSaveResult(
        repository = repository,
        saveResult = saveResult,
        saveResultDeferred = saveResultDeferred,
        savedRect = savedRect,
        onSaveCancelled = onSaveCancelled,
    )
    return repository
}

private fun configureSaveResult(
    repository: ExternalCropRepository,
    saveResult: Intent?,
    saveResultDeferred: CompletableDeferred<Intent?>?,
    savedRect: CapturingSlot<NormalizedCropRect>?,
    onSaveCancelled: () -> Unit,
) {
    when (savedRect) {
        null -> {
            coEvery {
                repository.saveCropResult(
                    request = any(),
                    image = any(),
                    normalizedRect = any(),
                )
            } coAnswers {
                awaitSaveResult(
                    saveResult = saveResult,
                    saveResultDeferred = saveResultDeferred,
                    onSaveCancelled = onSaveCancelled,
                )
            }
        }

        else -> {
            coEvery {
                repository.saveCropResult(
                    request = any(),
                    image = any(),
                    normalizedRect = capture(savedRect),
                )
            } coAnswers {
                awaitSaveResult(
                    saveResult = saveResult,
                    saveResultDeferred = saveResultDeferred,
                    onSaveCancelled = onSaveCancelled,
                )
            }
        }
    }
}

private suspend fun awaitSaveResult(
    saveResult: Intent?,
    saveResultDeferred: CompletableDeferred<Intent?>?,
    onSaveCancelled: () -> Unit,
): Intent? {
    return try {
        when (saveResultDeferred) {
            null -> saveResult
            else -> saveResultDeferred.await()
        }
    } catch (exception: CancellationException) {
        onSaveCancelled()
        throw exception
    }
}

private fun externalCropRequest(
    outputX: Int = 128,
    outputY: Int = 128,
    aspectX: Int = 1,
    aspectY: Int = 1,
): ExternalCropRequest {
    return ExternalCropRequest(
        sourceUri = Uri.parse("content://test/source"),
        outputUri = null,
        outputX = outputX,
        outputY = outputY,
        scale = true,
        scaleUpIfNeeded = true,
        aspectX = aspectX,
        aspectY = aspectY,
        returnData = false,
        outputFormat = null,
    )
}

private fun cropImage(width: Int = 256, height: Int = 256): CropImage {
    val sourceBitmap = Bitmap.createBitmap(
        width,
        height,
        Bitmap.Config.ARGB_8888,
    )
    return CropImage(
        sourceBitmap = sourceBitmap,
        previewBitmap = sourceBitmap,
    )
}

private fun testCropRect(): NormalizedCropRect {
    return NormalizedCropRect(
        left = 0.1f,
        top = 0.2f,
        right = 0.7f,
        bottom = 0.8f,
    )
}

private fun assertCropRect(expected: NormalizedCropRect, actual: NormalizedCropRect) {
    assertEquals(expected.left, actual.left, CROP_RECT_TOLERANCE)
    assertEquals(expected.top, actual.top, CROP_RECT_TOLERANCE)
    assertEquals(expected.right, actual.right, CROP_RECT_TOLERANCE)
    assertEquals(expected.bottom, actual.bottom, CROP_RECT_TOLERANCE)
}

private const val CROP_RECT_TOLERANCE = 0.0001f
