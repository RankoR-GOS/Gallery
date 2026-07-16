package com.dot.gallery.core.decoder.glide

import android.graphics.Bitmap
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.ResourceDecoder
import com.bumptech.glide.load.engine.Resource
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.VideoDecoder

internal class VerifiedVideoDecoder(
    bitmapPool: BitmapPool
) : ResourceDecoder<VerifiedVideoData, Bitmap> {
    private val delegate = VideoDecoder.parcel(bitmapPool)

    override fun handles(source: VerifiedVideoData, options: Options): Boolean {
        return true
    }

    override fun decode(
        source: VerifiedVideoData,
        width: Int,
        height: Int,
        options: Options,
    ): Resource<Bitmap>? {
        return delegate.decode(source.descriptor, width, height, options)
    }
}
