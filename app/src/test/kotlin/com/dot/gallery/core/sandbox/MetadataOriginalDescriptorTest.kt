package com.dot.gallery.core.sandbox

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
internal class MetadataOriginalDescriptorTest {
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
