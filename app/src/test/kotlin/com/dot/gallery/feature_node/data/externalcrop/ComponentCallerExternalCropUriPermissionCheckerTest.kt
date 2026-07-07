package com.dot.gallery.feature_node.data.externalcrop

import android.app.ComponentCaller
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.os.Process
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ComponentCallerExternalCropUriPermissionCheckerTest {

    @Test
    fun canWriteContentUri_returnsTrueWhenCallerHasWriteGrant() {
        val uri = Uri.parse("content://caller/output")
        val fixture = permissionChecker(
            permissionResult = PackageManager.PERMISSION_GRANTED,
        )

        assertTrue(
            fixture.checker.canWriteContentUri(
                uri = uri,
                caller = fixture.caller,
            ),
        )
    }

    @Test
    fun canWriteContentUri_returnsFalseWhenPlatformThrowsIllegalArgumentException() {
        val uri = Uri.parse("content://caller/output")
        val fixture = permissionChecker(
            permissionException = IllegalArgumentException("not launch tracked"),
        )

        assertFalse(
            fixture.checker.canWriteContentUri(
                uri = uri,
                caller = fixture.caller,
            ),
        )
    }

    @Test
    fun canWriteContentUri_returnsFalseWhenPlatformThrowsSecurityException() {
        val uri = Uri.parse("content://caller/output")
        val fixture = permissionChecker(
            permissionException = SecurityException("not accessible"),
        )

        assertFalse(
            fixture.checker.canWriteContentUri(
                uri = uri,
                caller = fixture.caller,
            ),
        )
    }

    @Test
    fun canReadFileUri_acceptsSharedImageDirectories() {
        val checker = ComponentCallerExternalCropUriPermissionChecker(
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
        permissionResult: Int = PackageManager.PERMISSION_DENIED,
        permissionException: RuntimeException? = null,
    ): PermissionCheckerFixture {
        val caller = mockk<ComponentCaller>()
        every { caller.uid } returns Process.myUid() + 1
        if (permissionException == null) {
            every {
                caller.checkContentUriPermission(
                    any(),
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            } returns permissionResult
        } else {
            every {
                caller.checkContentUriPermission(
                    any(),
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            } throws permissionException
        }

        return PermissionCheckerFixture(
            checker = ComponentCallerExternalCropUriPermissionChecker(
                packageManager = mockk<PackageManager>(relaxed = true),
            ),
            caller = caller,
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
    )
}
