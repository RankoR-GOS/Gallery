package com.dot.gallery.core.decoder.glide

import android.content.Context
import android.graphics.drawable.Drawable
import com.bumptech.glide.Glide
import com.dot.gallery.core.decoder.IMAGE_HEADER_BYTES
import com.dot.gallery.core.decoder.ImageFileFormat
import com.dot.gallery.core.decoder.classifyImageHeader
import com.dot.gallery.core.decoder.readImageHeader
import com.github.panpf.zoomimage.glide.GlideSubsamplingImageGenerator
import com.github.panpf.zoomimage.subsampling.ImageSource
import com.github.panpf.zoomimage.subsampling.SubsamplingImage
import com.github.panpf.zoomimage.subsampling.SubsamplingImageGenerateResult
import java.io.BufferedInputStream
import java.io.IOException
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Source
import okio.source

internal fun galleryMediaSubsamplingImageGenerators(): ImmutableList<GlideSubsamplingImageGenerator> {
    return persistentListOf(GalleryMediaSubsamplingImageGenerator())
}

internal class GalleryMediaSubsamplingImageGenerator : GlideSubsamplingImageGenerator {
    override suspend fun generateImage(
        context: Context,
        glide: Glide,
        model: Any,
        drawable: Drawable,
    ): SubsamplingImageGenerateResult? {
        if (model !is GalleryMediaModel) {
            return null
        }

        val imageSource = VerifiedGalleryMediaImageSource(
            mediaSource = ContentResolverGalleryMediaSource(
                contentResolver = context.contentResolver,
            ),
            model = model,
        )
        return withContext(context = Dispatchers.IO) {
            try {
                imageSource.verify()
                SubsamplingImageGenerateResult.Success(
                    subsamplingImage = SubsamplingImage(imageSource = imageSource),
                )
            } catch (failure: Exception) {
                SubsamplingImageGenerateResult.Error(
                    message = failure.message ?: "Image is not safe for subsampling",
                )
            }
        }
    }

    override fun equals(other: Any?): Boolean {
        return other != null && this::class == other::class
    }

    override fun hashCode(): Int {
        return this::class.hashCode()
    }

    override fun toString(): String {
        return "GalleryMediaSubsamplingImageGenerator"
    }
}

internal class VerifiedGalleryMediaImageSource(
    private val mediaSource: GalleryMediaSource,
    private val model: GalleryMediaModel,
) : ImageSource {
    override val key: String = "verified-gallery-media:${model}"

    fun verify() {
        openVerifiedStream().use { }
    }

    override fun openSource(): Source {
        return openVerifiedStream().source()
    }

    private fun openVerifiedStream(): BufferedInputStream {
        val source = mediaSource.openInputStream(uri = model.uri)
            ?: throw IOException("Unable to open image for subsampling")
        val inputStream = BufferedInputStream(source, IMAGE_HEADER_BYTES)
        return try {
            inputStream.mark(IMAGE_HEADER_BYTES)
            val header = inputStream.readImageHeader()
            val format = classifyImageHeader(header = header, length = header.size)
            if (format !in SUBSAMPLING_IMAGE_FORMATS) {
                throw IOException("Image format is not safe for subsampling")
            }
            inputStream.reset()
            inputStream
        } catch (failure: Exception) {
            inputStream.close()
            throw failure
        }
    }

    companion object {
        private val SUBSAMPLING_IMAGE_FORMATS = setOf(
            ImageFileFormat.JPEG,
            ImageFileFormat.PNG,
            ImageFileFormat.WEBP,
        )
    }
}
