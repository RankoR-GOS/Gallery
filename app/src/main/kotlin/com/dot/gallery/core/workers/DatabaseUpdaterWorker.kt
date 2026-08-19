package com.dot.gallery.core.workers

import android.content.Context
import androidx.compose.ui.util.fastMap
import androidx.hilt.work.HiltWorker
import androidx.room.withTransaction
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.dot.gallery.core.Resource
import com.dot.gallery.core.util.hasFullMediaAccess
import com.dot.gallery.feature_node.data.data_source.InternalDatabase
import com.dot.gallery.feature_node.data.model.MediaVersion
import com.dot.gallery.feature_node.data.repository.MediaRepository
import com.dot.gallery.feature_node.presentation.util.isMediaUpToDate
import com.dot.gallery.feature_node.presentation.util.mediaStoreVersion
import com.dot.gallery.feature_node.presentation.util.printDebug
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

internal fun WorkManager.updateDatabase() {
    val workPolicy = ExistingWorkPolicy.KEEP
    val constraints = Constraints.Builder()
        .setRequiresStorageNotLow(true)
        .build()

    val databaseUpdaterWork = OneTimeWorkRequestBuilder<DatabaseUpdaterWorker>()
        .setConstraints(constraints)
        .build()

    val metadataWork = OneTimeWorkRequestBuilder<MetadataCollectionWorker>()
        .setConstraints(constraints)
        .addTag("MetadataCollection")
        .build()

    enqueueUniqueWork("DatabaseUpdaterWorker", workPolicy, databaseUpdaterWork)
    enqueueUniqueWork("MetadataCollection", workPolicy, metadataWork)
}

@HiltWorker
class DatabaseUpdaterWorker @AssistedInject constructor(
    private val database: InternalDatabase,
    private val repository: MediaRepository,
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            delay(5000)
            if (!currentCoroutineContext().isActive || isStopped) {
                return Result.success()
            }
            // A limited selection is not a complete inventory of files to retain.
            if (!appContext.hasFullMediaAccess()) {
                return Result.success()
            }
            if (database.isMediaUpToDate(appContext)) {
                printDebug("Database is up to date")
                return Result.success()
            }
            withContext(Dispatchers.IO) {
                val mediaVersion = appContext.mediaStoreVersion
                val response = repository.getCompleteMedia().firstOrNull()
                check(response is Resource.Success) { "Could not read a complete media inventory" }
                val media = requireNotNull(response.data)
                if (!appContext.hasFullMediaAccess()) {
                    return@withContext
                }
                database.withTransaction {
                    printDebug("Database is not up to date. Updating to version $mediaVersion")
                    database.getMediaDao().updateMedia(mediaList = media)
                    database.getClassifierDao().deleteDeclassifiedImages(
                        media.fastMap { mediaItem -> mediaItem.id },
                    )
                    database.getMediaDao().setMediaVersion(version = MediaVersion(version = mediaVersion))
                }
            }
            Result.success()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            printDebug("Error updating database: ${exception.message}")
            Result.failure()
        }
    }
}
