package com.dot.gallery.injection.module.core

import com.dot.gallery.feature_node.data.repository.MotionPhotoRepository
import com.dot.gallery.feature_node.data.repository.MotionPhotoRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.Reusable
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
internal abstract class MotionPhotoBindsModule {

    @Binds
    @Reusable
    abstract fun bindMotionPhotoRepository(
        implementation: MotionPhotoRepositoryImpl,
    ): MotionPhotoRepository
}
