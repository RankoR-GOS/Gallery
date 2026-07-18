package com.dot.gallery.core.sandbox

import android.graphics.drawable.AnimatedImageDrawable
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bumptech.glide.Glide
import com.bumptech.glide.Priority
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.data.DataFetcher
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.dot.gallery.GalleryApp
import com.dot.gallery.core.decoder.SandboxedSketchHeifDecoder
import com.dot.gallery.core.decoder.SandboxedSketchJxlDecoder
import com.dot.gallery.core.decoder.glide.GalleryMediaData
import com.dot.gallery.core.decoder.glide.GalleryMediaModel
import com.dot.gallery.core.decoder.glide.GalleryMediaModelLoader
import com.dot.gallery.core.decoder.glide.GalleryMediaSource
import com.dot.gallery.core.decoder.glide.galleryMediaModel
import com.github.panpf.sketch.BitmapImage
import com.github.panpf.sketch.decode.Decoder
import com.github.panpf.sketch.request.ImageRequest
import com.github.panpf.sketch.request.RequestContext
import com.github.panpf.sketch.sketch
import com.github.panpf.sketch.source.FileDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okio.Path.Companion.toOkioPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch

@RunWith(AndroidJUnit4::class)
internal class IsolatedImageDecoderDeviceTest {
    private val decoder = ApplicationProvider
        .getApplicationContext<GalleryApp>()
        .isolatedImageDecoder

    @After
    fun unbindDecoder() {
        decoder.unbind()
    }

    @Test
    fun validAvifAndJxl_decodeRepeatedlyWithExpectedDimensionsAndPixels() {
        runBlocking {
            listOf(
                DecoderFixture(
                    name = "GalleryDecoderTest.avif",
                    mimeType = "image/avif",
                    width = 4000,
                    height = 3000,
                    decodedWidth = 64,
                    decodedHeight = 48,
                ),
                DecoderFixture(
                    name = "GalleryDecoderTest.jxl",
                    mimeType = "image/jxl",
                    width = 4000,
                    height = 3000,
                    decodedWidth = 64,
                    decodedHeight = 48,
                ),
            ).forEach { fixture ->
                val encodedBytes = readFixture(name = fixture.name)
                repeat(times = 5) {
                    val result = decoder.decode(
                        inputStream = ByteArrayInputStream(encodedBytes),
                        mimeType = fixture.mimeType,
                        targetWidth = 64,
                        targetHeight = 64,
                    )

                    assertNotNull(result)
                    val decodedResult = requireNotNull(result)
                    assertEquals(fixture.width, decodedResult.originalSize.width)
                    assertEquals(fixture.height, decodedResult.originalSize.height)
                    assertEquals(fixture.decodedWidth, decodedResult.bitmap.width)
                    assertEquals(fixture.decodedHeight, decodedResult.bitmap.height)
                    assertPixelDataIsNotEmpty(result = decodedResult)
                    decodedResult.bitmap.recycle()
                }
            }
        }
    }

    @Test
    fun concurrentRequests_completeWithoutReliablePipeFailures() {
        runBlocking {
            val fixtures = listOf(
                DecoderFixture(
                    name = "GalleryDecoderTest.avif",
                    mimeType = "image/avif",
                    width = 4000,
                    height = 3000,
                    decodedWidth = 48,
                    decodedHeight = 36,
                ),
                DecoderFixture(
                    name = "GalleryDecoderTest.jxl",
                    mimeType = "image/jxl",
                    width = 4000,
                    height = 3000,
                    decodedWidth = 48,
                    decodedHeight = 36,
                ),
            )
            fixtures.flatMap { fixture ->
                val encodedBytes = readFixture(name = fixture.name)
                List(size = 4) {
                    async {
                        decoder.decode(
                            inputStream = ByteArrayInputStream(encodedBytes),
                            mimeType = fixture.mimeType,
                            targetWidth = 48,
                            targetHeight = 48,
                        )
                    }
                }
            }.awaitAll().forEach { result ->
                assertNotNull(result)
                requireNotNull(result).bitmap.recycle()
            }
        }
    }

    @Test
    fun emptyMalformedAndUnsupportedInputs_failClosed() {
        runBlocking {
            assertNull(
                decoder.decode(
                    inputStream = ByteArrayInputStream(ByteArray(size = 0)),
                    mimeType = "image/avif",
                ),
            )
            assertNull(
                decoder.decode(
                    inputStream = ByteArrayInputStream("not an image".encodeToByteArray()),
                    mimeType = "image/jxl",
                ),
            )
            assertNull(
                decoder.decode(
                    inputStream = ByteArrayInputStream(readFixture(name = "GalleryDecoderTest.avif")),
                    mimeType = "image/jpeg",
                ),
            )
        }
    }

