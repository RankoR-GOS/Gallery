package com.dot.gallery.core.decoder.glide

import android.content.ContentResolver
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.dot.gallery.GalleryApp
import com.github.panpf.zoomimage.subsampling.SubsamplingImageGenerateResult
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
internal class GalleryMediaRoutingDeviceTest {
    @Test
    fun missingDeclaredMimeType_loadsMediaStoreVideoThumbnail() {
        val application = ApplicationProvider.getApplicationContext<GalleryApp>()
        val contentResolver = application.contentResolver
        val videoUri = insertVideo(
            contentResolver = contentResolver,
            bytes = readFixture(name = "GalleryDecoderTest.mp4"),
        )

        try {
            val bitmap = runBlocking {
                withContext(context = Dispatchers.IO) {
                    Glide.with(application)
                        .asBitmap()
                        .load(galleryMediaModel(uri = videoUri, mimeType = null))
                        .diskCacheStrategy(DiskCacheStrategy.NONE)
                        .skipMemoryCache(true)
                        .submit()
                        .get()
                }
            }

            assertEquals(16, bitmap.width)
            assertEquals(16, bitmap.height)
            bitmap.recycle()
        } finally {
            contentResolver.delete(videoUri, null, null)
        }
    }

    @Test
    fun subsampling_acceptsVerifiedPlatformImagesAndRejectsSandboxFormats() {
        val application = ApplicationProvider.getApplicationContext<GalleryApp>()
        val pngFile = writeFixtureFile(
            application = application,
            name = "GalleryDecoderTest.png",
        )
        val jpegFile = application.cacheDir.resolve("GalleryDecoderTest.jpg")
        val avifFile = writeFixtureFile(
            application = application,
            name = "GalleryDecoderTest.avif",
        )
        val jxlFile = writeFixtureFile(
            application = application,
            name = "GalleryDecoderTest.jxl",
        )
        writeJpeg(file = jpegFile)

        try {
            runBlocking {
                listOf(pngFile, jpegFile).forEach { imageFile ->
                    val result = generateSubsamplingImage(
                        application = application,
                        file = imageFile,
                    )
                    assertTrue(result is SubsamplingImageGenerateResult.Success)
                    val success = result as SubsamplingImageGenerateResult.Success
                    val imageSource = success.subsamplingImage.imageSource.create()
                    imageSource.openSource().buffer().use { source ->
                        assertTrue(!source.exhausted())
                    }
                }

                listOf(avifFile, jxlFile).forEach { imageFile ->
                    val result = generateSubsamplingImage(
                        application = application,
                        file = imageFile,
                    )
                    assertTrue(result is SubsamplingImageGenerateResult.Error)
                }
            }
        } finally {
            pngFile.delete()
            jpegFile.delete()
            avifFile.delete()
            jxlFile.delete()
        }
    }

    private suspend fun generateSubsamplingImage(
        application: GalleryApp,
        file: File,
    ): SubsamplingImageGenerateResult {
        val generators = galleryMediaSubsamplingImageGenerators()
        assertEquals(1, generators.size)
        assertTrue(generators.single() is GalleryMediaSubsamplingImageGenerator)
        return requireNotNull(
            generators.single().generateImage(
                context = application,
                glide = Glide.get(application),
                model = GalleryMediaModel(
                    uri = Uri.fromFile(file),
                    declaredMimeType = null,
                ),
                drawable = ColorDrawable(),
            ),
        )
    }

    private fun insertVideo(contentResolver: ContentResolver, bytes: ByteArray): Uri {
        val uri = requireNotNull(
            contentResolver.insert(
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                ContentValues().apply {
                    put(
                        MediaStore.MediaColumns.DISPLAY_NAME,
                        "gallery-routing-${System.nanoTime()}.mp4",
                    )
                    put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, TEST_RELATIVE_PATH)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                },
            ),
        ) { "Failed to insert test video" }

        try {
            contentResolver.openOutputStream(uri)?.use { outputStream ->
                outputStream.write(bytes)
            } ?: throw IOException("Failed to write test video")
            val updatedRows = contentResolver.update(
                uri,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                },
                null,
                null,
            )
            check(updatedRows > 0) { "Failed to publish test video" }
            return uri
        } catch (failure: Exception) {
            contentResolver.delete(uri, null, null)
            throw failure
        }
    }

    private fun readFixture(name: String): ByteArray {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        return assets.open(name).use { inputStream ->
            inputStream.readBytes()
        }
    }

    private fun writeFixtureFile(application: GalleryApp, name: String): File {
        val fixtureFile = application.cacheDir.resolve(name)
        fixtureFile.outputStream().use { outputStream ->
            outputStream.write(readFixture(name = name))
        }
        return fixtureFile
    }

    private fun writeJpeg(file: File) {
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        try {
            file.outputStream().use { outputStream ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)) {
                    "Failed to encode test JPEG"
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    companion object {
        private val TEST_RELATIVE_PATH =
            "${Environment.DIRECTORY_MOVIES}/GalleryMediaRoutingTest"
    }
}
