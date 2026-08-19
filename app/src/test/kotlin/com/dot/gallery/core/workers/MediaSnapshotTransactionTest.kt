package com.dot.gallery.core.workers

import android.Manifest
import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.room.Room
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.dot.gallery.core.Resource
import com.dot.gallery.feature_node.data.data_source.InternalDatabase
import com.dot.gallery.feature_node.data.model.Media
import com.dot.gallery.feature_node.data.model.MediaVersion
import com.dot.gallery.feature_node.data.repository.MediaRepository
import com.dot.gallery.feature_node.presentation.util.mediaStoreVersion
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@Config(application = Application::class)
@RunWith(RobolectricTestRunner::class)
internal class MediaSnapshotTransactionTest {
    @Test
    fun failedVersionWriteRollsBackTheMirrorAndMetadataCannotMarkItCurrent() {
        runTest {
            val context = RuntimeEnvironment.getApplication()
            shadowOf(context).grantPermissions(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
            val database = Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java)
                .allowMainThreadQueries().build()
            mockkStatic(MediaStore::class)
            try {
                every { MediaStore.getExternalVolumeNames(context) } returns setOf("abcd-1234", "external_primary")
                every { MediaStore.getGeneration(context, any()) } returns 7L
                every { MediaStore.getVersion(context) } returns "v1"
                val version = context.mediaStoreVersion
                assertEquals("abcd-1234:7,external_primary:7/v1", version)
                val dao = database.getMediaDao()
                dao.updateMedia(mediaList = listOf(media(id = 1L)))
                dao.setMediaVersion(version = MediaVersion(version = "old"))
                database.getMetadataDao().setMediaVersion(version = MediaVersion(version = version))
                assertFalse(dao.isMediaVersionUpToDate(version = version))
                database.openHelper.writableDatabase.execSQL(
                    "CREATE TRIGGER fail_version BEFORE INSERT ON media_version " +
                        "WHEN NEW.version LIKE 'media:%' BEGIN SELECT RAISE(ABORT, 'test failure'); END",
                )
                val repository = mockk<MediaRepository>()
                every { repository.getCompleteMedia() } returns flowOf(Resource.Success(listOf(media(id = 2L))))
                val worker = TestListenableWorkerBuilder<DatabaseUpdaterWorker>(context = context)
                    .setWorkerFactory(object : WorkerFactory() {
                        override fun createWorker(
                            appContext: Context,
                            workerClassName: String,
                            workerParameters: WorkerParameters,
                        ): ListenableWorker {
                            return DatabaseUpdaterWorker(
                                database = database,
                                repository = repository,
                                appContext = appContext,
                                workerParams = workerParameters,
                            )
                        }
                    }).build()
                assertEquals(ListenableWorker.Result.failure(), worker.doWork())
                assertEquals(listOf(1L), dao.getMedia().map { it.id })
                assertTrue(dao.isMediaVersionUpToDate(version = "old"))
                assertFalse(dao.isMediaVersionUpToDate(version = version))
                database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_version")
                assertEquals(ListenableWorker.Result.success(), worker.doWork())
                assertEquals(listOf(2L), dao.getMedia().map { it.id })
                assertTrue(dao.isMediaVersionUpToDate(version = version))
                assertFalse(dao.isMediaVersionUpToDate(version = "old"))
                assertTrue(database.getMetadataDao().isMediaVersionUpToDate(version = version))
            } finally {
                database.close()
                unmockkStatic(MediaStore::class)
            }
        }
    }

    private fun media(id: Long): Media.UriMedia {
        return Media.UriMedia(
            id = id,
            label = "test.jpg",
            uri = Uri.parse("content://media/external_primary/images/media/$id"),
            path = "",
            relativePath = "Pictures/",
            albumID = 1L,
            albumLabel = "Pictures",
            timestamp = 1L,
            fullDate = "",
            mimeType = "image/jpeg",
            favorite = 0,
            trashed = 0,
            size = 1L,
        )
    }
}
