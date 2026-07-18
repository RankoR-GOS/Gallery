package com.dot.gallery.feature_node.domain.use_case

import com.dot.gallery.feature_node.domain.repository.MediaRepository
import javax.inject.Inject

internal class UpdateMediaDatabase @Inject constructor(
    private val repository: MediaRepository,
    private val aiMediaAnalysis: AiMediaAnalysis,
) {

    suspend operator fun invoke() {
        repository.updateInternalDatabase()
        aiMediaAnalysis.requestAnalysis()
    }
}
