package com.dot.gallery.core.workers

import android.Manifest
import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import androidx.work.testing.TestListenableWorkerBuilder
import com.dot.gallery.core.Resource
import com.dot.gallery.core.sandbox.IsolatedMetadataParser
import com.dot.gallery.feature_node.data.data_source.InternalDatabase
import com.dot.gallery.feature_node.data.data_source.MetadataDao
import com.dot.gallery.feature_node.data.model.Media
import com.dot.gallery.feature_node.data.model.MediaMetadata
import com.dot.gallery.feature_node.data.repository.MediaRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@Config(application = Application::class)
@RunWith(RobolectricTestRunner::class)
internal class MetadataCollectionWorkerTest {
    @Test
    fun emptySelectedLibrary_preservesMetadataAndDoesNotMarkTheLibraryUpToDate() {
        runTest {
            val application = RuntimeEnvironment.getApplication()
            shadowOf(application).denyPermissions(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
            )
            shadowOf(application).grantPermissions(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            val database = mockk<InternalDatabase>()
            val metadataDao = mockk<MetadataDao>()
            val repository = mockk<MediaRepository>()
            every { database.getMetadataDao() } returns metadataDao
            every { metadataDao.getProcessedMediaIds() } returns flowOf(emptyList())
            every { repository.getCompleteMedia() } returns flowOf(Resource.Success(emptyList()))
            val worker = TestListenableWorkerBuilder<MetadataCollectionWorker>(context = application)
                .setWorkerFactory(
                    object : WorkerFactory() {
                        override fun createWorker(
                            appContext: Context,
                            workerClassName: String,
                            workerParameters: WorkerParameters,
                        ): ListenableWorker {
                            return MetadataCollectionWorker(
                                database = database,
                                repository = repository,
                                isolatedParser = mockk(),
                                appContext = appContext,
                                workerParams = workerParameters,
                            )
                        }
                    },
                )
                .build()

            assertEquals(ListenableWorker.Result.success(), worker.doWork())
            verify(exactly = 1) { repository.getCompleteMedia() }
            coVerify(exactly = 0) { metadataDao.deleteForgottenMetadata(ids = any()) }
            coVerify(exactly = 0) { metadataDao.setMediaVersion(version = any()) }
        }
    }

    @Test
    fun selectedLibrary_skipsPreviouslyExtractedMetadataUnlessForced() {
        runTest {
            val application = RuntimeEnvironment.getApplication()
            shadowOf(application).denyPermissions(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
            )
            shadowOf(application).grantPermissions(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            val database = mockk<InternalDatabase>()
            val metadataDao = mockk<MetadataDao>()
            val repository = mockk<MediaRepository>()
            val parser = mockk<IsolatedMetadataParser>()
            val processedIds = MutableStateFlow<List<Long>>(emptyList())
            every { database.getMetadataDao() } returns metadataDao
            every { metadataDao.getProcessedMediaIds() } returns processedIds
            coEvery { metadataDao.addMetadata(mediaMetadata = any(), isVideo = true) } answers {
                processedIds.value = listOf(firstArg<MediaMetadata>().mediaId)
            }
            val selectedMedia = Media.UriMedia(
                id = 1L,
                label = "selected.mp4",
                uri = Uri.parse("content://media/external/video/media/1"),
                path = "/storage/emulated/0/Movies/selected.mp4",
                relativePath = "Movies",
                albumID = 1L,
                albumLabel = "Movies",
                timestamp = 10L,
                fullDate = "",
                mimeType = "video/mp4",
                duration = "1000",
                favorite = 0,
                trashed = 0,
                size = 1L,
            )
            every { repository.getCompleteMedia() } returns flowOf(Resource.Success(listOf(selectedMedia)))
            coEvery { parser.parseVideoMetadata(uri = selectedMedia.uri) } returns Bundle()

            suspend fun collectMetadata(forceReload: Boolean = false) {
                val worker = TestListenableWorkerBuilder<MetadataCollectionWorker>(context = application)
                    .setInputData(workDataOf("forceReload" to forceReload))
                    .setWorkerFactory(
                        object : WorkerFactory() {
                            override fun createWorker(
                                appContext: Context,
                                workerClassName: String,
                                workerParameters: WorkerParameters,
                            ): ListenableWorker {
                                return MetadataCollectionWorker(
                                    database = database,
                                    repository = repository,
                                    isolatedParser = parser,
                                    appContext = appContext,
                                    workerParams = workerParameters,
                                )
                            }
                        },
                    )
                    .build()
                assertEquals(ListenableWorker.Result.success(), worker.doWork())
            }

            collectMetadata()
            collectMetadata()
            coVerify(exactly = 1) { parser.parseVideoMetadata(uri = selectedMedia.uri) }
            coVerify(exactly = 1) { metadataDao.addMetadata(mediaMetadata = any(), isVideo = true) }

            collectMetadata(forceReload = true)
            coVerify(exactly = 2) { parser.parseVideoMetadata(uri = selectedMedia.uri) }
            coVerify(exactly = 0) { metadataDao.deleteForgottenMetadata(ids = any()) }
            coVerify(exactly = 0) { metadataDao.setMediaVersion(version = any()) }
        }
    }

}
