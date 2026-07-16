package com.dot.gallery.core.decoder.glide

import android.graphics.Bitmap
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.ResourceDecoder
import com.bumptech.glide.load.engine.Resource
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapResource
import com.dot.gallery.core.sandbox.SandboxedDecoderHolder
import kotlinx.coroutines.runBlocking

internal class SandboxedGalleryImageDecoder(
    private val bitmapPool: BitmapPool,
) : ResourceDecoder<SandboxedImageData, Bitmap> {
    override fun handles(source: SandboxedImageData, options: Options): Boolean {
        return true
    }

    override fun decode(
        source: SandboxedImageData,
        width: Int,
        height: Int,
        options: Options,
    ): Resource<Bitmap>? {
        val decoder = SandboxedDecoderHolder.decoder ?: return null
        val result = runBlocking {
            decoder.decode(
                inputStream = source.inputStream,
                mimeType = source.mimeType,
                targetWidth = width,
                targetHeight = height,
            )
        } ?: return null
        return BitmapResource.obtain(result.bitmap, bitmapPool)
    }
}
