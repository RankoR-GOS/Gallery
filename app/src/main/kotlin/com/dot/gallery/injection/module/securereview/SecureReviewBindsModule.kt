package com.dot.gallery.injection.module.securereview

import com.dot.gallery.feature_node.data.repository.SecureReviewMediaRepository
import com.dot.gallery.feature_node.data.repository.SecureReviewMediaRepositoryImpl
import com.dot.gallery.feature_node.domain.securereview.AuthorizeSecureReviewRequest
import com.dot.gallery.feature_node.domain.securereview.AuthorizeSecureReviewRequestImpl
import com.dot.gallery.feature_node.domain.securereview.IsDeviceLocked
import com.dot.gallery.feature_node.domain.securereview.IsDeviceLockedImpl
import dagger.Binds
import dagger.Module
import dagger.Reusable
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
internal abstract class SecureReviewBindsModule {

    @Binds
    @Reusable
    abstract fun bindAuthorizeSecureReviewRequest(
        implementation: AuthorizeSecureReviewRequestImpl,
    ): AuthorizeSecureReviewRequest

    @Binds
    @Reusable
    abstract fun bindIsDeviceLocked(
        implementation: IsDeviceLockedImpl,
    ): IsDeviceLocked

    @Binds
    @Reusable
    abstract fun bindSecureReviewMediaRepository(
        implementation: SecureReviewMediaRepositoryImpl,
    ): SecureReviewMediaRepository
}
