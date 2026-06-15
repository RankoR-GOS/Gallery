package com.dot.gallery.feature_node.presentation.util

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
internal class MetadataRouteTest {
    @Test
    fun externalUriReservedCharactersRoundTripWithoutChangingVideoFlag() {
        val mediaUri = "content://example.provider/a b/photo.jpg?token=x&isVideo=false#details"
        val route = Screen.MetadataViewScreen.uriAndType(mediaUri = mediaUri, isVideo = true)
        val parsed = Uri.parse(route)
        assertEquals(Screen.MetadataViewScreen.route, parsed.path)
        assertEquals(mediaUri, parsed.getQueryParameter("mediaUri"))
        assertEquals("true", parsed.getQueryParameter("isVideo"))
    }
}
