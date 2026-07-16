package com.dot.gallery.core.decoder.glide

import com.bumptech.glide.load.Options
import com.bumptech.glide.load.ResourceDecoder
import com.bumptech.glide.load.engine.Resource
import com.bumptech.glide.load.resource.gif.GifDrawable
import java.io.InputStream

internal class GalleryMediaGifDecoder(
    private val delegate: ResourceDecoder<InputStream, GifDrawable>,
) : ResourceDecoder<GalleryMediaData, GifDrawable> {

    override fun handles(source: GalleryMediaData, options: Options): Boolean {
        return source is PlatformImageData &&
            source.format == ImageFileFormat.GIF &&
            delegate.handles(source.inputStream, options)
    }

    override fun decode(
        source: GalleryMediaData,
        width: Int,
        height: Int,
        options: Options,
    ): Resource<GifDrawable>? {
        val imageData = source as? PlatformImageData ?: return null
        return delegate.decode(imageData.inputStream, width, height, options)
    }
}
