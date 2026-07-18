package com.dot.gallery.core.sandbox

import android.content.ContentProvider
import android.content.ContentValues
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import androidx.core.graphics.createBitmap
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException

class UnknownSizeVideoProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        return true
    }

    override fun getType(uri: Uri): String {
        return VIDEO_MIME_TYPE
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        return openPipe(
            uri = uri,
            mimeType = VIDEO_MIME_TYPE,
            bytes = MP4_HEADER_BYTES,
        )
    }

    override fun openTypedAssetFile(
        uri: Uri,
        mimeTypeFilter: String,
        opts: Bundle?,
        signal: CancellationSignal?,
    ): AssetFileDescriptor {
        val descriptor = openPipe(
            uri = uri,
            mimeType = IMAGE_MIME_TYPE,
            bytes = createThumbnailBytes(),
        )
        return AssetFileDescriptor(
            descriptor,
            0L,
            AssetFileDescriptor.UNKNOWN_LENGTH,
        )
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        return null
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        return null
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        return 0
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int {
        return 0
    }

    private fun openPipe(uri: Uri, mimeType: String, bytes: ByteArray): ParcelFileDescriptor {
        return openPipeHelper(uri, mimeType, null, bytes) { output, _, _, _, pipeBytes ->
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(output).use { stream ->
                    stream.write(pipeBytes)
                }
            } catch (_: IOException) {
                // The reader may close the pipe before consuming the complete fixture.
            }
        }
    }

    private fun createThumbnailBytes(): ByteArray {
        val bitmap = createBitmap(
            width = THUMBNAIL_SIZE,
            height = THUMBNAIL_SIZE,
            config = Bitmap.Config.ARGB_8888,
        )
        return try {
            bitmap.eraseColor(Color.CYAN)
            ByteArrayOutputStream().use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    throw FileNotFoundException("Unable to create test thumbnail")
                }
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    companion object {
        private const val IMAGE_MIME_TYPE = "image/png"
        private const val THUMBNAIL_SIZE = 224
        private const val VIDEO_MIME_TYPE = "video/mp4"
        private val MP4_HEADER_BYTES = byteArrayOf(
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
