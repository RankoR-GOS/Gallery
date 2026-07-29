package com.dot.gallery.core.workers

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.graphics.createBitmap
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.dot.gallery.core.Resource
import com.dot.gallery.core.ml.ImageEmbeddingGenerator
import com.dot.gallery.core.ml.ImageEmbeddingSession
import com.dot.gallery.core.ml.ModelStatus
import com.dot.gallery.core.sandbox.MediaPreviewDecoder
import com.dot.gallery.feature_node.data.model.AiMediaAnalysisPreferences
import com.dot.gallery.feature_node.data.model.ImageEmbedding
import com.dot.gallery.feature_node.data.model.Media.UriMedia
import com.dot.gallery.feature_node.data.repository.AiMediaAnalysisRepository
import com.dot.gallery.feature_node.data.repository.MediaRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@Config(application = Application::class)
@RunWith(RobolectricTestRunner::class)
internal class SearchIndexerUpdaterWorkerTest {

    @Test
    fun disabledAnalysis_skipsMediaAndModelAccess() {
        runTest {
            val dependencies = dependencies(analysisEnabled = false)

            val result = buildWorker(dependencies = dependencies).doWork()

            assertEquals(indexingSuccess(changedCount = 0), result)
            verify(exactly = 0) { dependencies.mediaRepository.getCompleteMedia() }
            verify(exactly = 0) { dependencies.embeddingGenerator.status }
            coVerify(exactly = 0) {
                dependencies.previewDecoder.decode(uri = any(), mimeType = any(), isVideo = any())
            }
        }
    }

    @Test
    fun mediaDelta_invalidatesChangedDataAndIndexesEachChangedItemOnce() {
        runTest {
            val unchangedMedia = media(id = 1L, timestamp = 10L)
            val changedMedia = media(id = 2L, timestamp = 20L)
            val newMedia = media(id = 3L, timestamp = 30L)
            val dependencies = dependencies()
            every { dependencies.mediaRepository.getCompleteMedia() } returns flowOf(
                Resource.Success(listOf(unchangedMedia, changedMedia, newMedia)),
            )
            every { dependencies.mediaRepository.getImageEmbeddings() } returns flowOf(
                listOf(
                    embedding(id = 1L, date = 10L),
                    embedding(id = 2L, date = 15L),
                    embedding(id = 4L, date = 40L),
                ),
            )
            val changedBitmap = createBitmap(width = 8, height = 8)
            val newBitmap = createBitmap(width = 8, height = 8)
            coEvery {
                dependencies.previewDecoder.decode(
                    uri = changedMedia.uri,
                    mimeType = changedMedia.mimeType,
                    isVideo = false,
                )
            } returns changedBitmap
            coEvery {
                dependencies.previewDecoder.decode(
                    uri = newMedia.uri,
                    mimeType = newMedia.mimeType,
                    isVideo = false,
                )
            } returns newBitmap
            every { dependencies.embeddingSession.generate(bitmap = changedBitmap) } returns floatArrayOf(2f)
            every { dependencies.embeddingSession.generate(bitmap = newBitmap) } returns floatArrayOf(3f)

            val result = buildWorker(dependencies = dependencies).doWork()

            assertEquals(indexingSuccess(changedCount = 3), result)
            coVerify(exactly = 1) {
                dependencies.analysisRepository.removeMissingCategoryMappings(
                    validMediaIds = setOf(1L, 2L, 3L),
                )
                dependencies.analysisRepository.removeMediaData(mediaIds = setOf(4L))
                dependencies.analysisRepository.invalidateGeneratedData(mediaIds = setOf(2L, 3L))
            }
            coVerify(exactly = 2) {
                dependencies.previewDecoder.decode(uri = any(), mimeType = any(), isVideo = false)
            }
            coVerify(exactly = 1) {
                dependencies.mediaRepository.addImageEmbedding(
                    imageEmbedding = match { record ->
                        record.id == 2L && record.date == 20L &&
                            record.embedding.contentEquals(floatArrayOf(2f))
                    },
                )
                dependencies.mediaRepository.addImageEmbedding(
                    imageEmbedding = match { record ->
                        record.id == 3L && record.date == 30L &&
                            record.embedding.contentEquals(floatArrayOf(3f))
                    },
                )
            }
            assertTrue(changedBitmap.isRecycled)
            assertTrue(newBitmap.isRecycled)
        }
    }

    @Test
    fun failedDecode_isRetriedByTheNextIndexingRun() {
        runTest {
            val media = media(id = 1L, timestamp = 10L)
            val dependencies = dependencies()
            every { dependencies.mediaRepository.getCompleteMedia() } returns flowOf(
                Resource.Success(listOf(media)),
            )
            every { dependencies.mediaRepository.getImageEmbeddings() } returns flowOf(emptyList())
            coEvery {
                dependencies.previewDecoder.decode(
                    uri = media.uri,
                    mimeType = media.mimeType,
                    isVideo = false,
                )
            } returns null

            val firstResult = buildWorker(dependencies = dependencies).doWork()
            val secondResult = buildWorker(dependencies = dependencies).doWork()

            assertEquals(indexingSuccess(changedCount = 1), firstResult)
            assertEquals(indexingSuccess(changedCount = 1), secondResult)
            coVerify(exactly = 2) {
                dependencies.previewDecoder.decode(
                    uri = media.uri,
                    mimeType = media.mimeType,
                    isVideo = false,
                )
            }
            coVerify(exactly = 0) {
                dependencies.mediaRepository.addImageEmbedding(imageEmbedding = any())
            }
        }
    }

