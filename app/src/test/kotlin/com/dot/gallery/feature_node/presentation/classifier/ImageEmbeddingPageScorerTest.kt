package com.dot.gallery.feature_node.presentation.classifier

import com.dot.gallery.feature_node.data.model.ImageEmbedding
import com.dot.gallery.feature_node.data.repository.MediaRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

internal class ImageEmbeddingPageScorerTest {

    @Test
    fun scoreImageEmbeddingPages_readsAndScoresBoundedPages() {
        runTest {
            val repository = mockk<MediaRepository>()
            coEvery {
                repository.getImageEmbeddingPage(afterId = Long.MIN_VALUE, limit = PAGE_SIZE)
            } returns listOf(
                embedding(id = 1L, score = 0.2f),
                embedding(id = 2L, score = 0.8f),
            )
            coEvery {
                repository.getImageEmbeddingPage(afterId = 2L, limit = PAGE_SIZE)
            } returns listOf(embedding(id = 3L, score = 0.5f))
            coEvery {
                repository.getImageEmbeddingPage(afterId = 3L, limit = PAGE_SIZE)
            } returns emptyList()

            val scores = scoreImageEmbeddingPages(repository = repository) { imageEmbedding ->
                imageEmbedding.embedding.single()
            }

            assertEquals(listOf(2L, 3L, 1L), scores.map { (mediaId, _) -> mediaId })
            coVerify(exactly = 1) {
                repository.getImageEmbeddingPage(afterId = Long.MIN_VALUE, limit = PAGE_SIZE)
                repository.getImageEmbeddingPage(afterId = 2L, limit = PAGE_SIZE)
                repository.getImageEmbeddingPage(afterId = 3L, limit = PAGE_SIZE)
            }
        }
    }

    private fun embedding(id: Long, score: Float): ImageEmbedding {
        return ImageEmbedding(
            id = id,
            date = 1L,
            embedding = floatArrayOf(score),
        )
    }

    private companion object {
        private const val PAGE_SIZE = 256
    }
}
