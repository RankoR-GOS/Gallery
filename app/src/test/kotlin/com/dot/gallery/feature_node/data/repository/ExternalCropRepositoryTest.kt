package com.dot.gallery.feature_node.data.repository

import android.content.ContentResolver
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.dot.gallery.feature_node.data.model.editor.SaveFormat
import com.dot.gallery.feature_node.data.model.editor.crop.CropImage
import com.dot.gallery.feature_node.data.model.editor.crop.ExternalCropRequest
import com.dot.gallery.feature_node.data.model.editor.crop.NormalizedCropRect
import com.dot.gallery.feature_node.data.repository.ExternalCropRepository
import com.dot.gallery.testutil.MainDispatcherRule
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ExternalCropRepositoryTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun saveCropResult_withoutExplicitOutput_usesCroppedDisplayNameForInsertedOutput() {
        runTest(context = mainDispatcherRule.testDispatcher) {
            val sourceUri = Uri.parse("content://test/source")
            val outputUri = Uri.parse("content://media/external/images/media/42")
            val insertedValues = slot<ContentValues>()
            val publishedValues = slot<ContentValues>()
            val contentResolver = mockk<ContentResolver>()
            every {
                contentResolver.query(
                    sourceUri,
                    any(),
                    null,
                    null,
                    null,
                )
            } returns displayNameCursor(displayName = "avatar.png")
            every {
                contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    capture(insertedValues),
                )
            } returns outputUri
            every {
                contentResolver.openOutputStream(outputUri, "wt")
            } returns ByteArrayOutputStream()
            every {
                contentResolver.update(
                    outputUri,
                    capture(publishedValues),
                    null,
                    null,
                )
            } returns 1
            val repository = ExternalCropRepositoryImpl(
                contentResolver = contentResolver,
                defaultDispatcher = mainDispatcherRule.testDispatcher,
                ioDispatcher = mainDispatcherRule.testDispatcher,
            )

            val resultIntent = repository.saveCropResult(
                request = externalCropRequest(
                    sourceUri = sourceUri,
                    outputFormat = "image/jpeg",
                ),
                image = cropImage(),
                normalizedRect = fullCropRect(),
            )

            assertNotNull(resultIntent)
            assertEquals(outputUri, resultIntent?.data)
            assertEquals(
                "avatar-cropped.jpg",
                insertedValues.captured.getAsString(MediaStore.MediaColumns.DISPLAY_NAME),
            )
            assertEquals(
                SaveFormat.JPEG.mimeType,
                insertedValues.captured.getAsString(MediaStore.MediaColumns.MIME_TYPE),
            )
            assertEquals(
                0,
                publishedValues.captured.getAsInteger(MediaStore.MediaColumns.IS_PENDING),
            )
        }
    }

    @Test
    fun saveCropResult_withExplicitOutput_returnsExplicitOutputUri() {
        runTest(context = mainDispatcherRule.testDispatcher) {
            val outputUri = Uri.parse("content://test/output")
            val contentResolver = mockk<ContentResolver>()
            every {
                contentResolver.openOutputStream(outputUri, "wt")
            } returns ByteArrayOutputStream()
            val repository = externalCropRepository(contentResolver = contentResolver)

            val resultIntent = repository.saveCropResult(
                request = externalCropRequest(outputUri = outputUri),
                image = cropImage(),
                normalizedRect = fullCropRect(),
            )

            assertNotNull(resultIntent)
            assertEquals(outputUri, resultIntent?.data)
            verify(exactly = 0) {
                contentResolver.insert(any(), any())
            }
        }
    }

    @Test
    fun saveCropResult_whenExplicitOutputWriteFails_returnsNull() {
        runTest(context = mainDispatcherRule.testDispatcher) {
            val outputUri = Uri.parse("content://test/output")
            val contentResolver = mockk<ContentResolver>()
            every {
                contentResolver.openOutputStream(outputUri, "wt")
            } returns null
            every {
                contentResolver.openOutputStream(outputUri)
            } returns null
            val repository = externalCropRepository(contentResolver = contentResolver)

            val resultIntent = repository.saveCropResult(
                request = externalCropRequest(outputUri = outputUri),
                image = cropImage(),
                normalizedRect = fullCropRect(),
            )

            assertNull(resultIntent)
            verify(exactly = 0) {
                contentResolver.insert(any(), any())
            }
        }
    }

    @Test
    fun saveCropResult_whenFallbackWriteFails_deletesInsertedOutput() {
        runTest(context = mainDispatcherRule.testDispatcher) {
            val sourceUri = Uri.parse("content://test/source")
            val outputUri = Uri.parse("content://media/external/images/media/42")
            val contentResolver = mockk<ContentResolver>()
            every {
                contentResolver.query(
                    sourceUri,
                    any(),
                    null,
                    null,
                    null,
                )
            } returns displayNameCursor(displayName = "avatar.png")
            every {
                contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    any(),
                )
            } returns outputUri
            every {
                contentResolver.openOutputStream(outputUri, "wt")
            } returns null
            every {
                contentResolver.openOutputStream(outputUri)
            } returns null
            every {
                contentResolver.delete(outputUri, null, null)
            } returns 1
            val repository = externalCropRepository(contentResolver = contentResolver)

            val resultIntent = repository.saveCropResult(
                request = externalCropRequest(sourceUri = sourceUri),
                image = cropImage(),
                normalizedRect = fullCropRect(),
            )

            assertNull(resultIntent)
            verify(exactly = 1) {
                contentResolver.delete(outputUri, null, null)
            }
            verify(exactly = 0) {
                contentResolver.update(outputUri, any(), null, null)
            }
        }
    }

    @Test
    fun saveCropResult_whenFallbackPublishFails_deletesInsertedOutput() {
        runTest(context = mainDispatcherRule.testDispatcher) {
            val sourceUri = Uri.parse("content://test/source")
            val outputUri = Uri.parse("content://media/external/images/media/42")
            val contentResolver = mockk<ContentResolver>()
            every {
                contentResolver.query(
                    sourceUri,
                    any(),
                    null,
                    null,
                    null,
                )
            } returns displayNameCursor(displayName = "avatar.png")
            every {
                contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    any(),
                )
            } returns outputUri
            every {
                contentResolver.openOutputStream(outputUri, "wt")
            } returns ByteArrayOutputStream()
            every {
                contentResolver.update(outputUri, any(), null, null)
            } returns 0
            every {
                contentResolver.delete(outputUri, null, null)
            } returns 1
            val repository = externalCropRepository(contentResolver = contentResolver)

            val resultIntent = repository.saveCropResult(
                request = externalCropRequest(sourceUri = sourceUri),
                image = cropImage(),
                normalizedRect = fullCropRect(),
            )

            assertNull(resultIntent)
            verify(exactly = 1) {
                contentResolver.delete(outputUri, null, null)
            }
        }
    }

    @Test
    fun saveCropResult_whenReturnDataIsRequested_doesNotInsertFallbackOutput() {
        runTest(context = mainDispatcherRule.testDispatcher) {
            val contentResolver = mockk<ContentResolver>()
            val repository = externalCropRepository(contentResolver = contentResolver)

            val resultIntent = repository.saveCropResult(
                request = externalCropRequest(returnData = true),
                image = cropImage(),
                normalizedRect = fullCropRect(),
            )

            assertNotNull(resultIntent)
            assertNull(resultIntent?.data)
            assertEquals(true, resultIntent?.hasExtra("data"))
            verify(exactly = 0) {
                contentResolver.insert(any(), any())
            }
        }
    }

    @Test
    fun saveCropResult_whenScaleIsDisabledAndCropIsSmaller_centersCropOnRequestedCanvas() {
        runTest(context = mainDispatcherRule.testDispatcher) {
            val outputUri = Uri.parse("content://test/output")
            val outputStream = ByteArrayOutputStream()
            val contentResolver = mockk<ContentResolver>()
            every {
                contentResolver.openOutputStream(outputUri, "wt")
            } returns outputStream
            val repository = externalCropRepository(contentResolver = contentResolver)

            val resultIntent = repository.saveCropResult(
                request = externalCropRequest(
                    outputUri = outputUri,
                    outputX = 4,
                    outputY = 4,
                    scale = false,
                    outputFormat = "image/png",
                ),
                image = solidCropImage(
                    width = 2,
                    height = 2,
                    color = Color.RED,
                ),
                normalizedRect = fullCropRect(),
            )

            val decodedBitmap = decodeWrittenBitmap(outputStream = outputStream)
            assertNotNull(resultIntent)
            assertEquals(4, decodedBitmap.width)
            assertEquals(4, decodedBitmap.height)
            assertEquals(Color.TRANSPARENT, decodedBitmap.getPixel(0, 0))
            assertEquals(Color.RED, decodedBitmap.getPixel(1, 1))
            assertEquals(Color.RED, decodedBitmap.getPixel(2, 2))
        }
    }

    @Test
    fun saveCropResult_whenScaleIsDisabledAndCropIsLarger_usesCenteredSourceRegion() {
        runTest(context = mainDispatcherRule.testDispatcher) {
            val outputUri = Uri.parse("content://test/output")
            val outputStream = ByteArrayOutputStream()
            val contentResolver = mockk<ContentResolver>()
            every {
                contentResolver.openOutputStream(outputUri, "wt")
            } returns outputStream
            val repository = externalCropRepository(contentResolver = contentResolver)

            val resultIntent = repository.saveCropResult(
                request = externalCropRequest(
                    outputUri = outputUri,
                    outputX = 2,
                    outputY = 2,
                    scale = false,
                    outputFormat = "image/png",
                ),
                image = patternedCropImage(),
                normalizedRect = fullCropRect(),
            )

            val decodedBitmap = decodeWrittenBitmap(outputStream = outputStream)
            assertNotNull(resultIntent)
            assertEquals(2, decodedBitmap.width)
            assertEquals(2, decodedBitmap.height)
            assertEquals(patternColor(x = 1, y = 1), decodedBitmap.getPixel(0, 0))
            assertEquals(patternColor(x = 2, y = 1), decodedBitmap.getPixel(1, 0))
            assertEquals(patternColor(x = 1, y = 2), decodedBitmap.getPixel(0, 1))
            assertEquals(patternColor(x = 2, y = 2), decodedBitmap.getPixel(1, 1))
        }
    }

    @Test
    fun saveCropResult_whenScaleIsEnabled_scalesToRequestedOutput() {
        runTest(context = mainDispatcherRule.testDispatcher) {
            val outputUri = Uri.parse("content://test/output")
            val outputStream = ByteArrayOutputStream()
            val contentResolver = mockk<ContentResolver>()
            every {
                contentResolver.openOutputStream(outputUri, "wt")
            } returns outputStream
            val repository = externalCropRepository(contentResolver = contentResolver)

            val resultIntent = repository.saveCropResult(
                request = externalCropRequest(
                    outputUri = outputUri,
                    outputX = 4,
                    outputY = 4,
                    scale = true,
                    scaleUpIfNeeded = true,
                    outputFormat = "image/png",
                ),
                image = solidCropImage(
                    width = 2,
                    height = 2,
                    color = Color.RED,
                ),
                normalizedRect = fullCropRect(),
            )

            val decodedBitmap = decodeWrittenBitmap(outputStream = outputStream)
            assertNotNull(resultIntent)
            assertEquals(4, decodedBitmap.width)
            assertEquals(4, decodedBitmap.height)
            assertEquals(Color.RED, decodedBitmap.getPixel(0, 0))
            assertEquals(Color.RED, decodedBitmap.getPixel(3, 3))
        }
    }

    private fun externalCropRequest(
        sourceUri: Uri = Uri.parse("content://test/source"),
        outputUri: Uri? = null,
        outputX: Int = 0,
        outputY: Int = 0,
        scale: Boolean = true,
        scaleUpIfNeeded: Boolean = true,
        returnData: Boolean = false,
        outputFormat: String? = null,
    ): ExternalCropRequest {
        return ExternalCropRequest(
            sourceUri = sourceUri,
            outputUri = outputUri,
            outputX = outputX,
            outputY = outputY,
            scale = scale,
            scaleUpIfNeeded = scaleUpIfNeeded,
            aspectX = 0,
            aspectY = 0,
            returnData = returnData,
            outputFormat = outputFormat,
        )
    }

    private fun externalCropRepository(contentResolver: ContentResolver): ExternalCropRepository {
        return ExternalCropRepositoryImpl(
            contentResolver = contentResolver,
            defaultDispatcher = mainDispatcherRule.testDispatcher,
            ioDispatcher = mainDispatcherRule.testDispatcher,
        )
    }

    private fun cropImage(): CropImage {
        val bitmap = Bitmap.createBitmap(
            4,
            4,
            Bitmap.Config.ARGB_8888,
        )
        return CropImage(
            sourceBitmap = bitmap,
            previewBitmap = bitmap,
        )
    }

    private fun solidCropImage(width: Int, height: Int, color: Int): CropImage {
        val bitmap = Bitmap.createBitmap(
            width,
            height,
            Bitmap.Config.ARGB_8888,
        )
        bitmap.eraseColor(color)

        return CropImage(
            sourceBitmap = bitmap,
            previewBitmap = bitmap,
        )
    }

    private fun patternedCropImage(): CropImage {
        val bitmap = Bitmap.createBitmap(
            4,
            4,
            Bitmap.Config.ARGB_8888,
        )
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                bitmap.setPixel(x, y, patternColor(x = x, y = y))
            }
        }

        return CropImage(
            sourceBitmap = bitmap,
            previewBitmap = bitmap,
        )
    }

    private fun patternColor(x: Int, y: Int): Int {
        return Color.rgb(
            x * 40,
            y * 40,
            (x + y) * 20,
        )
    }

    private fun decodeWrittenBitmap(outputStream: ByteArrayOutputStream): Bitmap {
        val bytes = outputStream.toByteArray()
        return requireNotNull(
            BitmapFactory.decodeByteArray(
                bytes,
                0,
                bytes.size,
            ),
        )
    }

    private fun fullCropRect(): NormalizedCropRect {
        return NormalizedCropRect(
            left = 0f,
            top = 0f,
            right = 1f,
            bottom = 1f,
        )
    }

    private fun displayNameCursor(displayName: String): Cursor {
        return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply {
            addRow(arrayOf(displayName))
        }
    }
}
