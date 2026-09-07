package com.dot.gallery.core.sandbox

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import com.dot.gallery.feature_node.data.model.Media
import com.dot.gallery.feature_node.data.model.retrieveExtraMediaMetadata
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
internal class MetadataOriginalDescriptorTest {
    @Test
    fun originalCoordinatesSurviveLocalMetadataMapping() {
        runBlocking {
            val media = Media.UriMedia(
                id = 42L,
                label = "gps.jpg",
                uri = Uri.parse("content://media/external_primary/images/media/42"),
                path = "",
                relativePath = "Pictures/",
                albumID = 1L,
                albumLabel = "Pictures",
                timestamp = 1L,
                fullDate = "",
                mimeType = "image/jpeg",
                favorite = 0,
                trashed = 0,
                size = 1L,
            )
            val parser = mockk<IsolatedMetadataParser>()
            val bundle = Bundle().apply {
                putDouble(IsolatedMetadataService.KEY_GPS_LAT, 12.5)
                putDouble(IsolatedMetadataService.KEY_GPS_LON, 34.5)
                putInt(IsolatedMetadataService.KEY_IMAGE_WIDTH, 8)
                putInt(IsolatedMetadataService.KEY_IMAGE_HEIGHT, 8)
            }
            coEvery { parser.parseImageMetadata(media.uri, media.label) } returns bundle
            val metadata = requireNotNull(RuntimeEnvironment.getApplication().retrieveExtraMediaMetadata(
                isolatedParser = parser,
                media = media,
            ))
            assertEquals(12.5, requireNotNull(metadata.gpsLatitude), 0.0)
            assertEquals(34.5, requireNotNull(metadata.gpsLongitude), 0.0)
            assertNull(metadata.gpsLocationName)
            assertNull(metadata.gpsLocationNameCountry)
            assertNull(metadata.gpsLocationNameCity)
        }
    }

    @Test
    fun originalRequiresPermissionAndFallsBackWithoutSwallowingCancellation() {
        val context = mockk<Context>()
        val resolver = mockk<ContentResolver>()
        val descriptor = mockk<ParcelFileDescriptor>()
        val uri = Uri.parse("content://media/external_primary/images/media/42")
        val original = MediaStore.setRequireOriginal(uri)
        every { context.contentResolver } returns resolver
        every { context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) } returns PackageManager.PERMISSION_DENIED
        every { resolver.openFileDescriptor(uri, "r") } returns descriptor
        assertSame(descriptor, context.openMetadataFileDescriptor(uri = uri))
        verify(exactly = 0) { resolver.openFileDescriptor(original, "r") }

        every { context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) } returns PackageManager.PERMISSION_GRANTED
        every { resolver.openFileDescriptor(original, "r") } throws SecurityException()
        assertSame(descriptor, context.openMetadataFileDescriptor(uri = uri))
        every { resolver.openFileDescriptor(original, "r") } returns descriptor
        assertSame(descriptor, context.openMetadataFileDescriptor(uri = uri))
        every { resolver.openFileDescriptor(original, "r") } throws CancellationException()
        assertThrows(CancellationException::class.java) { context.openMetadataFileDescriptor(uri = uri) }
        verify(exactly = 2) { resolver.openFileDescriptor(uri, "r") }

        val external = Uri.parse("content://external.provider/photo.jpg")
        every { resolver.openFileDescriptor(external, "r") } returns descriptor
        assertSame(descriptor, context.openMetadataFileDescriptor(uri = external))
        verify(exactly = 0) { resolver.openFileDescriptor(MediaStore.setRequireOriginal(external), "r") }
    }
}
