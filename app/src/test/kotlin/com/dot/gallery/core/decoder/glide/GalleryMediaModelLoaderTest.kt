package com.dot.gallery.core.decoder.glide

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.bumptech.glide.Priority
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.data.DataFetcher
import com.dot.gallery.core.decoder.ImageFileFormat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

@RunWith(RobolectricTestRunner::class)
internal class GalleryMediaModelLoaderTest {
    private val contentResolver = mockk<ContentResolver>()
    private val loader = GalleryMediaModelLoader(
        mediaSource = ContentResolverGalleryMediaSource(contentResolver = contentResolver),
    )
    private val temporaryFiles = mutableListOf<File>()

    @After
    fun deleteTemporaryFiles() {
        temporaryFiles.forEach { file -> file.delete() }
    }

    @Test
    fun loadData_classifiesChunkedAvifWithOneStreamOpen() {
        val uri = Uri.parse("content://media/avif")
        every { contentResolver.openInputStream(uri) } returns OneByteMediaInputStream(avifHeader())

        val callback = load(uri = uri, mimeType = "image/avif")

        assertTrue(callback.data is SandboxedImageData)
        assertEquals("image/avif", (callback.data as SandboxedImageData).mimeType)
        verify(exactly = 1) { contentResolver.openInputStream(uri) }
        verify(exactly = 0) { contentResolver.openFileDescriptor(uri, any()) }
        callback.data?.close()
    }

    @Test
    fun loadData_routesStandardImageWithOneStreamOpen() {
        val uri = Uri.parse("content://media/jpeg")
        every { contentResolver.openInputStream(uri) } returns ByteArrayInputStream(jpegHeader())

        val callback = load(uri = uri, mimeType = "image/jpeg")

        assertTrue(callback.data is PlatformImageData)
        assertEquals(ImageFileFormat.JPEG, (callback.data as PlatformImageData).format)
        verify(exactly = 1) { contentResolver.openInputStream(uri) }
        callback.data?.close()
    }

    @Test
    fun loadData_routesDeclaredVideoWithOneDescriptorOpen() {
        val uri = Uri.parse("content://media/video")
        every { contentResolver.openFileDescriptor(uri, "r") } returns descriptor(mp4Header())

        val callback = load(uri = uri, mimeType = "video/mp4")

        assertTrue(callback.data is VerifiedVideoData)
        verify(exactly = 1) { contentResolver.openFileDescriptor(uri, "r") }
        verify(exactly = 0) { contentResolver.openInputStream(uri) }
        callback.data?.close()
    }

    @Test
    fun loadData_resolvesMissingVideoMimeType() {
        val uri = Uri.parse("content://media/video-without-declared-mime")
        every { contentResolver.getType(uri) } returns "video/mp4"
        every { contentResolver.openFileDescriptor(uri, "r") } returns descriptor(mp4Header())

        val callback = load(uri = uri, mimeType = null)

        assertTrue(callback.data is VerifiedVideoData)
        verify(exactly = 1) { contentResolver.getType(uri) }
        verify(exactly = 1) { contentResolver.openFileDescriptor(uri, "r") }
        verify(exactly = 0) { contentResolver.openInputStream(uri) }
        callback.data?.close()
    }

    @Test
    fun loadData_routesAvifDeclaredAsVideoByBytes() {
        val uri = Uri.parse("content://media/mislabeled-avif")
        every { contentResolver.openFileDescriptor(uri, "r") } returns descriptor(avifHeader())

        val callback = load(uri = uri, mimeType = "video/mp4")

        assertTrue(callback.data is SandboxedImageData)
        assertEquals("image/avif", (callback.data as SandboxedImageData).mimeType)
        verify(exactly = 1) { contentResolver.openFileDescriptor(uri, "r") }
        verify(exactly = 0) { contentResolver.openInputStream(uri) }
        callback.data?.close()
    }

    @Test
    fun loadData_routesJpegDeclaredAsVideoByBytes() {
        val uri = Uri.parse("content://media/mislabeled-jpeg")
        every { contentResolver.openFileDescriptor(uri, "r") } returns descriptor(jpegHeader())

        val callback = load(uri = uri, mimeType = "video/mp4")

        assertTrue(callback.data is PlatformImageData)
        assertEquals(ImageFileFormat.JPEG, (callback.data as PlatformImageData).format)
        verify(exactly = 1) { contentResolver.openFileDescriptor(uri, "r") }
        callback.data?.close()
    }

    @Test
    fun loadData_rejectsUnknownNonVideoContent() {
        val uri = Uri.parse("content://media/unknown")
        every { contentResolver.openInputStream(uri) } returns
                ByteArrayInputStream("not media".encodeToByteArray())

        val callback = load(uri = uri, mimeType = "application/octet-stream")

        assertNotNull(callback.failure)
        verify(exactly = 1) { contentResolver.openInputStream(uri) }
    }

    private fun load(uri: Uri, mimeType: String?): RecordingCallback {
        val loadData = loader.buildLoadData(
            model = GalleryMediaModel(
                uri = uri,
                declaredMimeType = mimeType,
            ),
            width = 100,
            height = 100,
            options = Options(),
        )
        val callback = RecordingCallback()
        loadData.fetcher.loadData(Priority.NORMAL, callback)
        return callback
    }

    private fun descriptor(bytes: ByteArray): ParcelFileDescriptor {
        val file = File.createTempFile("gallery-media-loader", ".bin")
        temporaryFiles += file
        file.writeBytes(bytes)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun jpegHeader(): ByteArray {
        return byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x00)
    }

    private fun avifHeader(): ByteArray {
        return byteArrayOf(
            0x00,
            0x00,
            0x00,
            0x14,
            0x66,
            0x74,
            0x79,
            0x70,
            0x61,
            0x76,
            0x69,
            0x66,
            0x00,
            0x00,
            0x00,
            0x00,
            0x6D,
            0x69,
            0x66,
            0x31,
        )
    }

    private fun mp4Header(): ByteArray {
        return byteArrayOf(
            0x00,
            0x00,
            0x00,
            0x18,
            0x66,
            0x74,
            0x79,
            0x70,
            0x69,
            0x73,
            0x6F,
            0x6D,
            0x00,
            0x00,
            0x00,
            0x00,
            0x69,
            0x73,
            0x6F,
            0x6D,
            0x6D,
            0x70,
            0x34,
            0x32,
        )
    }
}

private class RecordingCallback : DataFetcher.DataCallback<GalleryMediaData> {
    var data: GalleryMediaData? = null
        private set
    var failure: Exception? = null
        private set

    override fun onDataReady(data: GalleryMediaData?) {
        this.data = data
    }

    override fun onLoadFailed(exception: Exception) {
        failure = exception
    }
}

private class OneByteMediaInputStream(
    private val delegate: InputStream,
) : InputStream() {
    constructor(bytes: ByteArray) : this(delegate = ByteArrayInputStream(bytes))

    override fun read(): Int {
        return delegate.read()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        return delegate.read(buffer, offset, minOf(length, 1))
    }

    override fun close() {
        delegate.close()
    }
}
