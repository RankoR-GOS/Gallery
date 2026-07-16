package com.dot.gallery.core.decoder.glide

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.InputStream

internal interface GalleryMediaSource {
    fun getMimeType(uri: Uri): String?

    fun openInputStream(uri: Uri): InputStream?

    fun openFileDescriptor(uri: Uri): ParcelFileDescriptor?
}

internal class ContentResolverGalleryMediaSource(
    private val contentResolver: ContentResolver,
) : GalleryMediaSource {
    override fun getMimeType(uri: Uri): String? {
        return contentResolver.getType(uri)
    }

    override fun openInputStream(uri: Uri): InputStream? {
        return contentResolver.openInputStream(uri)
    }

    override fun openFileDescriptor(uri: Uri): ParcelFileDescriptor? {
        return contentResolver.openFileDescriptor(uri, "r")
    }
}
