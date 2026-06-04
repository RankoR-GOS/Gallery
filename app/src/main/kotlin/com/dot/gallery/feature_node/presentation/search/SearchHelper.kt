package com.dot.gallery.feature_node.presentation.search

import android.graphics.Bitmap
import com.dot.gallery.core.ml.ManagedOrtSession

interface SearchHelper {

    val isAvailable: Boolean

    fun sortByCosineDistance(
        searchEmbedding: FloatArray,
        imageEmbeddingsList: List<FloatArray>,
        imageIdxList: List<Long>,
    ): List<Pair<Long, Float>>

    suspend fun getTextEmbedding(session: ManagedOrtSession, text: String): FloatArray

    fun setupTextSession(): ManagedOrtSession

    fun setupVisionSession(): ManagedOrtSession

    suspend fun getImageEmbedding(session: ManagedOrtSession, bitmap: Bitmap): FloatArray
}
