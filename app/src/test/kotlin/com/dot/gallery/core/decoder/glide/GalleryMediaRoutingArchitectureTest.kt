package com.dot.gallery.core.decoder.glide

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

internal class GalleryMediaRoutingArchitectureTest {
    @Test
    fun zoomablePagerImage_registersVerifiedGallerySubsamplingGenerator() {
        val source = findAppDirectory()
            .resolve(
                "src/main/kotlin/com/dot/gallery/feature_node/presentation/" +
                        "mediaview/components/media/ZoomablePagerImage.kt",
            )
            .readText()

        assertTrue(
            "ZoomablePagerImage must construct the verified subsampling generator list",
            "galleryMediaSubsamplingImageGenerators()" in source,
        )
        assertTrue(
            "ZoomablePagerImage must register its verified generator list with ZoomImage",
            Regex(
                pattern = """rememberGlideZoomState\s*\(\s*""" +
                        """subsamplingImageGenerators\s*=\s*subsamplingImageGenerators\s*\)""",
            ).containsMatchIn(input = source),
        )
    }

    private fun findAppDirectory(): File {
        val workingDirectory = requireNotNull(System.getProperty("user.dir")) {
            "Working directory is unavailable"
        }
        return generateSequence(File(workingDirectory)) { directory ->
            directory.parentFile
        }.first { directory ->
            directory.resolve("src/main/kotlin/com/dot/gallery").isDirectory
        }
    }
}
