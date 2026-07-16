package com.dot.gallery.feature_node.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.dot.gallery.core.util.MAX_ENCODED_MEDIA_BYTES
import com.dot.gallery.core.util.SizeLimitedInputStream
import com.dot.gallery.injection.qualifier.IoDispatcher
import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.xmp.XmpDirectory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject

interface MotionPhotoRepository {

    suspend fun parseInfo(uri: Uri): MotionPhotoInfo?

    suspend fun extractVideo(uri: Uri, info: MotionPhotoInfo): File?

    suspend fun extractFrames(file: File, frameCount: Int): List<Bitmap>
}

internal class MotionPhotoRepositoryImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : MotionPhotoRepository {

    private val contentResolver = context.contentResolver

    override suspend fun parseInfo(uri: Uri): MotionPhotoInfo? {
        return withContext(ioDispatcher) {
            try {
                parseXmpInfo(uri = uri) ?: findSamsungMarkerOffset(uri = uri)?.let { offset ->
                    MotionPhotoInfo(videoOffset = offset)
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                Log.w(TAG, "Failed to parse motion photo metadata for $uri", exception)
                null
            }
        }
    }

    override suspend fun extractVideo(uri: Uri, info: MotionPhotoInfo): File? {
        return withContext(ioDispatcher) {
            try {
                val sourceSize = getSourceSize(uri = uri) ?: return@withContext null
                val requestedFile = extractAtOffset(
                    uri = uri,
                    sourceSize = sourceSize,
                    videoOffset = info.videoOffset,
                )
                when {
                    requestedFile != null -> requestedFile
                    else -> {
                        val samsungOffset = findSamsungMarkerOffset(uri = uri)
                        samsungOffset
                            ?.takeIf { offset -> offset != info.videoOffset }
                            ?.let { offset ->
                                extractAtOffset(
                                    uri = uri,
                                    sourceSize = sourceSize,
                                    videoOffset = offset,
                                )
                            }
                    }
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                Log.w(TAG, "Failed to extract motion photo video for $uri", exception)
                null
            }
        }
    }

    override suspend fun extractFrames(file: File, frameCount: Int): List<Bitmap> {
        return withContext(ioDispatcher) {
            if (frameCount <= 0) {
                return@withContext emptyList()
            }

            val frames = mutableListOf<Bitmap>()
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                val durationMicroseconds = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION,
                )?.toLongOrNull()?.times(MICROSECONDS_PER_MILLISECOND) ?: 0L
                if (durationMicroseconds <= 0L) {
                    return@withContext emptyList()
                }

                val intervalMicroseconds = durationMicroseconds / frameCount
                repeat(frameCount) { frameIndex ->
                    currentCoroutineContext().ensureActive()
                    val timestamp = frameIndex * intervalMicroseconds + intervalMicroseconds / 2L
                    retriever.getFrameAtTime(
                        timestamp,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    )?.let(frames::add)
                }
                frames
            } catch (exception: CancellationException) {
                frames.forEach(Bitmap::recycle)
                throw exception
            } catch (exception: Exception) {
                Log.w(TAG, "Failed to extract motion photo frames from $file", exception)
                frames
            } finally {
                runCatching(retriever::release)
            }
        }
    }

    private fun parseXmpInfo(uri: Uri): MotionPhotoInfo? {
        val properties: Map<String, String> = contentResolver.openInputStream(uri)?.use { inputStream ->
            val limitedInput = SizeLimitedInputStream(
                inputStream = inputStream,
                maximumBytes = MAX_ENCODED_MEDIA_BYTES,
            )
            val metadata = ImageMetadataReader.readMetadata(limitedInput)
            val properties = mutableMapOf<String, String>()
            val directories = metadata.getDirectoriesOfType(XmpDirectory::class.java)
            for (directory in directories) {
                directory.xmpProperties.forEach { (key, value) ->
                    properties[key] = value
                }
            }
            properties
        } ?: return null

        val isMotionPhoto = properties[KEY_MOTION_PHOTO] == ENABLED_VALUE ||
                properties[KEY_MICRO_VIDEO] == ENABLED_VALUE
        if (!isMotionPhoto) {
            return null
        }

        val videoOffset = explicitMotionPhotoOffset(properties = properties)
            ?: containerMotionPhotoOffset(properties = properties)
            ?: positiveLong(properties[KEY_MICRO_VIDEO_OFFSET])
            ?: return null
        val presentationTimestamp = properties[KEY_MOTION_PHOTO_TIMESTAMP]
            ?.toLongOrNull()
            ?: properties[KEY_MICRO_VIDEO_TIMESTAMP]?.toLongOrNull()
            ?: -1L
        return MotionPhotoInfo(
            videoOffset = videoOffset,
            presentationTimestampUs = presentationTimestamp,
        )
    }

    private fun explicitMotionPhotoOffset(properties: Map<String, String>): Long? {
        return positiveLong(properties[KEY_MOTION_PHOTO_OFFSET])
    }

    private fun containerMotionPhotoOffset(properties: Map<String, String>): Long? {
        val semanticEntry = properties.entries.firstOrNull { (key, value) ->
            key.endsWith(ITEM_SEMANTIC_SUFFIX) && value == MOTION_PHOTO_SEMANTIC
        } ?: return null
        val prefix = semanticEntry.key.removeSuffix(ITEM_SEMANTIC_SUFFIX)
        val length = positiveLong(properties["${prefix}${ITEM_LENGTH_SUFFIX}"]) ?: return null
        val padding = properties["${prefix}${ITEM_PADDING_SUFFIX}"]?.toLongOrNull() ?: 0L
        return (length + padding).takeIf { offset -> offset > 0L }
    }

