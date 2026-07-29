package com.dot.gallery.feature_node.data.model.editor.crop

import android.net.Uri
import com.dot.gallery.feature_node.data.model.editor.SaveFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [36])
@RunWith(RobolectricTestRunner::class)
class ExternalCropRequestTest {

    @Test
    fun aspectRatio_prefersExplicitAspectOverOutputSize() {
        val request = externalCropRequest(
            aspectX = 1,
            aspectY = 1,
            outputX = 16,
            outputY = 9,
        )

        assertEquals(1f, request.aspectRatio)
    }

    @Test
    fun aspectRatio_usesOutputSizeWhenAspectIsMissing() {
        val request = externalCropRequest(
            aspectX = 0,
            aspectY = 0,
            outputX = 16,
            outputY = 9,
        )

        assertEquals(16f / 9f, request.aspectRatio)
    }

    @Test
    fun aspectRatio_returnsNullWithoutAspectOrOutputSize() {
        val request = externalCropRequest(
            aspectX = 0,
            aspectY = 0,
            outputX = 0,
            outputY = 0,
        )

        assertNull(request.aspectRatio)
    }

    @Test
    fun saveFormat_mapsPngAndGifToPng() {
        assertEquals(
            SaveFormat.Png,
            externalCropRequest(outputFormat = "PNG").saveFormat,
        )
        assertEquals(
            SaveFormat.Png,
            externalCropRequest(outputFormat = "image/gif").saveFormat,
        )
        assertEquals(
            SaveFormat.Png,
            externalCropRequest(outputFormat = "avatar.png").saveFormat,
        )
        assertEquals(
            SaveFormat.Png,
            externalCropRequest(outputFormat = "image/x-png").saveFormat,
        )
    }

    @Test
    fun saveFormat_mapsWebpToWebpLossy() {
        assertEquals(
            SaveFormat.WebpLossy,
            externalCropRequest(outputFormat = "WEBP").saveFormat,
        )
        assertEquals(
            SaveFormat.WebpLossy,
            externalCropRequest(outputFormat = "image/webp").saveFormat,
        )
        assertEquals(
            SaveFormat.WebpLossy,
            externalCropRequest(outputFormat = "avatar.webp").saveFormat,
        )
        assertEquals(
            SaveFormat.WebpLossy,
            externalCropRequest(outputFormat = "image/webp; charset=utf-8").saveFormat,
        )
    }

    @Test
    fun saveFormat_mapsWebpLosslessExplicitly() {
        assertEquals(
            SaveFormat.WebpLossless,
            externalCropRequest(outputFormat = "WEBP_LOSSLESS").saveFormat,
        )
        assertEquals(
            SaveFormat.WebpLossless,
            externalCropRequest(outputFormat = "Bitmap.CompressFormat.WEBP_LOSSLESS").saveFormat,
        )
    }

    @Test
    fun saveFormat_mapsJpegAndJpgToJpeg() {
        assertEquals(
            SaveFormat.Jpeg,
            externalCropRequest(outputFormat = "JPEG").saveFormat,
        )
        assertEquals(
            SaveFormat.Jpeg,
            externalCropRequest(outputFormat = "image/jpg").saveFormat,
        )
        assertEquals(
            SaveFormat.Jpeg,
            externalCropRequest(outputFormat = "avatar.jpg").saveFormat,
        )
    }

    @Test
    fun saveFormat_defaultsToJpeg() {
        assertEquals(
            SaveFormat.Jpeg,
            externalCropRequest(outputFormat = null).saveFormat,
        )
        assertEquals(
            SaveFormat.Jpeg,
            externalCropRequest(outputFormat = "heic").saveFormat,
        )
    }

    private fun externalCropRequest(
        outputX: Int = 0,
        outputY: Int = 0,
        aspectX: Int = 0,
        aspectY: Int = 0,
        outputFormat: String? = null,
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
            outputFormat = outputFormat,
        )
    }
}
