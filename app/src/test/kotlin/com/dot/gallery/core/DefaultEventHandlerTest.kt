package com.dot.gallery.core

import com.dot.gallery.feature_node.domain.model.UIEvent
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

internal class DefaultEventHandlerTest {
    @Test
    fun queuedNavigationIsDeliveredInOrderAndActivityHandlersAreIndependent() {
        runBlocking {
            val gallery = DefaultEventHandler()
            val picker = DefaultEventHandler()
            var galleryBackCount = 0
            var pickerBackCount = 0
            gallery.navigateUpAction = { galleryBackCount++ }
            picker.navigateUpAction = { pickerBackCount++ }
            gallery.navigateUp()
            gallery.navigate(route = "metadata/42")
            picker.navigateUp()
            val galleryEvents = withTimeout(timeMillis = 2_000) { gallery.updaterFlow.take(count = 2).toList() }
            val pickerEvents = withTimeout(timeMillis = 2_000) { picker.updaterFlow.take(count = 1).toList() }
            assertEquals(listOf(UIEvent.NavigationUpEvent, UIEvent.NavigationRouteEvent(route = "metadata/42")), galleryEvents)
            assertEquals(listOf(UIEvent.NavigationUpEvent), pickerEvents)
            gallery.navigateUpAction()
            assertEquals(1, galleryBackCount)
            assertEquals(0, pickerBackCount)
        }
    }
}
