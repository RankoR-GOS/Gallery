package com.dot.gallery.core.decoder.glide

import android.graphics.Bitmap
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.ResourceDecoder
import com.bumptech.glide.load.engine.Resource
import java.io.InputStream

internal class GalleryMediaBitmapDecoder(
    private val platformImageDecoder: ResourceDecoder<InputStream, Bitmap>,
    private val sandboxedImageDecoder: SandboxedGalleryImageDecoder,
    private val videoDecoder: VerifiedVideoDecoder,
) : ResourceDecoder<GalleryMediaData, Bitmap> {
    override fun handles(source: GalleryMediaData, options: Options): Boolean {
        return when (source) {
            is PlatformImageData -> {
                platformImageDecoder.handles(source.inputStream, options)
            }

            is SandboxedImageData,
            is VerifiedVideoData -> true
        }
    }

    override fun decode(
        source: GalleryMediaData,
        width: Int,
        height: Int,
        options: Options,
    ): Resource<Bitmap>? {
        return when (source) {
            is PlatformImageData -> {
                platformImageDecoder.decode(
                    source.inputStream,
                    width,
                    height,
                    options,
                )
            }

            is SandboxedImageData -> {
                sandboxedImageDecoder.decode(source, width, height, options)
            }

            is VerifiedVideoData -> {
                videoDecoder.decode(source, width, height, options)
            }
        }
    }
}
