package com.dot.gallery.injection.module.core

import com.dot.gallery.feature_node.data.repository.WidgetRepository
import com.dot.gallery.feature_node.data.repository.WidgetRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal abstract class WidgetBindsModule {

    @Binds
    @Singleton
    abstract fun bindWidgetRepository(
        implementation: WidgetRepositoryImpl,
    ): WidgetRepository
}
