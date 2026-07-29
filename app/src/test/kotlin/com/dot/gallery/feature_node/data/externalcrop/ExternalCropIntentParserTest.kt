package com.dot.gallery.feature_node.data.externalcrop

import android.app.ComponentCaller
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import com.dot.gallery.feature_node.data.externalcrop.ExternalCropIntentParser
import com.dot.gallery.feature_node.data.model.editor.crop.ExternalCropRequest
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExternalCropIntentParserTest {

    @Test
    fun parse_acceptsGrantedContentSource() {
        val sourceUri = Uri.parse("content://source/image")
        val request = parse(
            intent = cropIntent(sourceUri = sourceUri),
            uriPermissionChecker = uriPermissionChecker(
                readableContentUris = setOf(sourceUri),
            ),
        )

        assertNotNull(request)
        assertEquals(sourceUri, request?.sourceUri)
    }

    @Test
    fun parse_rejectsContentSourceWithoutCallerReadPermission() {
        val request = parse(
            intent = cropIntent(
                sourceUri = Uri.parse("content://source/image"),
            ),
            uriPermissionChecker = uriPermissionChecker(),
        )

        assertNull(request)
    }

    @Test
    fun parse_rejectsUnsupportedSourceScheme() {
        val request = parse(
            intent = cropIntent(
                sourceUri = Uri.parse("https://example.test/image.jpg"),
            ),
            uriPermissionChecker = uriPermissionChecker(),
        )

        assertNull(request)
    }

    @Test
    fun parse_acceptsAllowedFileSource() {
        val sourceUri = Uri.parse("file:///storage/emulated/0/Pictures/image.jpg")
        val request = parse(
            intent = cropIntent(sourceUri = sourceUri),
            uriPermissionChecker = uriPermissionChecker(
                readableFileUris = setOf(sourceUri),
            ),
        )

        assertNotNull(request)
        assertEquals(sourceUri, request?.sourceUri)
    }

    @Test
    fun parse_rejectsFileSourceWithoutCallerPermission() {
        val request = parse(
            intent = cropIntent(
                sourceUri = Uri.parse("file:///storage/emulated/0/Pictures/image.jpg"),
            ),
            uriPermissionChecker = uriPermissionChecker(),
        )

        assertNull(request)
    }

    @Test
    fun parse_acceptsGrantedContentOutput() {
        val sourceUri = Uri.parse("content://source/image")
        val outputUri = Uri.parse("content://output/image")
        val request = parse(
            intent = cropIntent(
                sourceUri = sourceUri,
                outputUri = outputUri,
            ),
            uriPermissionChecker = uriPermissionChecker(
                readableContentUris = setOf(sourceUri),
                writableContentUris = setOf(outputUri),
            ),
        )

        assertNotNull(request)
        assertEquals(outputUri, request?.outputUri)
    }

    @Test
    fun parse_rejectsContentOutputWithoutCallerWritePermission() {
        val sourceUri = Uri.parse("content://source/image")
        val outputUri = Uri.parse("content://output/image")
        val request = parse(
            intent = cropIntent(
                sourceUri = sourceUri,
                outputUri = outputUri,
            ),
            uriPermissionChecker = uriPermissionChecker(
                readableContentUris = setOf(sourceUri),
            ),
        )

        assertNull(request)
    }

    @Test
    fun parse_rejectsFileOutput() {
        val sourceUri = Uri.parse("content://source/image")
        val request = parse(
            intent = cropIntent(
                sourceUri = sourceUri,
                outputUri = Uri.parse("file:///storage/emulated/0/Pictures/output.jpg"),
            ),
            uriPermissionChecker = uriPermissionChecker(
                readableContentUris = setOf(sourceUri),
            ),
        )

        assertNull(request)
    }

    @Test
    fun parse_withoutOutputDoesNotForceFallbackOutput() {
        val sourceUri = Uri.parse("content://source/image")
        val request = parse(
            intent = cropIntent(sourceUri = sourceUri).apply {
                putExtra("return-data", true)
            },
            uriPermissionChecker = uriPermissionChecker(
                readableContentUris = setOf(sourceUri),
            ),
        )

        assertNotNull(request)
        assertNull(request?.outputUri)
    }

    @Test
    fun parse_rejectsWrongAction() {
        val sourceUri = Uri.parse("content://source/image")
        val request = parse(
            intent = Intent(Intent.ACTION_VIEW).apply {
                data = sourceUri
            },
            uriPermissionChecker = uriPermissionChecker(
                readableContentUris = setOf(sourceUri),
            ),
        )

        assertNull(request)
    }

    @Test
    fun parse_ignoresShowWhenLockedExtra() {
        val sourceUri = Uri.parse("content://source/image")
        val request = parse(
            intent = cropIntent(sourceUri = sourceUri).apply {
                putExtra("showWhenLocked", true)
            },
            uriPermissionChecker = uriPermissionChecker(
                readableContentUris = setOf(sourceUri),
            ),
        )

        assertNotNull(request)
    }

    @Test
    fun parse_rejectsNonUriOutputExtra() {
        val sourceUri = Uri.parse("content://source/image")
        val request = parse(
            intent = cropIntent(sourceUri = sourceUri).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, "not a uri")
            },
            uriPermissionChecker = uriPermissionChecker(
                readableContentUris = setOf(sourceUri),
            ),
        )

        assertNull(request)
    }

    @Test
    fun parse_doesNotSwallowRuntimeFailure() {
        val sourceUri = Uri.parse("content://source/image")
        val exception = RuntimeException("parser bug")
        val thrownException = assertThrows(
            RuntimeException::class.java,
        ) {
            parse(
                intent = cropIntent(sourceUri = sourceUri),
                uriPermissionChecker = throwingUriPermissionChecker(
                    throwable = exception,
                ),
            )
        }

        assertSame(
            exception,
            thrownException,
        )
    }

    private fun cropIntent(
        sourceUri: Uri,
        outputUri: Uri? = null,
    ): Intent {
        return Intent(ExternalCropIntentParser.ACTION_CROP).apply {
            data = sourceUri
            outputUri?.let { uri ->
                putExtra(MediaStore.EXTRA_OUTPUT, uri)
            }
        }
    }

    private fun parse(
        intent: Intent,
        uriPermissionChecker: ExternalCropUriPermissionChecker,
    ): ExternalCropRequest? {
        val parser = ExternalCropIntentParserImpl(uriPermissionChecker = uriPermissionChecker)
        return parser.parse(
            intent = intent,
            caller = mockk<ComponentCaller>(relaxed = true),
        )
    }

    private fun uriPermissionChecker(
        readableContentUris: Set<Uri> = emptySet(),
        writableContentUris: Set<Uri> = emptySet(),
        readableFileUris: Set<Uri> = emptySet(),
    ): ExternalCropUriPermissionChecker {
        return mockk<ExternalCropUriPermissionChecker>().apply {
            every { canReadContentUri(uri = any(), caller = any()) } answers {
                firstArg<Uri>() in readableContentUris
            }
            every { canWriteContentUri(uri = any(), caller = any()) } answers {
                firstArg<Uri>() in writableContentUris
            }
            every { canReadFileUri(uri = any(), caller = any()) } answers {
                firstArg<Uri>() in readableFileUris
            }
        }
    }

    private fun throwingUriPermissionChecker(
        throwable: RuntimeException,
    ): ExternalCropUriPermissionChecker {
        return mockk<ExternalCropUriPermissionChecker>().apply {
            every { canReadContentUri(uri = any(), caller = any()) } throws throwable
        }
    }
}
