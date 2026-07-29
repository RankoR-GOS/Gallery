package com.dot.gallery.feature_node.presentation.classifier

import com.dot.gallery.feature_node.data.model.ImageEmbedding
import com.dot.gallery.feature_node.data.repository.MediaRepository
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal suspend fun scoreImageEmbeddingPages(
    repository: MediaRepository,
    scoreEmbedding: (ImageEmbedding) -> Float?,
): List<Pair<Long, Float>> {
    val matches = mutableListOf<Pair<Long, Float>>()
    var afterId = Long.MIN_VALUE

    while (true) {
        currentCoroutineContext().ensureActive()
        val page = repository.getImageEmbeddingPage(
            afterId = afterId,
            limit = EMBEDDING_PAGE_SIZE,
        )

        if (page.isEmpty()) {
            break
        }

        page.forEach { imageEmbedding ->
            scoreEmbedding(imageEmbedding)?.let { score ->
                matches += imageEmbedding.id to score
            }
        }
        afterId = page.last().id
    }

    return matches.sortedByDescending { (_, score) -> score }
}

private const val EMBEDDING_PAGE_SIZE = 256