    @Test
    fun earlyServiceRejection_returnsPromptlyAndPreservesFollowingDecode() {
        runBlocking {
            val rejectedResult = withTimeout(timeMillis = EARLY_REJECTION_TIMEOUT_MILLIS) {
                decoder.decode(
                    inputStream = ByteArrayInputStream(
                        ByteArray(size = EARLY_REJECTION_INPUT_BYTES),
                    ),
                    mimeType = "image/jpeg",
                )
            }
            assertNull(rejectedResult)

            val recoveredResult = decoder.decode(
                inputStream = ByteArrayInputStream(
                    readFixture(name = "GalleryDecoderTest.avif"),
                ),
                mimeType = "image/avif",
                targetWidth = 32,
                targetHeight = 32,
            )
            assertNotNull(recoveredResult)
            requireNotNull(recoveredResult).bitmap.recycle()
        }
    }

    @Test
    fun writerFailureAndCancellation_failWithoutHangingTheConnection() {
        runBlocking {
            assertNull(
                decoder.decode(
                    inputStream = ThrowingInputStream(),
                    mimeType = "image/avif",
                ),
            )

            val cancelledResult = withTimeoutOrNull(timeMillis = 100L) {
                decoder.decode(
                    inputStream = CloseAwareBlockingInputStream(),
                    mimeType = "image/avif",
                )
            }
            assertNull(cancelledResult)

            val recoveredResult = decoder.decode(
                inputStream = ByteArrayInputStream(
                    readFixture(name = "GalleryDecoderTest.avif"),
                ),
                mimeType = "image/avif",
                targetWidth = 32,
                targetHeight = 32,
            )
            assertNotNull(recoveredResult)
            requireNotNull(recoveredResult).bitmap.recycle()
        }
    }

    @Test
    fun glideRoute_decodesAvifAndJxlOnlyThroughVerifiedModels() {
        runBlocking {
            val application = ApplicationProvider.getApplicationContext<GalleryApp>()
            listOf(
                DecoderFixture(
                    name = "GalleryDecoderTest.avif",
                    mimeType = "image/avif",
                    width = 4000,
                    height = 3000,
                    decodedWidth = 64,
                    decodedHeight = 48,
                ),
                DecoderFixture(
                    name = "GalleryDecoderTest.jxl",
                    mimeType = "image/jxl",
                    width = 4000,
                    height = 3000,
                    decodedWidth = 64,
                    decodedHeight = 48,
                ),
            ).forEach { fixture ->
                val fixtureFile = application.cacheDir.resolve(fixture.name)
                try {
                    fixtureFile.outputStream().use { outputStream ->
                        outputStream.write(readFixture(name = fixture.name))
                    }
                    val requestManager = Glide.with(application)
                    val target = requestManager
                        .asBitmap()
                        .load(
                            galleryMediaModel(
                                uri = Uri.fromFile(fixtureFile),
                                mimeType = fixture.mimeType,
                            ),
                        )
                        .override(64, 64)
                        .diskCacheStrategy(DiskCacheStrategy.NONE)
                        .skipMemoryCache(true)
                        .submit()
                    try {
                        val bitmap = withContext(Dispatchers.IO) { target.get() }
                        assertEquals(fixture.decodedWidth, bitmap.width)
                        assertEquals(fixture.decodedHeight, bitmap.height)
                    } finally {
                        requestManager.clear(target)
                    }
                } finally {
                    fixtureFile.delete()
                }
            }
        }
    }

