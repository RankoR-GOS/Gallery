package com.dot.gallery.core.sandbox

import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

internal class SandboxDecoderArchitectureTest {
    @Test
    fun sandboxClientEntryPoints_doNotReadWholeSourcesEagerly() {
        val sourceRoot = findAppDirectory().resolve("src/main/kotlin/com/dot/gallery")
        val sourceFiles = listOf(
            sourceRoot.resolve("core/sandbox/IsolatedImageDecoder.kt"),
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
