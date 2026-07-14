package com.dot.gallery.injection.module.core

import com.dot.gallery.core.workers.MediaCopyScheduler
import com.dot.gallery.core.workers.MediaCopySchedulerImpl
import com.dot.gallery.feature_node.data.repository.MediaCopyRepository
import com.dot.gallery.feature_node.data.repository.MediaCopyRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.Reusable
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
internal abstract class MediaCopyBindsModule {

    @Binds
    @Reusable
    abstract fun bindMediaCopyScheduler(
        implementation: MediaCopySchedulerImpl,
    ): MediaCopyScheduler

    @Binds
    @Reusable
    abstract fun bindMediaCopyRepository(
        implementation: MediaCopyRepositoryImpl,
    ): MediaCopyRepository
}
