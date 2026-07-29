package com.dot.gallery.feature_node.data.repository

import java.io.ByteArrayInputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MotionPhotoMarkerTest {

    @Test
    fun `marker spanning buffers is found without loading whole input`() {
        runTest {
            val prefix = "prefix-".toByteArray()
            val marker = "MotionPhoto_Data".toByteArray()
            val video = "video-payload".toByteArray()
            val input = prefix + marker + video

            val offset = findLastMotionPhotoMarkerOffset(
                inputStream = ByteArrayInputStream(input),
                bufferSize = prefix.size + 3,
            )

            assertEquals(video.size.toLong(), offset)
        }
    }

    @Test
    fun `last marker wins when metadata contains an earlier marker`() {
        runTest {
            val marker = "MotionPhoto_Data".toByteArray()
            val video = "final-video".toByteArray()
            val input = marker + "metadata".toByteArray() + marker + video

            val offset = findLastMotionPhotoMarkerOffset(
                inputStream = ByteArrayInputStream(input),
                bufferSize = 5,
            )

            assertEquals(video.size.toLong(), offset)
        }
    }

    @Test
    fun `missing marker returns null`() {
        runTest {
            val offset = findLastMotionPhotoMarkerOffset(
                inputStream = ByteArrayInputStream("ordinary-image".toByteArray()),
                bufferSize = 4,
            )

            assertNull(offset)
        }
    }
}
