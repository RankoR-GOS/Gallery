package com.dot.gallery.core.sandbox

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Test

internal class SandboxDecoderArchitectureTest {
    @Test
    fun sandboxClientEntryPoints_doNotReadWholeSourcesEagerly() {
        val sourceRoot = findAppDirectory().resolve("src/main/kotlin/com/dot/gallery")
        val sourceFiles = listOf(
            sourceRoot.resolve("core/sandbox/IsolatedImageDecoder.kt"),
            sourceRoot.resolve("core/sandbox/MediaPreviewDecoder.kt"),
            sourceRoot.resolve("core/sandbox/EncodedMediaTransfer.kt"),
            sourceRoot.resolve("core/decoder/SandboxedSketchHeifDecoder.kt"),
            sourceRoot.resolve("core/decoder/SandboxedSketchJxlDecoder.kt"),
            sourceRoot.resolve("core/decoder/glide/GalleryMediaModelLoader.kt"),
            sourceRoot.resolve("core/decoder/glide/SandboxedGalleryImageDecoder.kt"),
        )

        sourceFiles.forEach { sourceFile ->
            val source = sourceFile.readText()
            assertFalse("${sourceFile.name} must not call readBytes()", ".readBytes(" in source)
            assertFalse("${sourceFile.name} must not call readAllBytes()", ".readAllBytes(" in source)
        }
    }

    @Test
    fun aiAnalysisEntryPoints_doNotDecodeMediaInTheApplicationProcess() {
        val sourceRoot = findAppDirectory().resolve("src/main/kotlin/com/dot/gallery")
        val sourceFiles = listOf(
            sourceRoot.resolve("core/workers/SearchIndexerUpdaterWorker.kt"),
            sourceRoot.resolve("feature_node/presentation/search/SearchViewModel.kt"),
        )
        val forbiddenCalls = listOf(
            "BitmapFactory",
            "ImageRequest(",
            ".readBytes(",
            ".readAllBytes(",
            ".sketch",
        )

        sourceFiles.forEach { sourceFile ->
            val source = sourceFile.readText()
            forbiddenCalls.forEach { forbiddenCall ->
                assertFalse(
                    "${sourceFile.name} must not use $forbiddenCall",
                    forbiddenCall in source,
                )
            }
        }
    }

    @Test
    fun galleryDoesNotSendCoordinatesToGeocodingProviders() {
        val sourceRoot = findAppDirectory().resolve("src/main/kotlin/com/dot/gallery")
        sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { sourceFile ->
            assertFalse(
                "${sourceFile.name} must keep photo coordinates local",
                "android.location.Geocoder" in sourceFile.readText(),
            )
        }
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
