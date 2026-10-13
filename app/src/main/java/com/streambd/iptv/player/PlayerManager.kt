package com.streambd.iptv.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.streambd.iptv.data.db.ChannelEntity
import com.streambd.iptv.data.settings.TrackMode
import com.streambd.iptv.data.settings.SettingsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single shared ExoPlayer for preview + fullscreen. Compose screens only
 * observe [state] and call the small command surface below.
 */
@Singleton
class PlayerManager @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val settings: SettingsStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val httpFactory = DefaultHttpDataSource.Factory()
        .setUserAgent("StreamBD/1.0")
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(15_000)
        .setAllowCrossProtocolRedirects(true)

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.update { it.copy(isPlaying = isPlaying, isBuffering = false) }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            _state.update {
                it.copy(
                    isBuffering = playbackState == Player.STATE_BUFFERING,
                    duration = player.duration.coerceAtLeast(0L),
                )
            }
            if (playbackState == Player.STATE_READY) retriesLeft = 3
        }

        override fun onPlayerError(error: PlaybackException) {
            if (retriesLeft > 0) {
                retriesLeft--
                player.prepare()
            } else {
                _state.update {
                    it.copy(error = "Playback failed", isBuffering = false)
                }
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            val video = ArrayList<Tracks.Group>()
            val audio = ArrayList<Tracks.Group>()
            for (g in tracks.groups) when (g.type) {
                C.TRACK_TYPE_VIDEO -> video += g
                C.TRACK_TYPE_AUDIO -> audio += g
            }
            val keys = currentKeys()
            _state.update {
                it.copy(tracks = TrackInfo(video, audio, keys.first, keys.second))
            }
            restoreIfNewMedia(tracks)
        }
    }

    private fun createPlayer(bufferMs: Int = 10_000): ExoPlayer {
        val minMs = bufferMs.coerceAtLeast(1_000)
        val maxMs = (bufferMs * 2).coerceAtLeast(minMs + 1_000)
        return ExoPlayer.Builder(appContext)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(minMs, maxMs, 1_000, 2_000)
                    .build(),
            )
            .build()
            .also { it.addListener(playerListener) }
    }

    var player: ExoPlayer = createPlayer()
        private set

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private var retriesLeft = 3
    private var volumeHideJob: Job? = null
    private var restoredForMediaId: String? = null

    init {
        scope.launch {
            while (isActive) {
                _state.update {
                    it.copy(
                        position = player.currentPosition.coerceAtLeast(0L),
                        duration = player.duration.coerceAtLeast(0L),
                    )
                }
                delay(500L)
            }
        }

        scope.launch {
            var lastBufferMs = -1
            settings.flow.collect { s ->
                _state.update { it.copy(aspectMode = s.aspectMode) }
                if (s.bufferTimeMs != lastBufferMs) {
                    lastBufferMs = s.bufferTimeMs
                    val currentMediaItem = player.currentMediaItem
                    val currentPosition = player.currentPosition
                    val playWhenReady = player.playWhenReady
                    val isPlaying = player.isPlaying
                    val volume = player.volume

                    player.removeListener(playerListener)
                    player.release()
                    player = createPlayer(s.bufferTimeMs)
                    restoredForMediaId = null
                    player.volume = volume
                    player.playWhenReady = playWhenReady
                    if (currentMediaItem != null) {
                        player.setMediaItem(currentMediaItem, currentPosition)
                        player.prepare()
                        if (isPlaying) player.play()
                    }
                    player.addListener(playerListener)
                }
            }
        }
    }

    /* ------------------------------ commands ------------------------------ */

    fun play(channel: ChannelEntity) {
        if (_state.value.current?.id == channel.id) return
        retriesLeft = 3
        _state.update {
            it.copy(
                current = channel,
                isBuffering = true,
                error = null,
                position = 0L,
                duration = 0L,
            )
        }
        val item = MediaItem.Builder()
            .setUri(channel.streamUrl)
            .setMediaId(channel.id.toString())
            .build()
        player.setMediaItem(item)
        player.prepare()
        player.playWhenReady = true
    }

    fun stopAndClear() {
        player.stop()
        player.clearMediaItems()
        _state.update {
            it.copy(
                current = null,
                isPlaying = false,
                isBuffering = false,
                position = 0L,
                duration = 0L,
                error = null,
            )
        }
    }

    fun togglePlayPause() {
        if (player.isPlaying) player.pause() else player.play()
    }

    fun setFullscreen(on: Boolean) { _state.update { it.copy(isFullscreen = on) } }
    fun setAspectMode(mode: AspectMode) { _state.update { it.copy(aspectMode = mode) } }
    fun clearError() { _state.update { it.copy(error = null) } }

    fun adjustVolume(delta: Int) {
        val newVol = (_state.value.volumeLevel + delta * 0.05f).coerceIn(0f, 1f)
        player.volume = newVol
        _state.update { it.copy(volumeLevel = newVol, volumeOverlayVisible = true) }
        volumeHideJob?.cancel()
        volumeHideJob = scope.launch {
            delay(1500L)
            _state.update { it.copy(volumeOverlayVisible = false) }
        }
    }

    fun applyTrackChoice(type: Int, choice: TrackChoice) {
        val b = player.trackSelectionParameters.buildUpon()
        when (choice) {
            TrackChoice.None ->
                b.setTrackTypeDisabled(type, true)

            TrackChoice.Auto ->
                b.setTrackTypeDisabled(type, false).clearOverridesOfType(type)

            is TrackChoice.Specific -> {
                val override = TrackSelectionOverride(
                    choice.group.mediaTrackGroup, choice.index
                )
                b.setTrackTypeDisabled(type, false).setOverrideForType(override)
            }
        }
        player.trackSelectionParameters = b.build()
        persist(type, choice)
        _state.update { it.copy(tracks = it.tracks.withCurrent(currentKeys())) }
    }

    private fun persist(type: Int, choice: TrackChoice) {
        scope.launch {
            when (type) {
                C.TRACK_TYPE_VIDEO -> when (choice) {
                    TrackChoice.None -> settings.setVideoTrack(TrackMode.NONE, "")
                    TrackChoice.Auto -> settings.setVideoTrack(TrackMode.AUTO, "")
                    is TrackChoice.Specific -> settings.setVideoTrack(
                        TrackMode.SPECIFIC,
                        formatKey(choice.group, choice.index),
                    )
                }
                C.TRACK_TYPE_AUDIO -> when (choice) {
                    TrackChoice.None -> settings.setAudioTrack(TrackMode.NONE, "")
                    TrackChoice.Auto -> settings.setAudioTrack(TrackMode.AUTO, "")
                    is TrackChoice.Specific -> settings.setAudioTrack(
                        TrackMode.SPECIFIC,
                        formatKey(choice.group, choice.index),
                    )
                }
            }
        }
    }

    private fun formatKey(group: Tracks.Group, index: Int): String {
        val format = group.getTrackFormat(index)
        return listOf(
            format.id.orEmpty(),
            format.sampleMimeType.orEmpty(),
            format.language.orEmpty(),
            format.label.orEmpty(),
        ).joinToString("|")
    }

    private fun currentKeys(): Pair<String?, String?> {
        val disabledTypes = player.trackSelectionParameters.disabledTrackTypes
        fun keyOfType(type: Int): String? {
            if (disabledTypes.contains(type)) return ""
            for (group in player.currentTracks.groups) {
                if (group.type != type) continue
                for (index in 0 until group.length) {
                    if (group.isTrackSelected(index)) return formatKey(group, index)
                }
            }
            return null
        }
        return keyOfType(C.TRACK_TYPE_VIDEO) to keyOfType(C.TRACK_TYPE_AUDIO)
    }

    private fun restoreIfNewMedia(tracks: Tracks) {
        val mediaId = player.currentMediaItem?.mediaId ?: return
        if (mediaId == restoredForMediaId) return
        if (tracks.groups.none {
                it.type == C.TRACK_TYPE_VIDEO || it.type == C.TRACK_TYPE_AUDIO
            }
        ) return
        restoredForMediaId = mediaId
        scope.launch {
            val prefs = settings.trackPrefs.first()
            applyStored(C.TRACK_TYPE_VIDEO, prefs.videoMode, prefs.videoKey)
            applyStored(C.TRACK_TYPE_AUDIO, prefs.audioMode, prefs.audioKey)
        }
    }

    private fun applyStored(type: Int, mode: TrackMode, key: String) {
        val builder = player.trackSelectionParameters.buildUpon()
        when (mode) {
            TrackMode.NONE -> builder.setTrackTypeDisabled(type, true)
            TrackMode.AUTO -> builder.setTrackTypeDisabled(type, false).clearOverridesOfType(type)
            TrackMode.SPECIFIC -> {
                for (group in player.currentTracks.groups) {
                    if (group.type != type) continue
                    for (index in 0 until group.length) {
                        if (formatKey(group, index) == key) {
                            builder.setTrackTypeDisabled(type, false)
                                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
                            player.trackSelectionParameters = builder.build()
                            return
                        }
                    }
                }
                builder.setTrackTypeDisabled(type, false).clearOverridesOfType(type)
            }
        }
        player.trackSelectionParameters = builder.build()
    }
}