    @Test
    fun consentRevokedDuringInference_discardsTheGeneratedEmbedding() {
        runTest {
            val media = media(id = 1L, timestamp = 10L)
            val dependencies = dependencies()
            var analysisEnabled = true
            coEvery { dependencies.analysisRepository.getPreferences() } answers {
                preferences(analysisEnabled = analysisEnabled)
            }
            every { dependencies.mediaRepository.getCompleteMedia() } returns flowOf(
                Resource.Success(listOf(media)),
            )
            every { dependencies.mediaRepository.getImageEmbeddings() } returns flowOf(emptyList())
            val bitmap = createBitmap(width = 8, height = 8)
            coEvery {
                dependencies.previewDecoder.decode(
                    uri = media.uri,
                    mimeType = media.mimeType,
                    isVideo = false,
                )
            } returns bitmap
            every { dependencies.embeddingSession.generate(bitmap = bitmap) } answers {
                analysisEnabled = false
                floatArrayOf(1f)
            }

            val result = buildWorker(dependencies = dependencies).doWork()

            assertEquals(indexingSuccess(changedCount = 1), result)
            coVerify(exactly = 0) {
                dependencies.mediaRepository.addImageEmbedding(imageEmbedding = any())
            }
            assertTrue(bitmap.isRecycled)
        }
    }

    @Test
    fun cancellationDuringDecode_isPropagated() {
        runTest {
            val media = media(id = 1L, timestamp = 10L)
            val dependencies = dependencies()
            every { dependencies.mediaRepository.getCompleteMedia() } returns flowOf(
                Resource.Success(listOf(media)),
            )
            every { dependencies.mediaRepository.getImageEmbeddings() } returns flowOf(emptyList())
            coEvery {
                dependencies.previewDecoder.decode(
                    uri = media.uri,
                    mimeType = media.mimeType,
                    isVideo = false,
                )
            } throws CancellationException("cancelled")

            val thrownException = runCatching {
                buildWorker(dependencies = dependencies).doWork()
            }.exceptionOrNull()

            assertTrue(thrownException is CancellationException)
            assertEquals("cancelled", thrownException?.message)
            coVerify(exactly = 0) {
                dependencies.mediaRepository.addImageEmbedding(imageEmbedding = any())
            }
        }
    }

    private fun buildWorker(dependencies: Dependencies): SearchIndexerUpdaterWorker {
        val context: Context = RuntimeEnvironment.getApplication()
        return TestListenableWorkerBuilder<SearchIndexerUpdaterWorker>(context = context)
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ): ListenableWorker {
                        return SearchIndexerUpdaterWorker(
                            repository = dependencies.mediaRepository,
                            analysisRepository = dependencies.analysisRepository,
                            embeddingGenerator = dependencies.embeddingGenerator,
                            previewDecoder = dependencies.previewDecoder,
                            appContext = appContext,
                            workerParams = workerParameters,
                        )
                    }
                },
            )
            .build()
    }

    private fun dependencies(analysisEnabled: Boolean = true): Dependencies {
        val mediaRepository = mockk<MediaRepository>()
        val analysisRepository = mockk<AiMediaAnalysisRepository>()
        val embeddingGenerator = mockk<ImageEmbeddingGenerator>()
        val embeddingSession = mockk<ImageEmbeddingSession>()
        val previewDecoder = mockk<MediaPreviewDecoder>()
        coEvery { analysisRepository.getPreferences() } returns preferences(
            analysisEnabled = analysisEnabled,
        )
        coJustRun { analysisRepository.removeMissingCategoryMappings(validMediaIds = any()) }
        coJustRun { analysisRepository.removeMediaData(mediaIds = any()) }
        coJustRun { analysisRepository.invalidateGeneratedData(mediaIds = any()) }
        coJustRun { mediaRepository.addImageEmbedding(imageEmbedding = any()) }
        every { embeddingGenerator.status } returns MutableStateFlow(ModelStatus.READY)
        every { embeddingGenerator.openSession() } returns embeddingSession
        every { embeddingSession.close() } just Runs
        return Dependencies(
            mediaRepository = mediaRepository,
            analysisRepository = analysisRepository,
            embeddingGenerator = embeddingGenerator,
            embeddingSession = embeddingSession,
            previewDecoder = previewDecoder,
        )
    }

    private fun media(id: Long, timestamp: Long): UriMedia {
        return UriMedia(
            id = id,
            label = "image_$id.png",
            uri = Uri.parse("content://media/external/images/media/$id"),
            path = "/storage/emulated/0/Pictures/image_$id.png",
            relativePath = "Pictures",
            albumID = 1L,
            albumLabel = "Pictures",
            timestamp = timestamp,
            fullDate = "",
            mimeType = "image/png",
            favorite = 0,
            trashed = 0,
            size = 1L,
        )
    }

    private fun embedding(id: Long, date: Long): ImageEmbedding {
        return ImageEmbedding(
            id = id,
            date = date,
            embedding = floatArrayOf(id.toFloat()),
        )
    }

    private fun indexingSuccess(changedCount: Int): ListenableWorker.Result {
        return ListenableWorker.Result.success(
            workDataOf(SearchIndexerUpdaterWorker.KEY_CHANGED_COUNT to changedCount),
        )
    }

    private fun preferences(analysisEnabled: Boolean): AiMediaAnalysisPreferences {
        return AiMediaAnalysisPreferences(
            analysisEnabled = analysisEnabled,
            categoryClassificationEnabled = true,
            analysisCleanupPending = false,
            categoryCleanupPending = false,
        )
    }

    private data class Dependencies(
        val mediaRepository: MediaRepository,
        val analysisRepository: AiMediaAnalysisRepository,
        val embeddingGenerator: ImageEmbeddingGenerator,
        val embeddingSession: ImageEmbeddingSession,
        val previewDecoder: MediaPreviewDecoder,
    )
}
