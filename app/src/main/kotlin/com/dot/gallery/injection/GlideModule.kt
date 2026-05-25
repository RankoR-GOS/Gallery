package com.dot.gallery.injection

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.bumptech.glide.Glide
import com.bumptech.glide.Registry
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.module.AppGlideModule
import com.dot.gallery.core.decoder.glide.HeifMimeInputStreamDecoder
import com.dot.gallery.core.decoder.glide.JxlBitmapDecoder
import com.dot.gallery.core.decoder.glide.MimeInputStream
import com.dot.gallery.core.decoder.glide.MimeInputStreamModelLoader
import java.io.InputStream

@GlideModule
class GlideModule: AppGlideModule() {

    override fun registerComponents(context: Context, glide: Glide, registry: Registry) {
        val pool: BitmapPool = glide.bitmapPool

        registry.prepend(
            Uri::class.java,
            MimeInputStream::class.java,
            MimeInputStreamModelLoader.Factory(context)
        )
        registry.prepend(
            MimeInputStream::class.java,
            Bitmap::class.java,
            HeifMimeInputStreamDecoder(pool)
        )
        registry.prepend(
            InputStream::class.java,
            Bitmap::class.java,
            JxlBitmapDecoder(pool)
        )
    }

    // Disable manifest parsing for speed
    override fun isManifestParsingEnabled(): Boolean = false

}
