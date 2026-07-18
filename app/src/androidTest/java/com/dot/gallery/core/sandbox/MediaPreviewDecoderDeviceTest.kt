package com.dot.gallery.core.sandbox

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.provider.MediaStore
import android.system.Os
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dot.gallery.GalleryApp
import kotlin.math.abs
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
internal class MediaPreviewDecoderDeviceTest {
    private val application = ApplicationProvider.getApplicationContext<GalleryApp>()
    private val contentResolver = application.contentResolver
    private val decoder = application.mediaPreviewDecoder
    private val insertedUris = mutableListOf<Uri>()

    @After
    fun deleteFixtures() {
        insertedUris.forEach { uri ->
            contentResolver.delete(uri, null, null)
        }
    }

    @Test
    fun imageAndVideoFixtures_decodeToBoundedNonEmptyPreviews() {
        runBlocking {
            listOf(
                Fixture(name = "GalleryDecoderTest.png", mimeType = "image/png", isVideo = false),
                Fixture(name = "GalleryDecoderTest.gif", mimeType = "image/gif", isVideo = false),
                Fixture(name = "GalleryDecoderTest.webp", mimeType = "image/webp", isVideo = false),
                Fixture(name = "GalleryDecoderTest.svg", mimeType = "image/svg+xml", isVideo = false),
                Fixture(name = "GalleryDecoderTest.avif", mimeType = "image/avif", isVideo = false),
                Fixture(name = "GalleryDecoderTest.jxl", mimeType = "image/jxl", isVideo = false),
                Fixture(name = "GalleryDecoderTest.mp4", mimeType = "video/mp4", isVideo = true),
            ).forEach { fixture ->
                val uri = insertFixture(fixture = fixture)
                val result = decoder.decode(
                    uri = uri,
                    mimeType = fixture.mimeType,
                    isVideo = fixture.isVideo,
                )

                assertNotNull(fixture.name, result)
                requireNotNull(result).let { bitmap ->
                    assertEquals(PREVIEW_SIZE, bitmap.width)
                    assertEquals(PREVIEW_SIZE, bitmap.height)
                    assertBitmapHasPixels(bitmap = bitmap)
                    bitmap.recycle()
                }
            }
        }
    }

    @Test
    fun recognizedImageSignature_overridesVideoMimeType() {
        runBlocking {
            val uri = insertFixture(
                fixture = Fixture(
                    name = "GalleryDecoderTest.avif",
                    mimeType = "video/mp4",
                    isVideo = true,
                ),
            )

            val result = decoder.decode(
                uri = uri,
                mimeType = "video/mp4",
                isVideo = true,
            )

            assertNotNull(result)
            requireNotNull(result).recycle()
        }
    }

    @Test
    fun videoLargerThanImageLimit_decodesThumbnail() {
        runBlocking {
            val uri = insertFixture(
                fixture = Fixture(
                    name = "GalleryDecoderTest.mp4",
                    mimeType = "video/mp4",
                    isVideo = true,
                ),
                minimumSize = LARGE_VIDEO_SIZE_BYTES,
            )

            val result = decoder.decode(
                uri = uri,
                mimeType = "video/mp4",
                isVideo = true,
            )

            assertNotNull(result)
            requireNotNull(result).recycle()
        }
    }

    @Test
    fun videoWithUnknownStatSize_decodesThumbnail() {
        runBlocking {
            val uri = Uri.parse("content://${application.packageName}.unknownsize/video")

            val result = withTimeout(timeMillis = DEVICE_TEST_TIMEOUT_MILLIS) {
                decoder.decode(
                    uri = uri,
                    mimeType = "video/mp4",
                    isVideo = true,
                )
            }

            assertNotNull(result)
            requireNotNull(result).recycle()
        }
    }

    @Test
    fun svgLargerThanLimit_isRejected() {
        runBlocking {
            val uri = insertFixture(
                fixture = Fixture(
                    name = "GalleryDecoderTest.svg",
                    mimeType = "image/svg+xml",
                    isVideo = false,
                ),
                minimumSize = LARGE_SVG_SIZE_BYTES,
            )

            val result = decoder.decode(
                uri = uri,
                mimeType = "image/svg+xml",
                isVideo = false,
            )

            assertNull(result)
        }
    }

