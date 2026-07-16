package com.dot.gallery.core.decoder.glide

import android.graphics.drawable.Drawable
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.ResourceDecoder
import com.bumptech.glide.load.engine.Resource
import java.io.InputStream

internal class GalleryMediaAnimatedImageDecoder(
    private val delegate: ResourceDecoder<InputStream, Drawable>,
) : ResourceDecoder<GalleryMediaData, Drawable> {

    override fun handles(source: GalleryMediaData, options: Options): Boolean {
        return source is PlatformImageData &&
                source.format == ImageFileFormat.ANIMATED_WEBP
    }

    override fun decode(
        source: GalleryMediaData,
        width: Int,
        height: Int,
        options: Options,
    ): Resource<Drawable>? {
        val imageData = source as? PlatformImageData ?: return null
        return delegate.decode(imageData.inputStream, width, height, options)
    }
}
