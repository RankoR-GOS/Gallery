package com.dot.gallery.injection.module.core

import com.dot.gallery.core.ml.ImageEmbeddingGenerator
import com.dot.gallery.core.ml.ImageEmbeddingGeneratorImpl
import com.dot.gallery.feature_node.domain.repository.AiMediaAnalysisRepository
import com.dot.gallery.feature_node.domain.repository.AiMediaAnalysisRepositoryImpl
import com.dot.gallery.feature_node.domain.use_case.AiMediaAnalysis
import com.dot.gallery.feature_node.domain.use_case.AiMediaAnalysisImpl
import com.dot.gallery.feature_node.domain.use_case.AiMediaAnalysisScheduler
import com.dot.gallery.feature_node.domain.use_case.AiMediaAnalysisSchedulerImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal abstract class AiMediaAnalysisBindsModule {

    @Binds
    @Singleton
    abstract fun bindAiMediaAnalysisRepository(
        implementation: AiMediaAnalysisRepositoryImpl,
    ): AiMediaAnalysisRepository

    @Binds
    @Singleton
    abstract fun bindAiMediaAnalysisScheduler(
        implementation: AiMediaAnalysisSchedulerImpl,
    ): AiMediaAnalysisScheduler

    @Binds
    @Singleton
    abstract fun bindAiMediaAnalysis(implementation: AiMediaAnalysisImpl): AiMediaAnalysis

    @Binds
    @Singleton
    abstract fun bindImageEmbeddingGenerator(
        implementation: ImageEmbeddingGeneratorImpl,
    ): ImageEmbeddingGenerator
}