    @Test
    fun nonSquarePlatformImages_decodeToSquarePreviews() {
        runBlocking {
            listOf(
                GeneratedFixture(
                    name = "GalleryDecoderLandscape.png",
                    mimeType = "image/png",
                    width = 640,
                    height = 320,
                    format = Bitmap.CompressFormat.PNG,
                ),
                GeneratedFixture(
                    name = "GalleryDecoderPortrait.jpg",
                    mimeType = "image/jpeg",
                    width = 320,
                    height = 640,
                    format = Bitmap.CompressFormat.JPEG,
                ),
            ).forEach { fixture ->
                val uri = insertGeneratedFixture(fixture = fixture)

                val result = decoder.decode(
                    uri = uri,
                    mimeType = fixture.mimeType,
                    isVideo = false,
                )

                assertNotNull(fixture.name, result)
                requireNotNull(result).let { bitmap ->
                    assertEquals(PREVIEW_SIZE, bitmap.width)
                    assertEquals(PREVIEW_SIZE, bitmap.height)
                    assertBitmapHasPixels(bitmap = bitmap)
                    bitmap.recycle()
                }
            }
        }
    }

    @Test
    fun smallNonSquareImages_preserveCenteredShapeAspectRatio() {
        runBlocking {
            listOf(
                GeneratedFixture(
                    name = "GalleryDecoderSmallLandscape.png",
                    mimeType = "image/png",
                    width = 32,
                    height = 16,
                    format = Bitmap.CompressFormat.PNG,
                ),
                GeneratedFixture(
                    name = "GalleryDecoderSmallPortrait.png",
                    mimeType = "image/png",
                    width = 16,
                    height = 32,
                    format = Bitmap.CompressFormat.PNG,
                ),
            ).forEach { fixture ->
                val uri = insertGeneratedFixture(
                    fixture = fixture,
                    drawGeometryPattern = true,
                )

                val result = decoder.decode(
                    uri = uri,
                    mimeType = fixture.mimeType,
                    isVideo = false,
                )

                assertNotNull(fixture.name, result)
                requireNotNull(result).let { bitmap ->
                    assertCenteredShapeIsSquare(name = fixture.name, bitmap = bitmap)
                    bitmap.recycle()
                }
            }
        }
    }

    @Test
    fun concurrentPreviewRequests_completeInDedicatedIsolatedInstance() {
        runBlocking {
            val fixtures = listOf(
                Fixture(name = "GalleryDecoderTest.png", mimeType = "image/png", isVideo = false),
                Fixture(name = "GalleryDecoderTest.avif", mimeType = "image/avif", isVideo = false),
                Fixture(name = "GalleryDecoderTest.mp4", mimeType = "video/mp4", isVideo = true),
            ).map { fixture -> fixture to insertFixture(fixture = fixture) }

            fixtures.flatMap { (fixture, uri) ->
                List(size = 3) {
                    async {
                        decoder.decode(
                            uri = uri,
                            mimeType = fixture.mimeType,
                            isVideo = fixture.isVideo,
                        )
                    }
                }
            }.awaitAll().forEach { result ->
                assertNotNull(result)
                requireNotNull(result).recycle()
            }
        }
    }

