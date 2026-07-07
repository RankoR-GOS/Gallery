package com.dot.gallery.injection.module.crop

import com.dot.gallery.feature_node.data.externalcrop.ComponentCallerExternalCropUriPermissionChecker
import com.dot.gallery.feature_node.data.externalcrop.ExternalCropIntentParserImpl
import com.dot.gallery.feature_node.data.externalcrop.ExternalCropUriPermissionChecker
import com.dot.gallery.feature_node.data.repository.ExternalCropRepositoryImpl
import com.dot.gallery.feature_node.domain.externalcrop.ExternalCropIntentParser
import com.dot.gallery.feature_node.domain.repository.ExternalCropRepository
import dagger.Binds
import dagger.Module
import dagger.Reusable
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
internal abstract class ExternalCropBindsModule {

    @Binds
    @Reusable
    abstract fun bindExternalCropIntentParser(
        impl: ExternalCropIntentParserImpl,
    ): ExternalCropIntentParser

    @Binds
    @Reusable
    abstract fun bindExternalCropRepository(
        impl: ExternalCropRepositoryImpl,
    ): ExternalCropRepository

    @Binds
    @Reusable
    abstract fun bindExternalCropUriPermissionChecker(
        impl: ComponentCallerExternalCropUriPermissionChecker,
    ): ExternalCropUriPermissionChecker
}
