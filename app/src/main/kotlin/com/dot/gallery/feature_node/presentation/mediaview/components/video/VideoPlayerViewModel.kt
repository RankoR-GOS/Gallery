package com.dot.gallery.feature_node.presentation.mediaview.components.video

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.annotation.OptIn
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import com.dot.gallery.feature_node.data.model.Media
import com.dot.gallery.feature_node.data.util.getUri
import com.dot.gallery.feature_node.presentation.util.printDebug
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * A ViewModel that owns an ExoPlayer instance keyed by a media.id.
 * This survives configuration changes, reducing churn and preserving playback state.
 *
 * You still pass the current Media object from the Composable; if it changes (same id, new instance),
 * the ViewModel reuses the existing player unless the underlying uri actually changed.
 *
 * Persisted (process death) fields via SavedStateHandle:
 * - positionMs
 * - wasPlaying
 */
@HiltViewModel(assistedFactory = VideoPlayerViewModel.Factory::class)
class VideoPlayerViewModel @AssistedInject constructor(
    @param:ApplicationContext
    private val appContext: Context,
    private val savedStateHandle: SavedStateHandle = SavedStateHandle(),
    @Assisted("media") private val media: Media,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(@Assisted("media") media: Media): VideoPlayerViewModel
    }

    data class PlaybackState(
        val ready: Boolean = false,
        val durationMs: Long = 0L,
        val positionMs: Long = 0L,
        val bufferedPercent: Int = 0,
        val frameRate: Float = 60f,
        val isPlaying: Boolean = false
    )

    private var initialSeekApplied = false
    private var progressJob: Job? = null

    // Public immutable flow
    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state

    // Owned player — exposed as StateFlow so Compose recomposes on player recreation
    private val _playerFlow = MutableStateFlow(createExoPlayer())
    val playerFlow: StateFlow<ExoPlayer> = _playerFlow
    var player: ExoPlayer
        get() = _playerFlow.value
        set(value) { _playerFlow.value = value }

    init {
        restoreFromSavedState()
        prepareMedia()
        startProgressLoop()
    }

    @OptIn(UnstableApi::class)
    private fun createExoPlayer(): ExoPlayer {
        return ExoPlayer.Builder(appContext).build().apply {
            setSeekParameters(SeekParameters.EXACT)
            repeatMode = Player.REPEAT_MODE_ONE
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        markReady()
                    }
                    updateDuration(duration)
                }

                override fun onEvents(player: Player, events: Player.Events) {
                    // Duration might update after dynamic metadata
                    updateDuration(player.duration)
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    _state.update { it.copy(isPlaying = isPlaying) }
                }

                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    // Keep isPlaying consistent (ExoPlayer may report false until ready)
                    if (!playWhenReady) {
                        _state.update { it.copy(isPlaying = false) }
                    }
                }
            })
        }
    }

    private fun restoreFromSavedState() {
        val positionRestore = savedStateHandle.get<Long>(KEY_POSITION) ?: 0L
        val wasPlayingRestore = savedStateHandle.get<Boolean>(KEY_PLAYING) ?: false
        if (positionRestore > 0) {
            // Seek will be applied after player becomes ready
            _state.update { it.copy(positionMs = positionRestore, isPlaying = wasPlayingRestore) }
        }
    }

    private fun prepareMedia() {
        setAndPrepare(media.getUri(), media.mimeType)
        retrieveFrameRate()
    }

    private fun setAndPrepare(uri: Uri, mime: String?) {
        val existingUri = player.currentMediaItem?.localConfiguration?.uri
        if (existingUri == uri) {
            // Already set
            return
        }
        initialSeekApplied = false
        val item = MediaItem.Builder()
            .setUri(uri)
            .setMimeType(mime)
            .build()
        player.setMediaItem(item)
        player.prepare()
    }

    private fun markReady() {
        if (!_state.value.ready) {
            _state.update { it.copy(ready = true) }
            // Apply initial seek only once
            if (!initialSeekApplied && _state.value.positionMs > 0) {
                player.seekTo(_state.value.positionMs)
                if (_state.value.isPlaying) {
                    player.play()
                }
                initialSeekApplied = true
            }
        }
    }

    private fun updateDuration(duration: Long) {
        if (duration > 0 && duration != _state.value.durationMs) {
            _state.update { it.copy(durationMs = duration) }
        }
    }

    @OptIn(UnstableApi::class)
    private fun startProgressLoop() {
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            while (isActive) {
                val p = player
                if (!p.isReleased) {
                    val pos = p.currentPosition
                    val buffered = p.bufferedPercentage
                    _state.update {
                        it.copy(
                            positionMs = pos,
                            bufferedPercent = buffered
                        )
                    }
                }
                delay(1.seconds / 30) // ~30fps updates
            }
        }
    }

    private fun retrieveFrameRate() {
        viewModelScope.launch(Dispatchers.IO) {
            val fps = try {
                MediaMetadataRetriever().use { r ->
                    r.setDataSource(appContext, media.getUri())
                    r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                        ?.toFloat()
                        ?: 60f
                }
            } catch (_: Exception) {
                60f
            }
            _state.update { it.copy(frameRate = fps) }
        }
    }

    fun togglePlay() {
        if (player.isPlaying) player.pause() else player.play()
    }

    fun seekTo(positionMs: Long) {
        player.seekTo(positionMs)
        _state.update { it.copy(positionMs = positionMs) }
    }

    fun setUserPlayWhenReady(play: Boolean, canAutoPlay: Boolean) {
        // Apply user intended state respecting autoplay rules externally
        val targetPlay = if (!play && canAutoPlay) false else play
        player.playWhenReady = targetPlay
        if (targetPlay) player.play() else player.pause()
        _state.update { it.copy(isPlaying = targetPlay) }
    }

    fun applyAudioFocusPreference(wantsFocus: Boolean) {
        val usage = if (wantsFocus) C.USAGE_MEDIA else C.USAGE_NOTIFICATION
        val contentType =
            if (wantsFocus) C.AUDIO_CONTENT_TYPE_MOVIE else C.AUDIO_CONTENT_TYPE_SONIFICATION
        val attrs = AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(contentType)
            .build()
        player.setAudioAttributes(attrs, /* handleAudioFocus = */ wantsFocus)
    }

    @OptIn(UnstableApi::class)
    fun reattachFromComposition() {
        if (player.isReleased) {
            printDebug("Reattached to composition ${media.id}'s video")
            runCatching {
                _state.update { it.copy(ready = false) }
                initialSeekApplied = false
                player = createExoPlayer()
                restoreFromSavedState()
                prepareMedia()
                startProgressLoop()
                val isPlaying = savedStateHandle.get<Boolean>(KEY_PLAYING)
                player.playWhenReady = isPlaying ?: _state.value.isPlaying

                printDebug("Video after reattach: playWhenReady: ${player.playWhenReady}")
            }
        } else {
            printDebug("Skipped re-attaching to composition ${media.id}'s video. We are already there")
        }
    }

    @OptIn(UnstableApi::class)
    fun detachFromComposition() {
        printDebug("Cleared from composition ${media.id}'s video")
        progressJob?.cancel()
        progressJob = null
        runCatching {
            if (!player.isReleased) {
                savedStateHandle[KEY_POSITION] = player.currentPosition
                savedStateHandle[KEY_PLAYING] = player.isPlaying
                player.release()
            }
        }
    }

    @OptIn(UnstableApi::class)
    override fun onCleared() {
        progressJob?.cancel()
        progressJob = null
        try {
            if (!player.isReleased) {
                // Persist position & play state
                savedStateHandle[KEY_POSITION] = player.currentPosition
                savedStateHandle[KEY_PLAYING] = player.isPlaying
                player.release()
            }
        } catch (_: Throwable) {
        }
        super.onCleared()
    }

    companion object {
        private const val KEY_POSITION = "positionMs"
        private const val KEY_PLAYING = "wasPlaying"
    }
}
