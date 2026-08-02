package com.dot.gallery.core

import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import com.dot.gallery.feature_node.data.model.Media
import com.dot.gallery.feature_node.data.repository.MediaRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

internal class MediaHandlerTrashTest {
    @Test
    fun mixedVolumeTrashAndRestoreNeverFallBackToPermanentDeletion() {
        runTest {
            val repository = mockk<MediaRepository>()
            val launcher = mockk<ActivityResultLauncher<IntentSenderRequest>>()
            val handler = MediaHandlerImpl(
                repository = repository,
                context = mockk(),
                workManager = mockk(),
            )
            val selection = listOf(mockk<Media.UriMedia>(), mockk<Media.UriMedia>())
            coEvery { repository.trashMedia(launcher, selection, any()) } returns true

            for (trash in listOf(true, false)) {
                assertTrue(handler.trashMedia(result = launcher, mediaList = selection, trash = trash))
                coVerify(exactly = 1) { repository.trashMedia(launcher, selection, trash) }
            }
            coVerify(exactly = 0) { repository.deleteMedia<Media>(any(), any()) }
        }
    }
}