    @Test
    fun glideRoute_routesSandboxSignaturesDeclaredAsVideoByBytes() {
        runBlocking {
            val application = ApplicationProvider.getApplicationContext<GalleryApp>()
            listOf(
                DecoderFixture(
                    name = "GalleryDecoderTest.avif",
                    mimeType = "image/avif",
                    width = 4000,
                    height = 3000,
                    decodedWidth = 64,
                    decodedHeight = 48,
                ),
                DecoderFixture(
                    name = "GalleryDecoderTest.jxl",
                    mimeType = "image/jxl",
                    width = 4000,
                    height = 3000,
                    decodedWidth = 64,
                    decodedHeight = 48,
                ),
            ).forEach { fixture ->
                val fixtureFile = application.cacheDir.resolve(fixture.name)
                try {
                    fixtureFile.outputStream().use { outputStream ->
                        outputStream.write(readFixture(name = fixture.name))
                    }
                    val requestManager = Glide.with(application)
                    val target = requestManager
                        .asBitmap()
                        .load(
                            galleryMediaModel(
                                uri = Uri.fromFile(fixtureFile),
                                mimeType = "video/mp4",
                            ),
                        )
                        .override(64, 64)
                        .diskCacheStrategy(DiskCacheStrategy.NONE)
                        .skipMemoryCache(true)
                        .submit()
                    try {
                        val bitmap = withContext(Dispatchers.IO) { target.get() }
                        assertEquals(fixture.decodedWidth, bitmap.width)
                        assertEquals(fixture.decodedHeight, bitmap.height)
                    } finally {
                        requestManager.clear(target)
                    }
                } finally {
                    fixtureFile.delete()
                }
            }
        }
    }

    @Test
    fun glideRoute_preservesPlatformAnimationAndVideoDecoders() {
        runBlocking {
            val application = ApplicationProvider.getApplicationContext<GalleryApp>()
            val pngFile = writeFixtureFile(application = application, name = "GalleryDecoderTest.png")
            val gifFile = writeFixtureFile(application = application, name = "GalleryDecoderTest.gif")
            val webpFile = writeFixtureFile(application = application, name = "GalleryDecoderTest.webp")
            val videoFile = writeFixtureFile(application = application, name = "GalleryDecoderTest.mp4")
            try {
                withContext(Dispatchers.IO) {
                    val requestManager = Glide.with(application)
                    val pngTarget = requestManager
                        .asBitmap()
                        .load(
                            galleryMediaModel(
                                uri = Uri.fromFile(pngFile),
                                mimeType = "image/png",
                            ),
                        )
                        .diskCacheStrategy(DiskCacheStrategy.NONE)
                        .skipMemoryCache(true)
                        .submit()
                    try {
                        val pngBitmap = pngTarget.get()
                        assertEquals(16, pngBitmap.width)
                        assertEquals(16, pngBitmap.height)
                    } finally {
                        requestManager.clear(pngTarget)
                    }

                    val gifTarget = requestManager
                        .asGif()
                        .load(
                            galleryMediaModel(
                                uri = Uri.fromFile(gifFile),
                                mimeType = "image/gif",
                            ),
                        )
                        .diskCacheStrategy(DiskCacheStrategy.NONE)
                        .skipMemoryCache(true)
                        .submit()
                    try {
                        val gifDrawable = gifTarget.get()
                        assertTrue(gifDrawable.frameCount > 1)
                        gifDrawable.stop()
                    } finally {
                        requestManager.clear(gifTarget)
                    }

                    val webpTarget = requestManager
                        .asDrawable()
                        .load(
                            galleryMediaModel(
                                uri = Uri.fromFile(webpFile),
                                mimeType = "image/webp",
                            ),
                        )
                        .diskCacheStrategy(DiskCacheStrategy.NONE)
                        .skipMemoryCache(true)
                        .submit()
                    try {
                        val animatedWebp = webpTarget.get()
                        assertTrue(
                            "Expected AnimatedImageDrawable, got ${animatedWebp::class.java.name}",
                            animatedWebp is AnimatedImageDrawable,
                        )
                        (animatedWebp as AnimatedImageDrawable).stop()
                    } finally {
                        requestManager.clear(webpTarget)
                    }

                    val videoTarget = requestManager
                        .asBitmap()
                        .load(
                            galleryMediaModel(
                                uri = Uri.fromFile(videoFile),
                                mimeType = "video/mp4",
                            ),
                        )
                        .diskCacheStrategy(DiskCacheStrategy.NONE)
                        .skipMemoryCache(true)
                        .submit()
                    try {
                        val videoBitmap = videoTarget.get()
                        assertEquals(16, videoBitmap.width)
                        assertEquals(16, videoBitmap.height)
                    } finally {
                        requestManager.clear(videoTarget)
                    }
                }
            } finally {
                pngFile.delete()
                gifFile.delete()
                webpFile.delete()
                videoFile.delete()
            }
        }
    }

