package com.dot.gallery.core.decoder.glide

import android.content.Context
import android.os.ParcelFileDescriptor
import com.bumptech.glide.Priority
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.data.DataFetcher
import com.bumptech.glide.load.model.ModelLoader
import com.bumptech.glide.load.model.ModelLoaderFactory
import com.bumptech.glide.load.model.MultiModelLoaderFactory
import com.bumptech.glide.signature.ObjectKey
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream

internal class GalleryMediaModelLoader(
    private val mediaSource: GalleryMediaSource,
) : ModelLoader<GalleryMediaModel, GalleryMediaData> {

    override fun handles(model: GalleryMediaModel): Boolean {
        return true
    }

    override fun buildLoadData(
        model: GalleryMediaModel,
        width: Int,
        height: Int,
        options: Options,
    ): ModelLoader.LoadData<GalleryMediaData> {
        return ModelLoader.LoadData(
            ObjectKey(model),
            GalleryMediaFetcher(
                mediaSource = mediaSource,
                model = model,
            ),
        )
    }

    internal class Factory(private val context: Context) :
        ModelLoaderFactory<GalleryMediaModel, GalleryMediaData> {
        override fun build(
            multiFactory: MultiModelLoaderFactory,
        ): ModelLoader<GalleryMediaModel, GalleryMediaData> {
            return GalleryMediaModelLoader(
                mediaSource = ContentResolverGalleryMediaSource(
                    contentResolver = context.contentResolver,
                ),
            )
        }

        override fun teardown() {
        }
    }
}

private class GalleryMediaFetcher(
    private val mediaSource: GalleryMediaSource,
    private val model: GalleryMediaModel,
) : DataFetcher<GalleryMediaData> {
    private var mediaData: GalleryMediaData? = null

    override fun loadData(
        priority: Priority,
        callback: DataFetcher.DataCallback<in GalleryMediaData>,
    ) {
        val loadedData = try {
            val mimeType = model.declaredMimeType ?: normalizeMimeType(
                mimeType = mediaSource.getMimeType(uri = model.uri),
            )
            when {
                mimeType?.startsWith("video/") == true -> openDeclaredVideo()
                else -> openImageStream()
            }
        } catch (failure: Exception) {
            callback.onLoadFailed(failure)
            return
        }

        mediaData = loadedData
        callback.onDataReady(loadedData)
    }

    private fun openImageStream(): GalleryMediaData {
        val source = mediaSource.openInputStream(uri = model.uri)
            ?: throw IOException("Unable to open media stream")
        val inputStream = BufferedInputStream(source, IMAGE_HEADER_BYTES)
        return try {
            inputStream.mark(IMAGE_HEADER_BYTES)
            val header = inputStream.readImageHeader()
            inputStream.reset()
            createImageData(
                inputStream = inputStream,
                header = header,
            )
        } catch (failure: Exception) {
            inputStream.close()
            throw failure
        }
    }

    private fun openDeclaredVideo(): GalleryMediaData {
        val descriptor = mediaSource.openFileDescriptor(uri = model.uri)
            ?: throw IOException("Unable to open media descriptor")
        return try {
            val header = descriptor.fileDescriptor.preadImageHeader()
            val imageFormat = classifyImageHeader(header = header, length = header.size)
            when (imageFormat) {
                null -> VerifiedVideoData(descriptor = descriptor)
                else -> createImageData(
                    inputStream = ParcelFileDescriptor.AutoCloseInputStream(descriptor),
                    format = imageFormat,
                )
            }
        } catch (failure: Exception) {
            descriptor.close()
            throw failure
        }
    }

    private fun createImageData(
        inputStream: BufferedInputStream,
        header: ByteArray,
    ): GalleryMediaData {
        val format = classifyImageHeader(header = header, length = header.size)
            ?: throw IOException("Image format is not verified")
        return createImageData(inputStream = inputStream, format = format)
    }

    private fun createImageData(
        inputStream: InputStream,
        format: ImageFileFormat,
    ): GalleryMediaData {
        val sandboxMimeType = format.sandboxMimeType
        return when {
            sandboxMimeType != null -> SandboxedImageData(
                inputStream = inputStream,
                mimeType = sandboxMimeType,
            )

            else -> PlatformImageData(
                inputStream = inputStream,
                format = format,
            )
        }
    }

    override fun cleanup() {
        mediaData?.close()
        mediaData = null
    }

    override fun cancel() {
        cleanup()
    }

    override fun getDataClass(): Class<GalleryMediaData> {
        return GalleryMediaData::class.java
    }

    override fun getDataSource(): DataSource {
        return DataSource.LOCAL
    }
}
