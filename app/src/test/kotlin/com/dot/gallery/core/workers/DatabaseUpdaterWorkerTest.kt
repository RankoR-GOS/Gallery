package com.dot.gallery.core.workers

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.dot.gallery.feature_node.data.data_source.InternalDatabase
import com.dot.gallery.feature_node.data.repository.MediaRepository
import io.mockk.Called
import io.mockk.mockk
import io.mockk.verify
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
internal class DatabaseUpdaterWorkerTest {
    @Test
    fun limitedAccess_doesNotPruneTheLibraryOrClassifications() {
        runTest {
            val application = RuntimeEnvironment.getApplication()
            val permissions = listOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            )
            val database = mockk<InternalDatabase>()
            val repository = mockk<MediaRepository>()
            for (grantedPermission in permissions) {
                shadowOf(application).denyPermissions(*permissions.toTypedArray())
                shadowOf(application).grantPermissions(grantedPermission)
                val worker = TestListenableWorkerBuilder<DatabaseUpdaterWorker>(context = application)
                    .setWorkerFactory(
                        object : WorkerFactory() {
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
                        },
                    )
                    .build()

                assertEquals(ListenableWorker.Result.success(), worker.doWork())
                verify { database wasNot Called }
                verify { repository wasNot Called }
            }
        }
    }
}
