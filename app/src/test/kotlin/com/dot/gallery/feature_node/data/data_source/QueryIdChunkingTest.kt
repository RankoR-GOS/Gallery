package com.dot.gallery.feature_node.data.data_source

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

internal class QueryIdChunkingTest {

    @Test
    fun forEachIdChunk_limitsEveryQueryArgumentSet() {
        runTest {
            val processedChunks = mutableListOf<Set<Long>>()

            forEachIdChunk(ids = (1L..2_001L).toSet()) { mediaIds ->
                processedChunks += mediaIds
            }

            assertEquals(listOf(900, 900, 201), processedChunks.map { chunk -> chunk.size })
            assertTrue(processedChunks.flatten().toSet() == (1L..2_001L).toSet())
        }
    }

    @Test
    fun flatMapIdChunks_limitsEveryQueryArgumentSetAndConcatenatesRows() {
        runTest {
            val queriedChunks = mutableListOf<Set<Long>>()

            val rows = flatMapIdChunks(ids = (1L..2_001L).toSet()) { mediaIds ->
                queriedChunks += mediaIds
                mediaIds.map { id -> id * 2 }
            }

            assertEquals(listOf(900, 900, 201), queriedChunks.map { chunk -> chunk.size })
            assertEquals((1L..2_001L).map { id -> id * 2 }.toSet(), rows.toSet())
        }
    }
}
