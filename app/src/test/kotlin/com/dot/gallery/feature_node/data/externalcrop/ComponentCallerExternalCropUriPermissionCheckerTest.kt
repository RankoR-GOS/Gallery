package com.dot.gallery.feature_node.data.externalcrop

import android.app.ComponentCaller
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Environment
import android.os.Process
import android.provider.MediaStore
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
    fun canWriteContentUri_acceptsOutputOwnedByCaller() {
        val fixture = permissionChecker(resolvedType = "image/jpeg", owner = CALLER_PACKAGE)

        assertTrue(
            fixture.checker.canWriteContentUri(
                uri = OUTPUT_URI,
                caller = fixture.caller,
            ),
        )
    }

    @Test
    fun canWriteContentUri_acceptsOutputOwnedByUs() {
        val fixture = permissionChecker(resolvedType = "image/jpeg", owner = OUR_PACKAGE)

        assertTrue(
            fixture.checker.canWriteContentUri(
                uri = OUTPUT_URI,
                caller = fixture.caller,
            ),
        )
    }

    /**
     * The confused deputy guard: we hold write access to all of shared media, the caller does not.
     * Cropping into a row owned by someone else would let the caller overwrite media it cannot
     * touch itself, and our crop ui only ever shows the source, so the user cannot catch it.
     */
    @Test
    fun canWriteContentUri_rejectsOutputOwnedByThirdParty() {
        val fixture = permissionChecker(resolvedType = "image/jpeg", owner = "com.example.victim")

        assertFalse(
            fixture.checker.canWriteContentUri(
                uri = OUTPUT_URI,
                caller = fixture.caller,
            ),
        )
    }

    @Test
    fun canWriteContentUri_rejectsOutputWithoutRecordedOwner() {
        val fixture = permissionChecker(resolvedType = "image/jpeg", owner = null)

        assertFalse(
            fixture.checker.canWriteContentUri(
                uri = OUTPUT_URI,
                caller = fixture.caller,
            ),
        )
    }

    @Test
    fun canWriteContentUri_rejectsOutputWithNoRow() {
        val fixture = permissionChecker(
            resolvedType = "image/jpeg",
            owner = null,
            ownerRowPresent = false,
        )

        assertFalse(
            fixture.checker.canWriteContentUri(
                uri = OUTPUT_URI,
                caller = fixture.caller,
            ),
        )
    }

    /**
     * A caller sharing a uid with other packages owns everything any of them owns.
     */
    @Test
    fun canWriteContentUri_acceptsOutputOwnedByAnotherPackageInTheCallerUid() {
        val fixture = permissionChecker(
            resolvedType = "image/jpeg",
            owner = "com.example.sibling",
            callerPackages = arrayOf(CALLER_PACKAGE, "com.example.sibling"),
        )

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
    fun canWriteContentUri_rejectsForeignAuthorityWithoutGrant() {
        val fixture = permissionChecker(resolvedType = "image/jpeg")

        assertFalse(
            fixture.checker.canWriteContentUri(
                uri = FOREIGN_OUTPUT_URI,
                caller = fixture.caller,
            ),
        )
    }

    /**
     * AvatarPicker's shape: one uri from its own [androidx.core.content.FileProvider], passed as both
     * the intent data and [MediaStore.EXTRA_OUTPUT] with
     * [android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION]. That puts it in the launch grant set,
     * so the caller has proven it can write the output itself and we are not acting as its deputy.
     */
    @Test
    fun canWriteContentUri_acceptsNonMediaStoreOutputWithWriteGrant() {
        val fixture = permissionChecker(grantResult = PackageManager.PERMISSION_GRANTED)

        assertTrue(
            fixture.checker.canWriteContentUri(
                uri = AVATAR_PICKER_OUTPUT_URI,
                caller = fixture.caller,
            ),
        )
    }

    @Test
    fun canWriteContentUri_rejectsNonMediaStoreOutputWithoutWriteGrant() {
        val fixture = permissionChecker(grantResult = PackageManager.PERMISSION_DENIED)

        assertFalse(
            fixture.checker.canWriteContentUri(
                uri = AVATAR_PICKER_OUTPUT_URI,
                caller = fixture.caller,
            ),
        )
    }

    /**
     * The extra-only shape: the caller named an output uri it never granted, so the platform throws
     * rather than answering. Rejecting is the correct answer — the caller proved nothing.
     */
    @Test
    fun canWriteContentUri_rejectsNonMediaStoreOutputOutsideLaunchGrantSet() {
        val fixture = permissionChecker(
            grantException = IllegalArgumentException("uri not in the launch grant set"),
        )

        assertFalse(
            fixture.checker.canWriteContentUri(
                uri = AVATAR_PICKER_OUTPUT_URI,
                caller = fixture.caller,
            ),
        )
    }

    @Test
    fun canWriteContentUri_rejectsNonMediaStoreOutputWhenGrantCheckThrowsSecurityException() {
        val fixture = permissionChecker(
            grantException = SecurityException("not accessible"),
        )

        assertFalse(
            fixture.checker.canWriteContentUri(
                uri = AVATAR_PICKER_OUTPUT_URI,
                caller = fixture.caller,
            ),
        )
    }

    /**
     * A write grant on a media uri must not buy access to a row the caller does not own. MediaStore
     * ownership is the stricter rule and stays the only one consulted there.
     */
    @Test
    fun canWriteContentUri_rejectsThirdPartyMediaStoreOwnerEvenWithWriteGrant() {
        val fixture = permissionChecker(
            resolvedType = "image/jpeg",
            owner = "com.example.victim",
            grantResult = PackageManager.PERMISSION_GRANTED,
        )

        assertFalse(
            fixture.checker.canWriteContentUri(
                uri = OUTPUT_URI,
                caller = fixture.caller,
            ),
        )
    }

    /**
     * Writing the output is not reading it: a caller holding only a read grant must not be able to
     * aim our writes.
     */
    @Test
    fun canWriteContentUri_checksTheWriteFlagNotTheReadFlag() {
        val fixture = permissionChecker(grantResult = PackageManager.PERMISSION_GRANTED)

        fixture.checker.canWriteContentUri(
            uri = AVATAR_PICKER_OUTPUT_URI,
            caller = fixture.caller,
        )

        verify(exactly = 1) {
            fixture.caller.checkContentUriPermission(
                AVATAR_PICKER_OUTPUT_URI,
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
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
     * A legacy MediaStore output arrives in [MediaStore.EXTRA_OUTPUT] alone, so it is outside the
     * launch grant set and [ComponentCaller.checkContentUriPermission] would throw — routing media
     * uris through it would reject every legacy `ACTION_CROP` request. Ownership answers there
     * instead; pin that the launch grant api is never consulted for media.
     */
    @Test
    fun canWriteContentUri_neverUsesLaunchGrantApiForMediaStoreOutput() {
        val fixture = permissionChecker(resolvedType = "image/jpeg")

        fixture.checker.canWriteContentUri(
            uri = OUTPUT_URI,
            caller = fixture.caller,
        )

        verify(exactly = 0) { fixture.caller.checkContentUriPermission(any(), any()) }
    }

    /**
     * `"w"` truncates the target on some providers, so probing writability by opening the output
     * would destroy it before the user confirms the crop. Neither branch may do it.
     */
    @Test
    fun canWriteContentUri_neverOpensTheOutput() {
        val fixture = permissionChecker(
            resolvedType = "image/jpeg",
            grantResult = PackageManager.PERMISSION_GRANTED,
        )

        fixture.checker.canWriteContentUri(
            uri = OUTPUT_URI,
            caller = fixture.caller,
        )
        fixture.checker.canWriteContentUri(
            uri = AVATAR_PICKER_OUTPUT_URI,
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
        owner: String? = CALLER_PACKAGE,
        ownerRowPresent: Boolean = true,
        callerPackages: Array<String> = arrayOf(CALLER_PACKAGE),
        grantResult: Int = PackageManager.PERMISSION_DENIED,
        grantException: RuntimeException? = null,
    ): PermissionCheckerFixture {
        val callerUid = Process.myUid() + 1
        val caller = mockk<ComponentCaller>()
        every { caller.uid } returns callerUid
        every { caller.getPackage() } returns callerPackages.firstOrNull()

        val grantCheck = every { caller.checkContentUriPermission(any(), any()) }
        if (grantException == null) {
            grantCheck returns grantResult
        } else {
            grantCheck throws grantException
        }

        val contentResolver = mockk<ContentResolver>(relaxed = true)
        val typeCheck = every { contentResolver.getType(any()) }
        if (resolveException == null) {
            typeCheck returns resolvedType
        } else {
            typeCheck throws resolveException
        }
        every { contentResolver.query(any(), any(), any(), any(), any()) } returns
                ownerCursor(owner = owner, rowPresent = ownerRowPresent)

        val context = mockk<Context>()
        every { context.contentResolver } returns contentResolver
        every { context.packageName } returns OUR_PACKAGE

        val packageManager = mockk<PackageManager>(relaxed = true)
        every { packageManager.getPackagesForUid(callerUid) } returns callerPackages

        return PermissionCheckerFixture(
            checker = ComponentCallerExternalCropUriPermissionChecker(
                context = context,
                packageManager = packageManager,
            ),
            caller = caller,
            contentResolver = contentResolver,
        )
    }

    private fun ownerCursor(owner: String?, rowPresent: Boolean): Cursor {
        return MatrixCursor(arrayOf(MediaStore.MediaColumns.OWNER_PACKAGE_NAME)).apply {
            if (rowPresent) addRow(arrayOf(owner))
        }
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
        const val CALLER_PACKAGE = "com.example.caller"
        const val OUR_PACKAGE = "com.dot.gallery"
        val OUTPUT_URI: Uri = Uri.parse("content://media/external/images/media/42")
        val AVATAR_PICKER_OUTPUT_URI: Uri =
            Uri.parse("content://com.android.avatarpicker.tempprovider/output.png")
        val FOREIGN_OUTPUT_URI: Uri = Uri.parse("content://com.example.provider/output")
    }
}
