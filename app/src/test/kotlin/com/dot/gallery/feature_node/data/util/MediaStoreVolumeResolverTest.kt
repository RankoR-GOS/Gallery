package com.dot.gallery.feature_node.data.util

import android.os.Environment
import android.provider.MediaStore
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MediaStoreVolumeResolverTest {

    @Test
    fun resolveMediaStoreVolume_withPrimaryAbsolutePath_returnsPrimaryVolume() {
        val primaryPath = Environment.getExternalStorageDirectory().absolutePath

        val result = resolveMediaStoreVolume(path = "$primaryPath/DCIM/Camera")

        assertEquals(MediaStore.VOLUME_EXTERNAL_PRIMARY, result.first)
        assertEquals("DCIM/Camera", result.second)
    }

    @Test
    fun resolveMediaStoreVolume_withSecondaryAbsolutePath_returnsSecondaryVolume() {
        val result = resolveMediaStoreVolume(path = "/storage/71F8-2C0A/DCIM/Camera")

        assertEquals("71f8-2c0a", result.first)
        assertEquals("DCIM/Camera", result.second)
    }

    @Test
    fun resolveMediaStoreVolume_withRelativePath_returnsPrimaryVolume() {
        val result = resolveMediaStoreVolume(path = "Pictures/Edited")

        assertEquals(MediaStore.VOLUME_EXTERNAL_PRIMARY, result.first)
        assertEquals("Pictures/Edited", result.second)
    }
}
