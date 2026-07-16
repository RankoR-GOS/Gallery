package com.dot.gallery.injection.module.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import com.bumptech.glide.Glide
import com.bumptech.glide.Registry
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.load.resource.bitmap.Downsampler
import com.bumptech.glide.load.resource.bitmap.StreamBitmapDecoder
import com.bumptech.glide.load.resource.drawable.AnimatedImageDecoder
import com.bumptech.glide.load.resource.gif.ByteBufferGifDecoder
import com.bumptech.glide.load.resource.gif.GifDrawable
import com.bumptech.glide.load.resource.gif.StreamGifDecoder
import com.bumptech.glide.module.AppGlideModule
import com.dot.gallery.core.decoder.glide.GalleryMediaAnimatedImageDecoder
import com.dot.gallery.core.decoder.glide.GalleryMediaBitmapDecoder
import com.dot.gallery.core.decoder.glide.GalleryMediaData
import com.dot.gallery.core.decoder.glide.GalleryMediaGifDecoder
import com.dot.gallery.core.decoder.glide.GalleryMediaModel
import com.dot.gallery.core.decoder.glide.GalleryMediaModelLoader
import com.dot.gallery.core.decoder.glide.SandboxedGalleryImageDecoder
import com.dot.gallery.core.decoder.glide.VerifiedVideoDecoder

@GlideModule
class GlideModule : AppGlideModule() {

    override fun registerComponents(context: Context, glide: Glide, registry: Registry) {
        val arrayPool = glide.arrayPool
        val bitmapPool = glide.bitmapPool
        val imageHeaderParsers = registry.imageHeaderParsers
        val platformImageDecoder = StreamBitmapDecoder(
            Downsampler(
                imageHeaderParsers,
                context.resources.displayMetrics,
                bitmapPool,
                arrayPool,
            ),
            arrayPool,
        )
        val gifDecoder = StreamGifDecoder(
            imageHeaderParsers,
            ByteBufferGifDecoder(
                context,
                imageHeaderParsers,
                bitmapPool,
                arrayPool,
            ),
            arrayPool,
        )
        val animatedImageDecoder = AnimatedImageDecoder.streamDecoder(
            imageHeaderParsers,
            arrayPool,
        )

        registry.append(
            GalleryMediaModel::class.java,
            GalleryMediaData::class.java,
            GalleryMediaModelLoader.Factory(context = context),
        )
        registry.append(
            Registry.BUCKET_ANIMATION,
            GalleryMediaData::class.java,
            GifDrawable::class.java,
            GalleryMediaGifDecoder(delegate = gifDecoder),
        )
        registry.append(
            Registry.BUCKET_ANIMATION,
            GalleryMediaData::class.java,
            Drawable::class.java,
            GalleryMediaAnimatedImageDecoder(delegate = animatedImageDecoder),
        )
        registry.append(
            Registry.BUCKET_BITMAP,
            GalleryMediaData::class.java,
            Bitmap::class.java,
            GalleryMediaBitmapDecoder(
                platformImageDecoder = platformImageDecoder,
                sandboxedImageDecoder = SandboxedGalleryImageDecoder(bitmapPool = bitmapPool),
                videoDecoder = VerifiedVideoDecoder(bitmapPool = bitmapPool),
            ),
        )
    }

    override fun isManifestParsingEnabled(): Boolean {
        return false
    }
}
