package com.dot.gallery.feature_node.presentation.albums.components

import android.app.Application
import android.net.Uri
import android.widget.FrameLayout
import android.widget.ImageView
import com.dot.gallery.R
import com.dot.gallery.feature_node.data.model.Album
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@Config(application = Application::class)
@RunWith(RobolectricTestRunner::class)
internal class PinnedAlbumPrivacyTest {
    @Test
    fun lockingSameAlbumRebindsAndRecyclingClearsTheThumbnail() {
        val context = RuntimeEnvironment.getApplication()
        context.setTheme(R.style.Theme_Gallery)
        val album = Album(
            id = 42L,
            label = "Private",
            uri = Uri.parse("content://media/external_primary/images/media/42"),
            pathToThumbnail = "",
            relativePath = "Pictures/",
            timestamp = 1L,
        )
        val locked = album.copy(isLocked = true)
        assertTrue(PinnedAlbumsDiffCallback.areItemsTheSame(oldItem = album, newItem = locked))
        assertFalse(PinnedAlbumsDiffCallback.areContentsTheSame(oldItem = album, newItem = locked))
        val adapter = PinnedAlbumsAdapter(
            onAlbumClick = {},
            onAlbumLongClick = {},
            maxWidth = 200f,
            primaryTextColor = 0,
            secondaryTextColor = 0,
            containerColor = 0,
            lockedBackgroundColor = 0,
            lockedContentColor = 0,
        )
        val holder = adapter.onCreateViewHolder(viewGroup = FrameLayout(context), viewType = 0)
        holder.bind(album = locked)
        val image = holder.itemView.findViewById<ImageView>(R.id.carousel_image_view)
        assertEquals(context.getString(R.string.locked), image.contentDescription)
        assertEquals(ImageView.ScaleType.CENTER, image.scaleType)
        assertNotNull(image.drawable)
        adapter.onViewRecycled(holder = holder)
        assertNull(image.drawable)
        assertNull(image.contentDescription)
    }
}
