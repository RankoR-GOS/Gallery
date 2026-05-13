/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.core.decoder.glide

import android.content.Context
import android.graphics.Bitmap
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.ResourceDecoder
import com.bumptech.glide.load.engine.Resource
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapResource
import com.dot.gallery.core.sandbox.SandboxedDecoderHolder
import kotlinx.coroutines.runBlocking
import java.io.InputStream

/**
 * Glide JPEG XL decoder backed by the isolated decoder service.
 */
class SandboxedJxlBitmapDecoder(
    private val context: Context,
    private val bitmapPool: BitmapPool,
) : ResourceDecoder<InputStream, Bitmap> {

    override fun handles(source: InputStream, options: Options): Boolean {
        if (!SandboxedDecoderHolder.isEnabled(context)) {
            return false
        }
        source.mark(12)
        val header = ByteArray(12)
        val read = source.read(header)
        source.reset()
        if (read < 2) {
            return false
        }
        if (header[0] == 0xFF.toByte() && header[1] == 0x0A.toByte()) {
            return true
        }
        return read >= 12 &&
                header[0] == 0x00.toByte() &&
                header[1] == 0x00.toByte() &&
                header[2] == 0x00.toByte() &&
                header[3] == 0x0C.toByte() &&
                header[4] == 0x4A.toByte() &&
                header[5] == 0x58.toByte() &&
                header[6] == 0x4C.toByte() &&
                header[7] == 0x20.toByte() &&
                header[8] == 0x0D.toByte() &&
                header[9] == 0x0A.toByte() &&
                header[10] == 0x87.toByte() &&
                header[11] == 0x0A.toByte()
    }

    override fun decode(
        source: InputStream,
        width: Int,
        height: Int,
        options: Options,
    ): Resource<Bitmap>? {
        val decoder = SandboxedDecoderHolder.decoder ?: return null
        val bytes = source.readBytes()
        val bitmap = runBlocking {
            decoder.decode(
                encodedBytes = bytes,
                mimeType = "image/jxl",
                targetWidth = width,
                targetHeight = height,
            )
        } ?: return null
        return BitmapResource.obtain(bitmap, bitmapPool)
    }
}
