package com.dot.gallery.feature_node.presentation.picker

import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
internal class PickerRequestTest {
    @Test
    fun specificAndMultipleMimeRequestsExcludeOtherFormats() {
        val png = requestedMimeTypes(intent = Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/png" })
        assertTrue(matchesPickerMimeType(mimeType = "image/png", requestedTypes = png))
        assertFalse(matchesPickerMimeType(mimeType = "image/jpeg", requestedTypes = png))
        assertFalse(matchesPickerMimeType(mimeType = null, requestedTypes = png))
        val multiple = requestedMimeTypes(intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/png", "video/*"))
        })
        assertTrue(matchesPickerMimeType(mimeType = "image/png", requestedTypes = multiple))
        assertTrue(matchesPickerMimeType(mimeType = "video/mp4", requestedTypes = multiple))
        assertFalse(matchesPickerMimeType(mimeType = "image/jpeg", requestedTypes = multiple))
        val narrowed = requestedMimeTypes(intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/png", "video/*"))
        })
        assertTrue(matchesPickerMimeType(mimeType = "IMAGE/PNG", requestedTypes = narrowed))
        assertFalse(matchesPickerMimeType(mimeType = "image/jpeg", requestedTypes = narrowed))
        assertFalse(matchesPickerMimeType(mimeType = "video/mp4", requestedTypes = narrowed))
    }
}
