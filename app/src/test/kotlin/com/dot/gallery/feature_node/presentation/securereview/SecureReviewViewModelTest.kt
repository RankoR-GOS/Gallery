package com.dot.gallery.feature_node.presentation.securereview

import android.net.Uri
import com.dot.gallery.feature_node.data.repository.SecureReviewMediaRepository
import com.dot.gallery.feature_node.data.model.Media
import com.dot.gallery.feature_node.data.model.securereview.AuthorizedSecureReviewRequest
import com.dot.gallery.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
internal class SecureReviewViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun launchRequest_loadsMediaIntoUiState() {
        runTest(context = mainDispatcherRule.testDispatcher) {
            val media = persistentListOf(mockk<Media.UriMedia>())
            val repository = mockRepository(media = media)
            val viewModel = SecureReviewViewModel(repository = repository)

            viewModel.onLaunchRequest(request = request)
            advanceUntilIdle()

            val state = viewModel.uiState.value as SecureReviewUiState.Ready
            assertSame(media, state.media)
        }
    }

    @Test
    fun duplicateLaunchRequest_doesNotReloadMedia() {
        runTest(context = mainDispatcherRule.testDispatcher) {
            val repository = mockRepository(
                media = persistentListOf(mockk<Media.UriMedia>()),
            )
            val viewModel = SecureReviewViewModel(repository = repository)

            viewModel.onLaunchRequest(request = request)
            viewModel.onLaunchRequest(request = request)
            advanceUntilIdle()

            coVerify(exactly = 1) {
                repository.loadMedia(request = request)
            }
        }
    }

    @Test
    fun loadFailure_emitsFinishEffect() {
        runTest(context = mainDispatcherRule.testDispatcher) {
            val viewModel = SecureReviewViewModel(
                repository = mockRepository(media = null),
            )

            viewModel.onLaunchRequest(request = request)
            advanceUntilIdle()

            assertEquals(SecureReviewEffect.Finish, viewModel.effects.first())
        }
    }

    @Test
    fun incompleteLoad_emitsFinishEffect() {
        runTest(context = mainDispatcherRule.testDispatcher) {
            val twoUriRequest = AuthorizedSecureReviewRequest(
                uris = persistentListOf(
                    Uri.parse("content://example/image/1"),
                    Uri.parse("content://example/image/2"),
                ),
            )
            val viewModel = SecureReviewViewModel(
                repository = mockRepository(
                    media = persistentListOf(mockk<Media.UriMedia>()),
                ),
            )

            viewModel.onLaunchRequest(request = twoUriRequest)
            advanceUntilIdle()

            assertEquals(SecureReviewEffect.Finish, viewModel.effects.first())
        }
    }

    @Test
    fun repositoryException_emitsFinishEffect() {
        runTest(context = mainDispatcherRule.testDispatcher) {
            val repository = mockk<SecureReviewMediaRepository>()
            coEvery { repository.loadMedia(request = request) } throws IllegalStateException()
            val viewModel = SecureReviewViewModel(repository = repository)

            viewModel.onLaunchRequest(request = request)
            advanceUntilIdle()

            assertEquals(SecureReviewEffect.Finish, viewModel.effects.first())
        }
    }

    @Test
    fun closeClick_emitsFinishEffect() {
        runTest(context = mainDispatcherRule.testDispatcher) {
            val viewModel = SecureReviewViewModel(repository = mockk(relaxed = true))

            viewModel.onCloseClick()
            advanceUntilIdle()

            assertEquals(SecureReviewEffect.Finish, viewModel.effects.first())
        }
    }

    private fun mockRepository(
        media: ImmutableList<Media.UriMedia>?,
    ): SecureReviewMediaRepository {
        return mockk {
            coEvery { loadMedia(request = any()) } returns media
        }
    }

    companion object {
        private val request = AuthorizedSecureReviewRequest(
            uris = persistentListOf(Uri.parse("content://example/image/1")),
        )
    }
}
