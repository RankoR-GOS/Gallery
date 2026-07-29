package com.dot.gallery.feature_node.presentation.search

import android.graphics.Bitmap
import com.dot.gallery.core.ml.ManagedOrtSession
import com.dot.gallery.core.ml.ModelManager
import com.dot.gallery.feature_node.presentation.search.helpers.SearchVisionHelper
import com.dot.gallery.feature_node.presentation.search.util.dot
import com.dot.gallery.feature_node.presentation.util.printDebug
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SearchHelperImpl @Inject constructor(
    private val modelManager: ModelManager,
) : SearchHelper {

    override val isAvailable: Boolean get() = modelManager.isReady

    private val helper by lazy { SearchVisionHelper(modelManager) }

    override fun sortByCosineDistance(
        searchEmbedding: FloatArray,
        imageEmbeddingsList: List<FloatArray>,
        imageIdxList: List<Long>,
    ): List<Pair<Long, Float>> {
        val distances = LinkedHashMap<Long, Float>()
        for (i in imageEmbeddingsList.indices) {
            val dist = searchEmbedding.dot(imageEmbeddingsList[i])
            distances[imageIdxList[i]] = dist
        }
        return distances.toList()
            .filter { it.second >= SearchVisionHelper.THRESHOLD }
            .sortedByDescending { it.second }
            .map {
                printDebug(it)
                it
            }
    }

    override fun setupTextSession(): ManagedOrtSession {
        return helper.setupTextSession()
    }

    override suspend fun getTextEmbedding(session: ManagedOrtSession, text: String): FloatArray {
        return withContext(Dispatchers.IO) {
            helper.getTextEmbedding(session = session, text = text)
        }
    }

    override fun setupVisionSession(): ManagedOrtSession {
        return helper.setupVisionSession()
    }

    override suspend fun getImageEmbedding(session: ManagedOrtSession, bitmap: Bitmap): FloatArray {
        return withContext(Dispatchers.IO) {
            helper.getImageEmbedding(session = session, bitmap = bitmap)
        }
    }

}
