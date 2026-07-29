package com.dot.gallery.core.ml

import android.graphics.Bitmap
import com.dot.gallery.feature_node.presentation.search.helpers.SearchVisionHelper
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

internal interface ImageEmbeddingGenerator {

    val status: StateFlow<ModelStatus>

    fun openSession(): ImageEmbeddingSession
}

internal interface ImageEmbeddingSession : AutoCloseable {

    fun generate(bitmap: Bitmap): FloatArray
}

internal class ImageEmbeddingGeneratorImpl @Inject constructor(
    private val modelManager: ModelManager,
) : ImageEmbeddingGenerator {
    private val visionHelper by lazy { SearchVisionHelper(modelManager) }

    override val status: StateFlow<ModelStatus> = modelManager.status

    override fun openSession(): ImageEmbeddingSession {
        return ImageEmbeddingSessionImpl(
            visionHelper = visionHelper,
            session = visionHelper.setupVisionSession(),
        )
    }

    private class ImageEmbeddingSessionImpl(
        private val visionHelper: SearchVisionHelper,
        private val session: ManagedOrtSession,
    ) : ImageEmbeddingSession {

        override fun generate(bitmap: Bitmap): FloatArray {
            return visionHelper.getImageEmbedding(
                session = session,
                bitmap = bitmap,
            )
        }

        override fun close() {
            session.close()
        }
    }
}