    private fun insertFixture(fixture: Fixture, minimumSize: Long? = null): Uri {
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = requireNotNull(
            contentResolver.insert(
                collection,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "analysis_${fixture.name}")
                    put(MediaStore.MediaColumns.MIME_TYPE, fixture.mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/ReFraAnalysisTests")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                },
            ),
        )
        insertedUris += uri
        contentResolver.openOutputStream(uri, "w").use { outputStream ->
            requireNotNull(outputStream).write(readFixture(name = fixture.name))
        }
        minimumSize?.let { size ->
            contentResolver.openFileDescriptor(uri, "rw").use { descriptor ->
                Os.ftruncate(requireNotNull(descriptor).fileDescriptor, size)
            }
        }
        contentResolver.update(
            uri,
            ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            },
            null,
            null,
        )
        return uri
    }

    private fun insertGeneratedFixture(
        fixture: GeneratedFixture,
        drawGeometryPattern: Boolean = false,
    ): Uri {
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = requireNotNull(
            contentResolver.insert(
                collection,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "analysis_${fixture.name}")
                    put(MediaStore.MediaColumns.MIME_TYPE, fixture.mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/ReFraAnalysisTests")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                },
            ),
        )
        insertedUris += uri
        val bitmap = Bitmap.createBitmap(fixture.width, fixture.height, Bitmap.Config.ARGB_8888)
        try {
            when {
                drawGeometryPattern -> {
                    bitmap.eraseColor(GEOMETRY_BACKGROUND_COLOR)
                    Canvas(bitmap).drawCircle(
                        fixture.width / 2f,
                        fixture.height / 2f,
                        minOf(fixture.width, fixture.height) * GEOMETRY_RADIUS_FRACTION,
                        Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = GEOMETRY_FOREGROUND_COLOR
                        },
                    )
                }

                else -> bitmap.eraseColor(Color.MAGENTA)
            }
            contentResolver.openOutputStream(uri, "w").use { outputStream ->
                check(
                    bitmap.compress(
                        fixture.format,
                        GENERATED_IMAGE_QUALITY,
                        requireNotNull(outputStream),
                    ),
                )
            }
        } finally {
            bitmap.recycle()
        }
        contentResolver.update(
            uri,
            ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            },
            null,
            null,
        )
        return uri
    }

    private fun readFixture(name: String): ByteArray {
        return InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { input ->
            input.readBytes()
        }
    }

    private fun assertBitmapHasPixels(bitmap: Bitmap) {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(
            pixels,
            0,
            bitmap.width,
            0,
            0,
            bitmap.width,
            bitmap.height,
        )
        assertTrue(pixels.any { pixel -> pixel != 0 })
    }

    private fun assertCenteredShapeIsSquare(name: String, bitmap: Bitmap) {
        val foregroundCoordinates = buildList {
            for (y in 0 until bitmap.height) {
                for (x in 0 until bitmap.width) {
                    val pixel = bitmap.getPixel(x, y)
                    if (Color.green(pixel) > Color.red(pixel)) {
                        add(x to y)
                    }
                }
            }
        }
        assertTrue("$name has no foreground shape", foregroundCoordinates.isNotEmpty())
        val shapeWidth = foregroundCoordinates.maxOf { (x, _) -> x } -
            foregroundCoordinates.minOf { (x, _) -> x } + 1
        val shapeHeight = foregroundCoordinates.maxOf { (_, y) -> y } -
            foregroundCoordinates.minOf { (_, y) -> y } + 1
        assertTrue(
            "$name shape was distorted to ${shapeWidth}x${shapeHeight}",
            abs(shapeWidth - shapeHeight) <= GEOMETRY_SIZE_TOLERANCE_PIXELS,
        )
    }

    private data class Fixture(
        val name: String,
        val mimeType: String,
        val isVideo: Boolean,
    )

    private data class GeneratedFixture(
        val name: String,
        val mimeType: String,
        val width: Int,
        val height: Int,
        val format: Bitmap.CompressFormat,
    )

    companion object {
        private const val GEOMETRY_BACKGROUND_COLOR = Color.RED
        private const val GEOMETRY_FOREGROUND_COLOR = Color.GREEN
        private const val GEOMETRY_RADIUS_FRACTION = 0.375f
        private const val GEOMETRY_SIZE_TOLERANCE_PIXELS = 8
        private const val GENERATED_IMAGE_QUALITY = 90
        private const val LARGE_SVG_SIZE_BYTES = 17L * 1024L * 1024L
        private const val LARGE_VIDEO_SIZE_BYTES = 129L * 1024L * 1024L
        private const val PREVIEW_SIZE = 224
        private const val DEVICE_TEST_TIMEOUT_MILLIS = 10_000L
    }
}
