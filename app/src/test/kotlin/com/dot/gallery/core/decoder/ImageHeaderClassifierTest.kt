package com.dot.gallery.core.decoder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

internal class ImageHeaderClassifierTest {

    @Test
    fun classifyImageHeader_svgWithXmlDeclaration_returnsSvg() {
        val header = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!-- generated fixture -->
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 16">
        """.trimIndent().encodeToByteArray()

        assertEquals(
            ImageFileFormat.SVG,
            classifyImageHeader(header = header, length = header.size),
        )
    }

    @Test
    fun classifyImageHeader_textMentioningSvgWithoutTag_returnsNull() {
        val header = "This is not an SVG image".encodeToByteArray()

        assertNull(classifyImageHeader(header = header, length = header.size))
    }

    @Test
    fun classifyImageHeader_recognizesStandardImageFormats() {
        assertEquals(
            ImageFileFormat.JPEG,
            classify(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())),
        )
        assertEquals(
            ImageFileFormat.PNG,
            classify(
                byteArrayOf(
                    0x89.toByte(),
                    0x50,
                    0x4E,
                    0x47,
                    0x0D,
                    0x0A,
                    0x1A,
                    0x0A,
                ),
            ),
        )
        assertEquals(ImageFileFormat.GIF, classify("GIF89a".encodeToByteArray()))
        assertEquals(
            ImageFileFormat.WEBP,
            classify("RIFF0000WEBP".encodeToByteArray()),
        )
        assertEquals(
            ImageFileFormat.ANIMATED_WEBP,
            classify(
                "RIFF0000WEBPVP8X00000".encodeToByteArray().apply {
                    this[20] = 0x02
                },
            ),
        )
    }

    @Test
    fun classifyImageHeader_recognizesSandboxedFormats() {
        assertEquals(
            ImageFileFormat.JXL,
            classify(byteArrayOf(0xFF.toByte(), 0x0A)),
        )
        assertEquals(
            ImageFileFormat.AVIF,
            classify(ftypBox(primaryBrand = "avif", compatibleBrand = "mif1")),
        )
        assertEquals(
            ImageFileFormat.HEIF,
            classify(ftypBox(primaryBrand = "mif1", compatibleBrand = "heic")),
        )
    }

    @Test
    fun classifyImageHeader_rejectsUnknownAndMalformedInputs() {
        assertNull(classify(ByteArray(size = 0)))
        assertNull(classify("not an image".encodeToByteArray()))
        assertNull(
            classify(
                byteArrayOf(
                    0x00,
                    0x00,
                    0x10,
                    0x00,
                    0x66,
                    0x74,
                    0x79,
                    0x70,
                ),
            ),
        )
    }

    private fun classify(bytes: ByteArray): ImageFileFormat? {
        return classifyImageHeader(header = bytes, length = bytes.size)
    }

    private fun ftypBox(primaryBrand: String, compatibleBrand: String): ByteArray {
        val box = ByteArray(size = 20)
        box[3] = box.size.toByte()
        "ftyp".encodeToByteArray().copyInto(destination = box, destinationOffset = 4)
        primaryBrand.encodeToByteArray().copyInto(destination = box, destinationOffset = 8)
        compatibleBrand.encodeToByteArray().copyInto(destination = box, destinationOffset = 16)
        return box
    }
}