    private fun positiveLong(value: String?): Long? {
        return value?.toLongOrNull()?.takeIf { number -> number > 0L }
    }

    private suspend fun findSamsungMarkerOffset(uri: Uri): Long? {
        return contentResolver.openInputStream(uri)?.use { inputStream ->
            val limitedInput = SizeLimitedInputStream(
                inputStream = inputStream,
                maximumBytes = MAX_ENCODED_MEDIA_BYTES,
            )
            findLastMotionPhotoMarkerOffset(inputStream = limitedInput)
        }
    }

    private suspend fun getSourceSize(uri: Uri): Long? {
        val descriptorLength = contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
            descriptor.length.takeIf { length -> length >= 0L }
        }
        if (descriptorLength != null) {
            return descriptorLength.takeIf { length ->
                length in 1L..MAX_ENCODED_MEDIA_BYTES
            }
        }

        return contentResolver.openInputStream(uri)?.use { inputStream ->
            val limitedInput = SizeLimitedInputStream(
                inputStream = inputStream,
                maximumBytes = MAX_ENCODED_MEDIA_BYTES,
            )
            countBytes(inputStream = limitedInput)
        }
    }

    private suspend fun countBytes(inputStream: InputStream): Long {
        val buffer = ByteArray(STREAM_BUFFER_BYTES)
        var totalBytes = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val readBytes = inputStream.read(buffer)
            if (readBytes == -1) {
                return totalBytes
            }
            totalBytes += readBytes.toLong()
        }
    }

    private suspend fun extractAtOffset(
        uri: Uri,
        sourceSize: Long,
        videoOffset: Long,
    ): File? {
        if (videoOffset !in MINIMUM_MP4_BYTES..sourceSize) {
            return null
        }
        val videoStart = sourceSize - videoOffset
        val temporaryFile = File.createTempFile("motion_photo_", ".mp4", context.cacheDir)
        val extracted = try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                val limitedInput = SizeLimitedInputStream(
                    inputStream = inputStream,
                    maximumBytes = MAX_ENCODED_MEDIA_BYTES,
                )
                skipExactly(inputStream = limitedInput, byteCount = videoStart)
                temporaryFile.outputStream().buffered().use { outputStream ->
                    copyVerifiedMp4(inputStream = limitedInput, outputStream = outputStream)
                }
            } == true
        } catch (exception: Exception) {
            temporaryFile.delete()
            throw exception
        }
        return temporaryFile.takeIf { extracted }.also { result ->
            if (result == null) {
                temporaryFile.delete()
            }
        }
    }

    private suspend fun skipExactly(inputStream: InputStream, byteCount: Long) {
        val buffer = ByteArray(STREAM_BUFFER_BYTES)
        var remainingBytes = byteCount
        while (remainingBytes > 0L) {
            currentCoroutineContext().ensureActive()
            val readBytes = inputStream.read(
                buffer,
                0,
                minOf(buffer.size.toLong(), remainingBytes).toInt(),
            )
            if (readBytes == -1) {
                throw IOException("Motion photo ended before the embedded video")
            }
            remainingBytes -= readBytes.toLong()
        }
    }

    private suspend fun copyVerifiedMp4(
        inputStream: InputStream,
        outputStream: OutputStream,
    ): Boolean {
        val header = ByteArray(MP4_HEADER_BYTES)
        var headerBytes = 0
        while (headerBytes < header.size) {
            val readBytes = inputStream.read(header, headerBytes, header.size - headerBytes)
            if (readBytes == -1) {
                return false
            }
            headerBytes += readBytes
        }
        if (!header.copyOfRange(FTYP_START_INDEX, FTYP_END_INDEX).contentEquals(FTYP_MARKER)) {
            return false
        }

        outputStream.write(header)
        val buffer = ByteArray(STREAM_BUFFER_BYTES)
        while (true) {
            currentCoroutineContext().ensureActive()
            val readBytes = inputStream.read(buffer)
            if (readBytes == -1) {
                break
            }
            outputStream.write(buffer, 0, readBytes)
        }
        outputStream.flush()
        return true
    }

    companion object {
        private const val TAG = "MotionPhotoRepository"
        private const val ENABLED_VALUE = "1"
        private const val FTYP_END_INDEX = 8
        private const val FTYP_START_INDEX = 4
        private const val ITEM_LENGTH_SUFFIX = "/Item:Length"
        private const val ITEM_PADDING_SUFFIX = "/Item:Padding"
        private const val ITEM_SEMANTIC_SUFFIX = "/Item:Semantic"
        private const val KEY_MICRO_VIDEO = "GCamera:MicroVideo"
        private const val KEY_MICRO_VIDEO_OFFSET = "GCamera:MicroVideoOffset"
        private const val KEY_MICRO_VIDEO_TIMESTAMP = "GCamera:MicroVideoPresentationTimestampUs"
        private const val KEY_MOTION_PHOTO = "GCamera:MotionPhoto"
        private const val KEY_MOTION_PHOTO_OFFSET = "GCamera:MotionPhotoVideoOffset"
        private const val KEY_MOTION_PHOTO_TIMESTAMP =
            "GCamera:MotionPhotoPresentationTimestampUs"
        private const val MICROSECONDS_PER_MILLISECOND = 1_000L
        private const val MINIMUM_MP4_BYTES = 8L
        private const val MOTION_PHOTO_SEMANTIC = "MotionPhoto"
        private const val MP4_HEADER_BYTES = 8
        private const val STREAM_BUFFER_BYTES = 64 * 1024

        private val FTYP_MARKER = "ftyp".toByteArray(Charsets.US_ASCII)
    }
}
