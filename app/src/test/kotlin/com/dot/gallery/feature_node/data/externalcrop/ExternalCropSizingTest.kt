package com.dot.gallery.feature_node.data.externalcrop

import org.junit.Assert.assertEquals
import org.junit.Test

class ExternalCropSizingTest {

    @Test
    fun resolveCropOutputSize_returnsExactOutputWhenScaleUpIsAllowed() {
        val size = resolveCropOutputSize(
            cropWidth = 100,
            cropHeight = 100,
            outputX = 300,
            outputY = 300,
            scale = true,
            scaleUpIfNeeded = true,
        )

        assertEquals(CropSize(width = 300, height = 300), size)
    }

    @Test
    fun resolveCropOutputSize_clampsOversizedScaleUpRequest() {
        val size = resolveCropOutputSize(
            cropWidth = 100,
            cropHeight = 100,
            outputX = 100_000,
            outputY = 100_000,
            scale = true,
            scaleUpIfNeeded = true,
        )

        assertEquals(CropSize(width = 4096, height = 4096), size)
    }

    @Test
    fun resolveCropOutputSize_preservesAspectRatioWhenClampingWideRequest() {
        val size = resolveCropOutputSize(
            cropWidth = 100,
            cropHeight = 100,
            outputX = 100_000,
            outputY = 1_000,
            scale = true,
            scaleUpIfNeeded = true,
        )

        assertEquals(CropSize(width = 8192, height = 82), size)
    }

    @Test
    fun resolveCropOutputSize_doesNotUpscaleWhenScaleUpIsDisabled() {
        val size = resolveCropOutputSize(
            cropWidth = 100,
            cropHeight = 100,
            outputX = 300,
            outputY = 300,
            scale = true,
            scaleUpIfNeeded = false,
        )

        assertEquals(CropSize(width = 100, height = 100), size)
    }

    @Test
    fun resolveCropOutputSize_downscalesToFitRequestedBounds() {
        val size = resolveCropOutputSize(
            cropWidth = 800,
            cropHeight = 400,
            outputX = 200,
            outputY = 200,
            scale = true,
            scaleUpIfNeeded = false,
        )

        assertEquals(CropSize(width = 200, height = 100), size)
    }

    @Test
    fun resolveCropOutputSize_returnsExactOutputCanvasWhenScaleIsDisabled() {
        val size = resolveCropOutputSize(
            cropWidth = 100,
            cropHeight = 100,
            outputX = 300,
            outputY = 300,
            scale = false,
            scaleUpIfNeeded = false,
        )

        assertEquals(CropSize(width = 300, height = 300), size)
    }

    @Test
    fun resolveCropOutputSize_clampsOversizedOutputCanvasWhenScaleIsDisabled() {
        val size = resolveCropOutputSize(
            cropWidth = 100,
            cropHeight = 100,
            outputX = 100_000,
            outputY = 100_000,
            scale = false,
            scaleUpIfNeeded = false,
        )

        assertEquals(CropSize(width = 4096, height = 4096), size)
    }

    @Test
    fun resolveCropSourceDecodeSize_clampsOversizedSquareSource() {
        val size = resolveCropSourceDecodeSize(
            sourceWidth = 100_000,
            sourceHeight = 100_000,
        )

        assertEquals(CropSize(width = 4096, height = 4096), size)
    }

    @Test
    fun resolveCropSourceDecodeSize_preservesAspectRatioWhenClampingWideSource() {
        val size = resolveCropSourceDecodeSize(
            sourceWidth = 100_000,
            sourceHeight = 1_000,
        )

        assertEquals(CropSize(width = 8192, height = 82), size)
    }

    @Test
    fun resolveIntentBitmapSize_halvesUntilBelowBinderBudget() {
        val size = resolveIntentBitmapSize(
            width = 1000,
            height = 1000,
        )

        assertEquals(CropSize(width = 250, height = 250), size)
    }
}