    @Test
    fun galleryMediaLoader_rejectsNonSeekableDeclaredVideo() {
        val (readDescriptor, writeDescriptor) = ParcelFileDescriptor.createPipe()
        writeDescriptor.close()
        val mediaSource = object : GalleryMediaSource {
            override fun getMimeType(uri: Uri): String {
                return "video/mp4"
            }

            override fun openInputStream(uri: Uri): InputStream? {
                error("Stream path must not be used for declared video")
            }

            override fun openFileDescriptor(uri: Uri): ParcelFileDescriptor {
                return readDescriptor
            }
        }
        val loader = GalleryMediaModelLoader(mediaSource = mediaSource)
        val loadData = loader.buildLoadData(
            model = GalleryMediaModel(
                uri = Uri.parse("content://gallery-test/non-seekable-video"),
                declaredMimeType = "video/mp4",
            ),
            width = 64,
            height = 64,
            options = Options(),
        )
        val callback = RecordingMediaCallback()

        loadData.fetcher.loadData(Priority.NORMAL, callback)

        assertNull(callback.data)
        assertNotNull(callback.failure)
    }

    @Test
    fun sketchAdapters_readSizeAndDecodeThroughIsolatedService() {
        runBlocking {
            val application = ApplicationProvider.getApplicationContext<GalleryApp>()
            listOf(
                DecoderFixture(
                    name = "GalleryDecoderTest.avif",
                    mimeType = "image/avif",
                    width = 4000,
                    height = 3000,
                    decodedWidth = 64,
                    decodedHeight = 48,
                ),
                DecoderFixture(
                    name = "GalleryDecoderTest.jxl",
                    mimeType = "image/jxl",
                    width = 4000,
                    height = 3000,
                    decodedWidth = 64,
                    decodedHeight = 48,
                ),
            ).forEach { fixture ->
                val fixtureFile = application.cacheDir.resolve(fixture.name)
                try {
                    fixtureFile.outputStream().use { outputStream ->
                        outputStream.write(readFixture(name = fixture.name))
                    }
                    val request = ImageRequest(application, fixtureFile.toURI().toString()) {
                        size(width = 64, height = 64)
                        setExtra(key = "realMimeType", value = fixture.mimeType)
                    }
                    val requestContext = RequestContext(
                        sketch = application.sketch,
                        request = request,
                    )
                    val dataSource = FileDataSource(path = fixtureFile.toOkioPath())
                    val sketchDecoder: Decoder = when (fixture.mimeType) {
                        "image/avif" -> SandboxedSketchHeifDecoder(
                            requestContext = requestContext,
                            dataSource = dataSource,
                            mimeType = fixture.mimeType,
                        )

                        else -> SandboxedSketchJxlDecoder(
                            requestContext = requestContext,
                            dataSource = dataSource,
                        )
                    }

                    val imageInfo = sketchDecoder.getImageInfo()
                    assertEquals(fixture.width, imageInfo.width)
                    assertEquals(fixture.height, imageInfo.height)

                    val imageData = sketchDecoder.decode()
                    val bitmap = (imageData.image as BitmapImage).bitmap
                    assertEquals(fixture.decodedWidth, bitmap.width)
                    assertEquals(fixture.decodedHeight, bitmap.height)
                    assertTrue(bitmap.byteCount > 0)
                    bitmap.recycle()
                } finally {
                    fixtureFile.delete()
                }
            }
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

    private fun assertPixelDataIsNotEmpty(result: IsolatedImageDecodeResult) {
        val pixels = IntArray(size = result.bitmap.width * result.bitmap.height)
        result.bitmap.getPixels(
            pixels,
            0,
            result.bitmap.width,
            0,
            0,
            result.bitmap.width,
            result.bitmap.height,
        )
        val firstPixel = pixels.first()
        check(pixels.any { pixel -> pixel != firstPixel }) {
            "Decoded image contained no varying pixel data"
        }
    }

    companion object {
        private const val EARLY_REJECTION_INPUT_BYTES = 1_000_000
        private const val EARLY_REJECTION_TIMEOUT_MILLIS = 5_000L
    }
}

private class ThrowingInputStream : InputStream() {
    override fun read(): Int {
        throw IOException("Synthetic writer failure")
    }
}

private class CloseAwareBlockingInputStream : InputStream() {
    private val closed = CountDownLatch(1)

    override fun read(): Int {
        closed.await()
        return -1
    }

    override fun close() {
        closed.countDown()
    }
}

private class RecordingMediaCallback : DataFetcher.DataCallback<GalleryMediaData> {
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
