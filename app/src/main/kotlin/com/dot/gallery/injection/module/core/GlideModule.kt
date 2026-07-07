package com.dot.gallery.injection.module.core

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.bumptech.glide.Glide
import com.bumptech.glide.Registry
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.module.AppGlideModule
import com.dot.gallery.core.decoder.glide.MimeInputStream
import com.dot.gallery.core.decoder.glide.MimeInputStreamModelLoader
import com.dot.gallery.core.decoder.glide.SandboxedHeifBitmapDecoder
import com.dot.gallery.core.decoder.glide.SandboxedHeifMimeDecoder
import com.dot.gallery.core.decoder.glide.SandboxedJxlBitmapDecoder
import java.io.InputStream

@GlideModule
class GlideModule: AppGlideModule() {

    override fun registerComponents(context: Context, glide: Glide, registry: Registry) {
        val pool: BitmapPool = glide.bitmapPool

        registry.prepend(
            Uri::class.java,
            MimeInputStream::class.java,
            MimeInputStreamModelLoader.Factory(context = context)
        )
        registry.prepend(
            MimeInputStream::class.java,
            Bitmap::class.java,
            SandboxedHeifMimeDecoder(context = context, bitmapPool = pool)
        )
        registry.prepend(
            InputStream::class.java,
            Bitmap::class.java,
            SandboxedHeifBitmapDecoder(context = context, bitmapPool = pool)
        )
        registry.prepend(
            InputStream::class.java,
            Bitmap::class.java,
            SandboxedJxlBitmapDecoder(context = context, bitmapPool = pool)
        )
    }

    // Disable manifest parsing for speed
    override fun isManifestParsingEnabled(): Boolean = false

}
