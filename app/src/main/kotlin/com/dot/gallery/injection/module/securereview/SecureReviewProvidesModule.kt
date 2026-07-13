package com.dot.gallery.injection.module.securereview

import android.app.KeyguardManager
import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.Reusable
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
internal object SecureReviewProvidesModule {

    @Provides
    @Reusable
    fun provideKeyguardManager(
        @ApplicationContext
        context: Context,
    ): KeyguardManager {
        return context.getSystemService(KeyguardManager::class.java)
    }
}
