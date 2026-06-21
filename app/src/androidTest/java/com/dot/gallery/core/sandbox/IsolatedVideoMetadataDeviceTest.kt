package com.dot.gallery.core.sandbox

import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
internal class IsolatedVideoMetadataDeviceTest {
    @Test
    fun movMetadataSurvivesMissingPlatformDimensionsAndMalformedInput() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("metadata-fallback-", ".mov", context.cacheDir)
        val parser = IsolatedMetadataParser(context = context)
        try {
            file.writeBytes(metadataOnlyMov())
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(file.absolutePath)
                assertNull(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH))
            }
            runBlocking {
                val uri = Uri.fromFile(file)
                val summary = parser.parseVideoMetadata(uri = uri)
                assertNotNull(summary)
                assertEquals(640, requireNotNull(summary).getInt(IsolatedMetadataService.KEY_VIDEO_WIDTH))
                assertEquals(480, summary.getInt(IsolatedMetadataService.KEY_VIDEO_HEIGHT))
                val directories = parser.parseRawMetadata(uri = uri, isVideo = true)
                assertTrue(directories.toString(), directories.any { it.name == "QuickTime Video" })
                val tags = directories.flatMap { it.tags }
                assertTrue(tags.toString(), tags.any { it.description == "Gallery Fixture" })
                assertTrue(tags.toString(), tags.any { it.description == "X".repeat(512) })
                assertTrue(tags.all { it.name.length <= 512 && it.description.length <= 512 })
                file.writeBytes(byteArrayOf(0, 1, 2))
                assertNull(parser.parseVideoMetadata(uri = uri))
                assertTrue(parser.parseRawMetadata(uri = uri, isVideo = true).isEmpty())
                file.writeBytes(metadataOnlyMov())
                assertNotNull(parser.parseVideoMetadataPerFile(uri = uri, mediaId = 907L))
                assertFalse(parser.parseRawMetadataPerFile(uri = uri, isVideo = true, mediaId = 907L).isEmpty())
                for (asset in listOf("GalleryDecoderTest.png", "GalleryDecoderTest.mp4")) {
                    InstrumentationRegistry.getInstrumentation().context.assets.open(asset).use { input ->
                        file.outputStream().use { output -> input.copyTo(output) }
                    }
                    assertFalse(parser.parseRawMetadata(uri = uri, isVideo = asset.endsWith(".mp4")).isEmpty())
                }
            }
        } finally {
            parser.unbind()
            check(file.delete())
        }
    }

    // QuickTime metadata without usable samples: platform dimensions are unavailable.
    private fun metadataOnlyMov(): ByteArray {
        val sample = ByteBuffer.allocate(86)
            .putInt(86).put("jpeg".toByteArray()).put(ByteArray(6)).putShort(1)
            .putShort(0).putShort(0).put("appl".toByteArray()).putInt(0).putInt(0)
            .putShort(640).putShort(480).putInt(72 shl 16).putInt(72 shl 16)
            .putInt(0).putShort(1).put(ByteArray(32)).putShort(24).putShort(-1).array()
        val description = atom(type = "stsd", payload = ByteBuffer.allocate(8).putInt(0).putInt(1).array() + sample)
        val handler = atom(type = "hdlr", payload = ByteArray(4) + "mhlrvide".toByteArray() + ByteArray(13))
        val media = atom(type = "mdia", payload = handler + atom(type = "minf", payload = atom(type = "stbl", payload = description +
            atom(type = "stco", payload = ByteBuffer.allocate(8).putInt(0).putInt(Int.MAX_VALUE).array()))))
        return atom(type = "ftyp", payload = "qt  ".toByteArray() + ByteArray(4) + "qt  ".toByteArray()) +
            atom(type = "moov", payload = atom(type = "trak", payload = media) + cameraMetadata())
    }

    private fun cameraMetadata(): ByteArray {
        val values = listOf("com.apple.quicktime.make" to "Gallery Fixture", "com.apple.quicktime.model" to "X".repeat(4096))
        var keys = ByteBuffer.allocate(8).putInt(0).putInt(values.size).array()
        var items = ByteArray(0)
        for ((index, entry) in values.withIndex()) {
            val (key, value) = entry
            keys += atom(type = "mdta", payload = key.toByteArray())
            val data = atom(type = "data", payload = ByteBuffer.allocate(8).putInt(1).putInt(0).array() + value.toByteArray())
            items += ByteBuffer.allocate(8).putInt(8 + data.size).putInt(index + 1).array() + data
        }
        val handler = atom(type = "hdlr", payload = ByteArray(4) + "mhlrmdta".toByteArray() + ByteArray(13))
        return atom(type = "meta", payload = ByteArray(4) + handler + atom(type = "keys", payload = keys) + atom(type = "ilst", payload = items))
    }

    private fun atom(type: String, payload: ByteArray): ByteArray {
        return ByteBuffer.allocate(8 + payload.size).putInt(8 + payload.size).put(type.toByteArray()).put(payload).array()
    }
}
