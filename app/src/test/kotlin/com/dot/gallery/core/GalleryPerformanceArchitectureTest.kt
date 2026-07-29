package com.dot.gallery.core

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

internal class GalleryPerformanceArchitectureTest {

    @Test
    fun mediaCells_doNotCollectSelectorOrMetadataFlows() {
        val source = sourceFile(
            "feature_node/presentation/common/components/MediaImage.kt",
        ).readText()

        assertFalse(source.contains("collectAsState"))
        assertFalse(source.contains("LocalMediaSelector"))
        assertFalse(source.contains("MediaMetadataState"))
    }

    @Test
    fun mediaDistributor_doesNotExposeEagerEmbeddingState() {
        val contract = sourceFile("core/MediaDistributor.kt").readText()
        val implementation = sourceFile("core/MediaDistributorImpl.kt").readText()

        assertFalse(contract.contains("imageEmbeddingsFlow"))
        assertFalse(implementation.contains("imageEmbeddingsFlow"))
    }

    @Test
    fun mosaicLayoutAndDragSelection_keepHeavyWorkOffTheMainPath() {
        val mosaicSource = sourceFile(
            "feature_node/presentation/common/components/MosaicMediaGrid.kt",
        ).readText()
        val selectionSource = sourceFile(
            "feature_node/presentation/util/MultiSelectExt.kt",
        ).readText()

        assertTrue(mosaicSource.contains("withContext(Dispatchers.Default)"))
        assertTrue(mosaicSource.contains("derivedStateOf { mappedData.toList() }"))
        assertFalse(mosaicSource.contains("val mappedDataSnapshot = mappedData.toList()"))
        assertFalse(selectionSource.contains(".indexOf("))
        assertFalse(selectionSource.contains("!!"))
    }

    /**
     * Dependencies run one way: the domain layer builds on the data layer, never the reverse. A
     * data source that reaches back into domain makes the storage and query code inseparable from
     * the app's higher-level concepts, so it is caught here rather than at review time.
     */
    @Test
    fun dataLayer_doesNotDependOnTheDomainLayer() {
        val dataSources = sourceFile("feature_node/data")
            .walkTopDown()
            .filter { file -> file.isFile && file.extension == "kt" }
            .toList()

        assertTrue("No data sources found — has the layout moved?", dataSources.isNotEmpty())
        dataSources.forEach { dataSource ->
            assertFalse(
                "Data source depends on the domain layer: ${dataSource.path}",
                dataSource.readText().withoutStringLiterals()
                    .contains("com.dot.gallery.feature_node.domain"),
            )
        }
    }

    /**
     * Drops string contents so that a domain package name appearing as *data* — a pinned
     * `@SerialName`, a stored column default — does not read as a dependency on it.
     */
    private fun String.withoutStringLiterals(): String {
        return replace(RAW_STRING, "").replace(ESCAPED_STRING, "")
    }

    private fun sourceFile(relativePath: String): File {
        return File("src/main/kotlin/com/dot/gallery/${relativePath}")
    }

    private companion object {
        private val RAW_STRING = Regex("\"\"\".*?\"\"\"", RegexOption.DOT_MATCHES_ALL)
        private val ESCAPED_STRING = Regex("""["](\\.|[^"\\\n])*["]""")
    }
}
