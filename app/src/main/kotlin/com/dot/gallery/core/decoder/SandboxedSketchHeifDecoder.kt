/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.core.decoder

import com.dot.gallery.core.sandbox.SandboxedDecoderHolder
import com.github.panpf.sketch.ComponentRegistry
import com.github.panpf.sketch.asImage
import com.github.panpf.sketch.decode.DecodeException
import com.github.panpf.sketch.decode.Decoder
import com.github.panpf.sketch.decode.ImageInfo
import com.github.panpf.sketch.decode.internal.createScaledTransformed
import com.github.panpf.sketch.fetch.FetchResult
import com.github.panpf.sketch.request.ImageData
import com.github.panpf.sketch.request.RequestContext
import com.github.panpf.sketch.request.get
import com.github.panpf.sketch.source.DataSource
import com.github.panpf.sketch.util.Size
import okio.buffer

fun ComponentRegistry.Builder.supportSandboxedHeifDecoder(): ComponentRegistry.Builder {
    return apply {
        add(SandboxedSketchHeifDecoder.Factory())
    }
}

/**
 * Sketch HEIF/AVIF decoder backed by [com.dot.gallery.core.sandbox.IsolatedDecoderService].
 */
class SandboxedSketchHeifDecoder(
    private val requestContext: RequestContext,
    private val dataSource: DataSource,
    private val mimeType: String,
) : Decoder {

    class Factory : Decoder.Factory {

        override val key: String
            get() = "SandboxedHeifDecoder"

        override val sortWeight: Int = 0

        override fun create(requestContext: RequestContext, fetchResult: FetchResult): Decoder? {
            val context = requestContext.sketch.context
            if (!SandboxedDecoderHolder.isEnabled(context)) {
                return null
            }
            val mimeType = requestContext.request.extras?.get("realMimeType") as String? ?: return null
            return if (SketchHeifDecoder.Factory.HEIF_MIMETYPES.any { mimeType.contains(it) }) {
                SandboxedSketchHeifDecoder(
                    requestContext = requestContext,
                    dataSource = fetchResult.dataSource,
                    mimeType = fetchResult.mimeType ?: mimeType,
                )
            } else {
                null
            }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            return other is Factory
        }

        override fun hashCode(): Int {
            return this@Factory::class.hashCode()
        }

        override fun toString(): String {
            return key
        }
    }

    override suspend fun decode(): ImageData {
        val decoder = SandboxedDecoderHolder.decoder
            ?: throw DecodeException("Sandboxed image decoder is not initialized")
        val requestedSize = requestContext.size.takeUnless { size -> size == Size.Origin }
        val decodedImage = dataSource.openSource().buffer().use { source ->
            decoder.decode(
                inputStream = source.inputStream(),
                mimeType = mimeType,
                targetWidth = requestedSize?.width ?: 0,
                targetHeight = requestedSize?.height ?: 0,
            )
        } ?: throw DecodeException("Failed to decode HEIF/AVIF in sandbox")
        val originalSize = Size(
            width = decodedImage.originalSize.width,
            height = decodedImage.originalSize.height,
        )
        val targetSize = Size(
            width = decodedImage.bitmap.width,
            height = decodedImage.bitmap.height,
        )

        val imageInfo = ImageInfo(
            width = targetSize.width,
            height = targetSize.height,
            mimeType = mimeType,
        )
        return ImageData(
            image = decodedImage.bitmap.asImage(),
            imageInfo = imageInfo,
            dataFrom = dataSource.dataFrom,
            resize = requestContext.computeResize(imageInfo.size),
            transformeds = transformedFor(
                originalSize = originalSize,
                targetSize = targetSize,
            ),
            extras = null,
        )
    }

    override suspend fun getImageInfo(): ImageInfo {
        val decoder = SandboxedDecoderHolder.decoder
            ?: throw DecodeException("Sandboxed image decoder is not initialized")
        val originalSize = dataSource.openSource().buffer().use { source ->
            decoder.getSize(inputStream = source.inputStream(), mimeType = mimeType)
        }
            ?: throw DecodeException("Failed to read HEIF/AVIF size in sandbox")
        return ImageInfo(
            width = originalSize.width,
            height = originalSize.height,
            mimeType = mimeType,
        )
    }
}

internal fun transformedFor(originalSize: Size, targetSize: Size): List<String>? {
    if (originalSize == targetSize) {
        return null
    }
    val scale = targetSize.width.toFloat() / originalSize.width.toFloat()
    return listOf(createScaledTransformed(scale))
}
