/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.core.decoder

import com.dot.gallery.core.decoder.SketchJxlDecoder.Factory.Companion.JXL_MIMETYPE
import com.dot.gallery.core.sandbox.SandboxedDecoderHolder
import com.github.panpf.sketch.ComponentRegistry
import com.github.panpf.sketch.asImage
import com.github.panpf.sketch.decode.DecodeException
import com.github.panpf.sketch.decode.Decoder
import com.github.panpf.sketch.decode.ImageInfo
import com.github.panpf.sketch.fetch.FetchResult
import com.github.panpf.sketch.request.ImageData
import com.github.panpf.sketch.request.RequestContext
import com.github.panpf.sketch.request.get
import com.github.panpf.sketch.source.DataSource
import com.github.panpf.sketch.util.Size
import okio.buffer

fun ComponentRegistry.Builder.supportSandboxedJxlDecoder(): ComponentRegistry.Builder {
    return apply {
        add(SandboxedSketchJxlDecoder.Factory())
    }
}

/**
 * Sketch JPEG XL decoder backed by [com.dot.gallery.core.sandbox.IsolatedDecoderService].
 */
class SandboxedSketchJxlDecoder(
    private val requestContext: RequestContext,
    private val dataSource: DataSource,
) : Decoder {

    class Factory : Decoder.Factory {

        override val key: String
            get() = "SandboxedJxlDecoder"

        override val sortWeight: Int = 0

        override fun create(requestContext: RequestContext, fetchResult: FetchResult): Decoder? {
            val context = requestContext.sketch.context
            if (!SandboxedDecoderHolder.isEnabled(context)) {
                return null
            }
            val mimeType = requestContext.request.extras?.get("realMimeType") as String? ?: return null
            return if (mimeType.contains(JXL_MIMETYPE)) {
                SandboxedSketchJxlDecoder(
                    requestContext = requestContext,
                    dataSource = fetchResult.dataSource,
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
        val sourceData = readSourceBytes()
        val decoder = SandboxedDecoderHolder.decoder
            ?: throw DecodeException("Sandboxed image decoder is not initialized")
        val originalSize = decoder.getSize(encodedBytes = sourceData, mimeType = JXL_MIMETYPE)
            ?: throw DecodeException("Failed to read JPEG XL size in sandbox")
        val targetSize = resolveTargetSize(
            originalSize = Size(originalSize.width, originalSize.height),
            requestSize = requestContext.size,
        )
        val decodedImage = decoder.decode(
            encodedBytes = sourceData,
            mimeType = JXL_MIMETYPE,
            targetWidth = targetSize.width,
            targetHeight = targetSize.height,
        ) ?: throw DecodeException("Failed to decode JPEG XL in sandbox")

        val imageInfo = ImageInfo(
            width = targetSize.width,
            height = targetSize.height,
            mimeType = JXL_MIMETYPE,
        )
        return ImageData(
            image = decodedImage.asImage(),
            imageInfo = imageInfo,
            dataFrom = dataSource.dataFrom,
            resize = requestContext.computeResize(imageInfo.size),
            transformeds = transformedFor(
                originalSize = Size(originalSize.width, originalSize.height),
                targetSize = targetSize,
            ),
            extras = null,
        )
    }

    override suspend fun getImageInfo(): ImageInfo {
        val sourceData = readSourceBytes()
        val decoder = SandboxedDecoderHolder.decoder
            ?: throw DecodeException("Sandboxed image decoder is not initialized")
        val originalSize = decoder.getSize(encodedBytes = sourceData, mimeType = JXL_MIMETYPE)
            ?: throw DecodeException("Failed to read JPEG XL size in sandbox")
        val targetSize = resolveTargetSize(
            originalSize = Size(originalSize.width, originalSize.height),
            requestSize = requestContext.size,
        )
        return ImageInfo(
            width = targetSize.width,
            height = targetSize.height,
            mimeType = JXL_MIMETYPE,
        )
    }

    private fun readSourceBytes(): ByteArray {
        return dataSource.openSource().use { source ->
            source.buffer().readByteArray()
        }
    }
}
