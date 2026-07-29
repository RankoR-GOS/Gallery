package com.dot.gallery.feature_node.presentation.search

import android.content.Context
import android.graphics.Bitmap
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.dot.gallery.core.MediaDistributor
import com.dot.gallery.core.ml.ManagedOrtSession
import com.dot.gallery.core.ml.ModelManager
import com.dot.gallery.core.ml.ModelStatus
import com.dot.gallery.core.sandbox.MediaPreviewDecoder
import com.dot.gallery.feature_node.data.data_source.CategoryWithMediaCount
import com.dot.gallery.feature_node.domain.model.AiMediaAnalysisWorkState
import com.dot.gallery.feature_node.data.model.Media
import com.dot.gallery.feature_node.domain.model.MediaMetadataState
import com.dot.gallery.feature_node.domain.model.MediaState
import com.dot.gallery.feature_node.data.repository.MediaRepository
import com.dot.gallery.feature_node.domain.use_case.AiMediaAnalysis
import com.dot.gallery.feature_node.domain.use_case.AiMediaAnalysisSettings
import com.dot.gallery.testutil.MainDispatcherRule
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
internal class SearchViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun disablingAnalysisWhileTextInferenceIsSuspended_cancelsAndClearsSemanticSearch() {
        runTest(context = mainDispatcherRule.testDispatcher) {
            val analysis = FakeAiMediaAnalysis(
                initialSettings = AiMediaAnalysisSettings(
                    analysisEnabled = true,
                    categoryClassificationEnabled = true,
                ),
            )
            val inferenceStarted = CompletableDeferred<Unit>()
            val inferenceCancelled = CompletableDeferred<Unit>()
            val searchHelper = SuspendingSearchHelper(
                inferenceStarted = inferenceStarted,
                inferenceCancelled = inferenceCancelled,
            )
            val viewModel = createViewModel(
                analysis = analysis,
                searchHelper = searchHelper,
            )
            runCurrent()

            viewModel.setQuery(query = "mountain")
            runCurrent()
            inferenceStarted.await()

            analysis.settingsFlow.value = analysis.settingsFlow.value.copy(
                analysisEnabled = false,
            )
            runCurrent()
            inferenceCancelled.await()

            assertEquals(SearchResultsState(), viewModel.searchResultsState.value)
            assertEquals(0, searchHelper.sortCallCount)
        }
    }

    @Test
    fun disablingCategories_hidesStoredCategories() {
        runTest(context = mainDispatcherRule.testDispatcher) {
            val analysis = FakeAiMediaAnalysis(
                initialSettings = AiMediaAnalysisSettings(
                    analysisEnabled = true,
                    categoryClassificationEnabled = true,
                ),
            )
            val categories = MutableStateFlow(listOf(categoryWithMediaCount()))
            val viewModel = createViewModel(
                analysis = analysis,
                categories = categories,
            )

            assertEquals(1, viewModel.topCategories.first { items -> items.isNotEmpty() }.size)

            analysis.settingsFlow.value = analysis.settingsFlow.value.copy(
                categoryClassificationEnabled = false,
            )

            assertTrue(viewModel.topCategories.first { items -> items.isEmpty() }.isEmpty())
        }
    }

    private fun createViewModel(
        analysis: FakeAiMediaAnalysis,
        searchHelper: SearchHelper = mockk {
            every { isAvailable } returns false
        },
        categories: Flow<List<CategoryWithMediaCount>> = flowOf(emptyList()),
    ): SearchViewModel {
        val timelineMedia = MutableSharedFlow<MediaState<Media.UriMedia>>(replay = 1).apply {
            tryEmit(MediaState(isLoading = false))
        }
        val mediaDistributor = mockk<MediaDistributor>(relaxed = true) {
            every { dateFormatsFlow } returns MutableStateFlow(Triple("", "", ""))
            every { imageEmbeddingsFlow } returns MutableStateFlow(emptyList())
            every { metadataFlow } returns flowOf(MediaMetadataState())
            every { timelineMediaFlow } returns timelineMedia
        }
        val repository = mockk<MediaRepository>(relaxed = true) {
            every { getTopCategories(limit = any()) } returns categories
        }
        val workManager = mockk<WorkManager> {
            every { getWorkInfosByTagFlow(any()) } returns flowOf(emptyList<WorkInfo>())
        }
        val modelManager = mockk<ModelManager> {
            every { status } returns MutableStateFlow(ModelStatus.READY)
            every { isReady } returns true
        }
        val context = mockk<Context>(relaxed = true)

        return SearchViewModel(
            mediaDistributor = mediaDistributor,
            workManager = workManager,
            searchHelper = searchHelper,
            repository = repository,
            modelManager = modelManager,
            aiMediaAnalysis = analysis,
            previewDecoder = mockk<MediaPreviewDecoder>(relaxed = true),
            ioDispatcher = mainDispatcherRule.testDispatcher,
            context = context,
        )
    }

    private fun categoryWithMediaCount(): CategoryWithMediaCount {
        return CategoryWithMediaCount(
            id = 1L,
            name = "Nature",
            searchTerms = "nature",
            embedding = null,
            referenceImageIds = emptyList(),
            threshold = 0.2f,
            isUserCreated = false,
            isPinned = false,
            createdAt = 0L,
            updatedAt = 0L,
            mediaCount = 1,
            thumbnailMediaId = null,
        )
    }

    private class SuspendingSearchHelper(
        private val inferenceStarted: CompletableDeferred<Unit>,
        private val inferenceCancelled: CompletableDeferred<Unit>,
    ) : SearchHelper {
        var sortCallCount = 0
            private set

        override val isAvailable: Boolean = true

        override fun sortByCosineDistance(
            searchEmbedding: FloatArray,
            imageEmbeddingsList: List<FloatArray>,
            imageIdxList: List<Long>,
        ): List<Pair<Long, Float>> {
            sortCallCount++
            return emptyList()
        }

        override suspend fun getTextEmbedding(
            session: ManagedOrtSession,
            text: String,
        ): FloatArray {
            inferenceStarted.complete(Unit)
            try {
                CompletableDeferred<Unit>().await()
            } finally {
                inferenceCancelled.complete(Unit)
            }
            return floatArrayOf()
        }

        override fun setupTextSession(): ManagedOrtSession {
            return mockk(relaxed = true)
        }

        override fun setupVisionSession(): ManagedOrtSession {
            return mockk(relaxed = true)
        }

        override suspend fun getImageEmbedding(
            session: ManagedOrtSession,
            bitmap: Bitmap,
        ): FloatArray {
            return floatArrayOf()
        }
    }

    private class FakeAiMediaAnalysis(
        initialSettings: AiMediaAnalysisSettings,
    ) : AiMediaAnalysis {
        val settingsFlow = MutableStateFlow(initialSettings)

        override val settings: Flow<AiMediaAnalysisSettings> = settingsFlow
        override val workState: Flow<AiMediaAnalysisWorkState> = emptyFlow()

        override suspend fun initialize() {
        }

        override suspend fun setAnalysisEnabled(enabled: Boolean) {
            settingsFlow.value = settingsFlow.value.copy(analysisEnabled = enabled)
        }

        override suspend fun setCategoryClassificationEnabled(enabled: Boolean) {
            settingsFlow.value = settingsFlow.value.copy(categoryClassificationEnabled = enabled)
        }

        override suspend fun requestAnalysis() {
        }

        override suspend fun requestCategoryClassification() {
        }

        override suspend fun cancelCategoryClassification() {
        }
    }
}
