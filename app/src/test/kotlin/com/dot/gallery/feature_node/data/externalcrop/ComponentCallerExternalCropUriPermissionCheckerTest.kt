package com.dot.gallery.feature_node.data.externalcrop

import android.app.ComponentCaller
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.os.Process
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ComponentCallerExternalCropUriPermissionCheckerTest {

    @Test
    fun canWriteContentUri_acceptsReachableMediaStoreUri() {
        val fixture = permissionChecker(resolvedType = "image/jpeg")

        assertTrue(
            fixture.checker.canWriteContentUri(
                uri = OUTPUT_URI,
                caller = fixture.caller,
            ),
        )
    }

    @Test
    fun canWriteContentUri_rejectsUnreachableMediaStoreUri() {
        val fixture = permissionChecker(resolvedType = null)

        assertFalse(
            fixture.checker.canWriteContentUri(
                uri = OUTPUT_URI,
                caller = fixture.caller,
            ),
        )
    }

    @Test
    fun canWriteContentUri_rejectsForeignAuthority() {
        val fixture = permissionChecker(resolvedType = "image/jpeg")

        assertFalse(
            fixture.checker.canWriteContentUri(
                uri = Uri.parse("content://com.example.provider/output"),
                caller = fixture.caller,
            ),
        )
    }

    @Test
    fun canWriteContentUri_returnsFalseWhenPlatformThrowsSecurityException() {
        val fixture = permissionChecker(
            resolveException = SecurityException("not accessible"),
        )

        assertFalse(
            fixture.checker.canWriteContentUri(
                uri = OUTPUT_URI,
                caller = fixture.caller,
            ),
        )
    }

    /**
     * The output uri never belongs to the launch grant set, so routing it through
     * [ComponentCaller.checkContentUriPermission] makes the platform throw and every external crop
     * request carrying an output uri gets rejected. Pin that it is never used here.
     */
    @Test
    fun canWriteContentUri_neverUsesLaunchGrantApi() {
        val fixture = permissionChecker(resolvedType = "image/jpeg")

        fixture.checker.canWriteContentUri(
            uri = OUTPUT_URI,
            caller = fixture.caller,
        )

        verify(exactly = 0) { fixture.caller.checkContentUriPermission(any(), any()) }
    }

    /**
     * `"w"` truncates the target on some providers, so probing writability by opening the output
     * would destroy it before the user confirms the crop.
     */
    @Test
    fun canWriteContentUri_neverOpensTheOutput() {
        val fixture = permissionChecker(resolvedType = "image/jpeg")

        fixture.checker.canWriteContentUri(
            uri = OUTPUT_URI,
            caller = fixture.caller,
        )

        verify(exactly = 0) { fixture.contentResolver.openFileDescriptor(any(), any()) }
        verify(exactly = 0) { fixture.contentResolver.openOutputStream(any()) }
    }

    @Test
    fun canReadFileUri_acceptsSharedImageDirectories() {
        val checker = ComponentCallerExternalCropUriPermissionChecker(
            context = mockk<Context>(relaxed = true),
            packageManager = mockk<PackageManager>(relaxed = true),
        )
        val caller = currentProcessCaller()
        val picturesFile = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "image.jpg",
        )
        val dcimFile = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
            "Camera/image.jpg",
        )

        assertTrue(
            checker.canReadFileUri(
                uri = Uri.fromFile(picturesFile),
                caller = caller,
            ),
        )
        assertTrue(
            checker.canReadFileUri(
                uri = Uri.fromFile(dcimFile),
                caller = caller,
            ),
        )
    }

    @Test
    fun canReadFileUri_rejectsPrivateAndEscapedPaths() {
        val checker = ComponentCallerExternalCropUriPermissionChecker(
            context = mockk<Context>(relaxed = true),
            packageManager = mockk<PackageManager>(relaxed = true),
        )
        val caller = currentProcessCaller()
        val privateUri = Uri.parse("file:///data/data/com.dot.gallery/files/image.jpg")
        val escapedUri = Uri.parse(
            "file:///storage/emulated/0/Pictures/../Android/data/com.example/files/image.jpg",
        )

        assertFalse(
            checker.canReadFileUri(
                uri = privateUri,
                caller = caller,
            ),
        )
        assertFalse(
            checker.canReadFileUri(
                uri = escapedUri,
                caller = caller,
            ),
        )
    }

    private fun permissionChecker(
        resolvedType: String? = null,
        resolveException: RuntimeException? = null,
    ): PermissionCheckerFixture {
        val caller = mockk<ComponentCaller>()
        every { caller.uid } returns Process.myUid() + 1

        val contentResolver = mockk<ContentResolver>(relaxed = true)
        val typeCheck = every { contentResolver.getType(any()) }
        if (resolveException == null) {
            typeCheck returns resolvedType
        } else {
            typeCheck throws resolveException
        }

        val context = mockk<Context>()
        every { context.contentResolver } returns contentResolver

        return PermissionCheckerFixture(
            checker = ComponentCallerExternalCropUriPermissionChecker(
                context = context,
                packageManager = mockk<PackageManager>(relaxed = true),
            ),
            caller = caller,
            contentResolver = contentResolver,
        )
    }

    private fun currentProcessCaller(): ComponentCaller {
        return mockk<ComponentCaller>().apply {
            every { uid } returns Process.myUid()
        }
    }

    private data class PermissionCheckerFixture(
        val checker: ComponentCallerExternalCropUriPermissionChecker,
        val caller: ComponentCaller,
        val contentResolver: ContentResolver,
    )

    private companion object {
        val OUTPUT_URI: Uri = Uri.parse("content://media/external/images/media/42")
    }
}
