package com.dot.gallery.feature_node.domain.model.editor.crop

import android.net.Uri
import com.dot.gallery.feature_node.domain.model.editor.SaveFormat
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
            SaveFormat.PNG,
            externalCropRequest(outputFormat = "PNG").saveFormat,
        )
        assertEquals(
            SaveFormat.PNG,
            externalCropRequest(outputFormat = "image/gif").saveFormat,
        )
        assertEquals(
            SaveFormat.PNG,
            externalCropRequest(outputFormat = "avatar.png").saveFormat,
        )
        assertEquals(
            SaveFormat.PNG,
            externalCropRequest(outputFormat = "image/x-png").saveFormat,
        )
    }

    @Test
    fun saveFormat_mapsWebpToWebpLossy() {
        assertEquals(
            SaveFormat.WEBP_LOSSY,
            externalCropRequest(outputFormat = "WEBP").saveFormat,
        )
        assertEquals(
            SaveFormat.WEBP_LOSSY,
            externalCropRequest(outputFormat = "image/webp").saveFormat,
        )
        assertEquals(
            SaveFormat.WEBP_LOSSY,
            externalCropRequest(outputFormat = "avatar.webp").saveFormat,
        )
        assertEquals(
            SaveFormat.WEBP_LOSSY,
            externalCropRequest(outputFormat = "image/webp; charset=utf-8").saveFormat,
        )
    }

    @Test
    fun saveFormat_mapsWebpLosslessExplicitly() {
        assertEquals(
            SaveFormat.WEBP_LOSSLESS,
            externalCropRequest(outputFormat = "WEBP_LOSSLESS").saveFormat,
        )
        assertEquals(
            SaveFormat.WEBP_LOSSLESS,
            externalCropRequest(outputFormat = "Bitmap.CompressFormat.WEBP_LOSSLESS").saveFormat,
        )
    }

    @Test
    fun saveFormat_mapsJpegAndJpgToJpeg() {
        assertEquals(
            SaveFormat.JPEG,
            externalCropRequest(outputFormat = "JPEG").saveFormat,
        )
        assertEquals(
            SaveFormat.JPEG,
            externalCropRequest(outputFormat = "image/jpg").saveFormat,
        )
        assertEquals(
            SaveFormat.JPEG,
            externalCropRequest(outputFormat = "avatar.jpg").saveFormat,
        )
    }

    @Test
    fun saveFormat_defaultsToJpeg() {
        assertEquals(
            SaveFormat.JPEG,
            externalCropRequest(outputFormat = null).saveFormat,
        )
        assertEquals(
            SaveFormat.JPEG,
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
