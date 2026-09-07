package com.dot.gallery.feature_node.data.data_source

import android.app.Application
import androidx.room.Room
import com.dot.gallery.feature_node.data.model.MediaMetadata
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@Config(application = Application::class)
@RunWith(RobolectricTestRunner::class)
internal class MetadataDaoTest {
    @Test
    fun extraction_preservesVideoDescriptionsButAllowsImageDescriptionRemoval() {
        runTest {
            val database = Room.inMemoryDatabaseBuilder(
                RuntimeEnvironment.getApplication(), InternalDatabase::class.java,
            ).allowMainThreadQueries().build()
            try {
                val dao = database.getMetadataDao()
                dao.upsertImageDescription(
                    mediaId = 1L, description = "My video", imageWidth = 0, imageHeight = 0,
                )
                assertTrue(dao.getProcessedMediaIds().first().isEmpty())
                val extracted = MediaMetadata(
                    mediaId = 1L,
                    imageDescription = null,
                    dateTimeOriginal = null,
                    manufacturerName = null,
                    modelName = null,
                    aperture = null,
                    exposureTime = null,
                    iso = null,
                    gpsLatitude = null,
                    gpsLongitude = null,
                    gpsLocationName = null,
                    gpsLocationNameCountry = null,
                    gpsLocationNameCity = null,
                    imageWidth = 0,
                    imageHeight = 0,
                    imageResolutionX = null,
                    imageResolutionY = null,
                    resolutionUnit = null,
                    durationMs = 1000L,
                    videoWidth = 1920,
                    videoHeight = 1080,
                    frameRate = null,
                    bitRate = null,
                    isNightMode = false,
                    isPanorama = false,
                    isPhotosphere = false,
                    isLongExposure = false,
                    isMotionPhoto = false,
                )
                dao.addMetadata(mediaMetadata = extracted, isVideo = true)
                assertEquals("My video", dao.getCoreMetadata(id = 1L)?.imageDescription)
                assertEquals(listOf(1L), dao.getProcessedMediaIds().first())

                dao.upsertImageDescription(
                    mediaId = 1L, description = "Edited", imageWidth = 0, imageHeight = 0,
                )
                dao.addMetadata(mediaMetadata = extracted, isVideo = true)
                assertEquals("Edited", dao.getCoreMetadata(id = 1L)?.imageDescription)

                dao.upsertImageDescription(
                    mediaId = 2L, description = "Photo caption", imageWidth = 10, imageHeight = 10,
                )
                dao.addMetadata(mediaMetadata = extracted.copy(mediaId = 2L), isVideo = false)
                assertNull(dao.getCoreMetadata(id = 2L)?.imageDescription)
            } finally {
                database.close()
            }
        }
    }
}
