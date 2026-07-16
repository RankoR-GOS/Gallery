package com.dot.gallery.feature_node.data.repository

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.InputStream

private const val MOTION_PHOTO_BUFFER_BYTES = 64 * 1024
private val MOTION_PHOTO_SAMSUNG_MARKER = "MotionPhoto_Data".toByteArray(Charsets.US_ASCII)

internal suspend fun findLastMotionPhotoMarkerOffset(
    inputStream: InputStream,
    bufferSize: Int = MOTION_PHOTO_BUFFER_BYTES,
): Long? {
    require(bufferSize > 0) { "Buffer size must be positive" }
    val buffer = ByteArray(bufferSize)
    var matchedMarkerBytes = 0
    var totalBytes = 0L
    var lastMarkerEnd: Long? = null
    while (true) {
        currentCoroutineContext().ensureActive()
        val readBytes = inputStream.read(buffer)
        if (readBytes == -1) {
            break
        }
        for (index in 0 until readBytes) {
            val value = buffer[index]
            matchedMarkerBytes = when (value) {
                MOTION_PHOTO_SAMSUNG_MARKER[matchedMarkerBytes] -> matchedMarkerBytes + 1
                MOTION_PHOTO_SAMSUNG_MARKER[0] -> 1
                else -> 0
            }
            if (matchedMarkerBytes == MOTION_PHOTO_SAMSUNG_MARKER.size) {
                lastMarkerEnd = totalBytes + index + 1L
                matchedMarkerBytes = 0
            }
        }
        totalBytes += readBytes.toLong()
    }
    return lastMarkerEnd?.let { markerEnd -> totalBytes - markerEnd }
}
