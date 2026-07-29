/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.injection.module.core

import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import androidx.room.Room
import androidx.work.WorkManager
import com.dot.gallery.core.DefaultEventHandler
import com.dot.gallery.core.MediaDistributor
import com.dot.gallery.core.MediaDistributorImpl
import com.dot.gallery.core.MediaHandler
import com.dot.gallery.core.MediaHandlerImpl
import com.dot.gallery.core.MediaSelector
import com.dot.gallery.core.MediaSelectorImpl
import com.dot.gallery.core.memory.ByteArrayPool
import com.dot.gallery.core.ml.ModelManager
import com.dot.gallery.core.sandbox.IsolatedMetadataParser
import com.dot.gallery.core.workers.MediaCopyScheduler
import com.dot.gallery.feature_node.data.data_source.InternalDatabase
import com.dot.gallery.feature_node.data.repository.MediaRepository
import com.dot.gallery.feature_node.data.repository.MediaRepositoryImpl
import com.dot.gallery.feature_node.domain.util.EventHandler
import com.dot.gallery.feature_node.presentation.search.SearchHelper
import com.dot.gallery.feature_node.presentation.search.SearchHelperImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    fun provideContentResolver(@ApplicationContext context: Context): ContentResolver =
        context.contentResolver

    @Provides
    fun providePackageManager(@ApplicationContext context: Context): PackageManager {
        return context.packageManager
    }

    @Provides
    @Singleton
    fun provideDatabase(app: Application): InternalDatabase {
        return Room.databaseBuilder(app, InternalDatabase::class.java, InternalDatabase.NAME)
            .fallbackToDestructiveMigration(true)
            .build()
    }

    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager =
        WorkManager.getInstance(context)

    @Provides
    @Singleton
    fun provideEventHandler(): EventHandler = DefaultEventHandler()

    @Provides
    @Singleton
    fun provideMediaDistributor(
        @ApplicationContext context: Context,
        workManager: WorkManager,
        repository: MediaRepository,
        eventHandler: EventHandler,
        database: InternalDatabase,
    ): MediaDistributor = MediaDistributorImpl(
        context = context,
        repository = repository,
        eventHandler = eventHandler,
        workManager = workManager,
        scannedMediaDao = database.getScannedMediaDao(),
    )

    @Provides
    @Singleton
    fun provideMediaSelector(): MediaSelector = MediaSelectorImpl()

    @Provides
    @Singleton
    fun provideMediaHandler(
        @ApplicationContext context: Context,
        mediaRepository: MediaRepository,
        workManager: WorkManager,
    ): MediaHandler = MediaHandlerImpl(mediaRepository, context, workManager)

    @Provides
    @Singleton
    fun provideIsolatedMetadataParser(@ApplicationContext context: Context): IsolatedMetadataParser =
        IsolatedMetadataParser(context)

    @Provides
    @Singleton
    internal fun provideMediaRepository(
        @ApplicationContext context: Context,
        workManager: WorkManager,
        mediaCopyScheduler: MediaCopyScheduler,
        database: InternalDatabase,
        geocoder: Geocoder?,
        isolatedParser: IsolatedMetadataParser,
    ): MediaRepository {
        return MediaRepositoryImpl(
            context = context,
            workManager = workManager,
            mediaCopyScheduler = mediaCopyScheduler,
            database = database,
            geocoder = geocoder,
            isolatedParser = isolatedParser,
        )
    }

    @Provides
    @Singleton
    fun provideModelManager(@ApplicationContext context: Context): ModelManager = ModelManager(context)

    @Provides
    @Singleton
    fun provideSearchHelper(modelManager: ModelManager): SearchHelper = SearchHelperImpl(modelManager)

    @Provides
    @Singleton
    fun provideGeocoder(@ApplicationContext context: Context): Geocoder? {
        return if (Geocoder.isPresent()) Geocoder(context) else null
    }

    @Provides
    @Singleton
    fun provideByteArrayPool(): ByteArrayPool = ByteArrayPool()

}
