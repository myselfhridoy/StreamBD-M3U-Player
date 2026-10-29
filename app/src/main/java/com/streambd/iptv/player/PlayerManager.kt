package com.streambd.iptv.player

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.drm.LocalMediaDrmCallback
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.streambd.iptv.data.db.ChannelEntity
import com.streambd.iptv.data.settings.SettingsStore
import com.streambd.iptv.data.settings.TrackMode
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
import java.net.URLDecoder
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(UnstableApi::class)
@Singleton
class PlayerManager @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val settings: SettingsStore,
) {
    private companion object {
        const val DEFAULT_BUFFER_MS = 2_500
        const val MAX_RETRIES = 3
        const val USER_AGENT = "StreamBD/1.0"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var requestHeaders: Map<String, String> = emptyMap()

    private val httpFactory = DefaultHttpDataSource.Factory()
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(15_000)
        .setAllowCrossProtocolRedirects(true)

    private val dataSourceFactory = ResolvingDataSource.Factory(
        httpFactory,
        ResolvingDataSource.Resolver { dataSpec ->
            val headers = requestHeaders
            val hasUserAgent = headers.keys.any { it.equals("User-Agent", ignoreCase = true) }
            dataSpec.withAdditionalHeaders(
                if (hasUserAgent) headers else headers + ("User-Agent" to USER_AGENT),
            )
        },
    )

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private var retriesLeft = MAX_RETRIES
    private var networkRetries = 0
    private var retryJob: Job? = null
    private var restoredForMediaId: String? = null
    private var currentItem: MediaItem? = null
    private var hlsHintApplied = false

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.update { it.copy(isPlaying = isPlaying) }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            _state.update {
                it.copy(
                    isBuffering = playbackState == Player.STATE_BUFFERING || retryJob?.isActive == true,
                    duration = player.duration.coerceAtLeast(0L),
                )
            }
            if (playbackState == Player.STATE_READY) {
                retriesLeft = MAX_RETRIES
                networkRetries = 0
                retryJob?.cancel()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            handleError(error)
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            _state.update {
                it.copy(
                    videoWidth = videoSize.width,
                    videoHeight = videoSize.height,
                    videoAspect = if (videoSize.height > 0) {
                        videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                    } else {
                        0f
                    },
                )
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

    private var bufferMs = DEFAULT_BUFFER_MS

    var player: ExoPlayer = createPlayer(bufferMs)
        private set

    private fun createPlayer(bufferMs: Int): ExoPlayer {
        val minMs = bufferMs.coerceAtLeast(1_000)
        val maxMs = (bufferMs * 2).coerceAtLeast(minMs + 1_000)
        val forPlaybackMs = minOf(1_000, minMs)
        val afterRebufferMs = minOf(2_000, minMs)
        return ExoPlayer.Builder(appContext)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(minMs, maxMs, forPlaybackMs, afterRebufferMs)
                    .build(),
            )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
            .also { it.addListener(playerListener) }
    }

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
            settings.flow.collect { s ->
                _state.update { it.copy(aspectMode = s.aspectMode) }
                if (s.bufferTimeMs != bufferMs) {
                    bufferMs = s.bufferTimeMs
                    rebuildPlayer()
                }
            }
        }
    }

    private fun rebuildPlayer() {
        val item = player.currentMediaItem
        val live = player.isCurrentMediaItemLive
        val position = player.currentPosition
        val playWhenReady = player.playWhenReady
        val volume = player.volume

        player.removeListener(playerListener)
        player.release()
        player = createPlayer(bufferMs)
        restoredForMediaId = null
        player.volume = volume
        player.playWhenReady = playWhenReady
        if (item != null) {
            player.setMediaItem(item, if (live) C.TIME_UNSET else position)
            player.prepare()
        }
    }

    fun play(channel: ChannelEntity) {
        val same = _state.value.current?.id == channel.id
        if (same && _state.value.error == null && player.playbackState != Player.STATE_IDLE) return

        retryJob?.cancel()
        retriesLeft = MAX_RETRIES
        networkRetries = 0
        hlsHintApplied = false
        restoredForMediaId = null

        val (url, headers) = splitHeaders(channel.streamUrl)
        requestHeaders = headers

        _state.update {
            it.copy(
                current = channel,
                isBuffering = true,
                error = null,
                position = 0L,
                duration = 0L,
                videoWidth = 0,
                videoHeight = 0,
                videoAspect = 0f,
            )
        }

        val itemBuilder = MediaItem.Builder()
            .setUri(url)
            .setMediaId(channel.id.toString())

        // DRM চেক এবং সেটআপ (ফিক্সড)
        if (!channel.drmScheme.isNullOrBlank() && !channel.drmLicense.isNullOrBlank()) {
            val scheme = channel.drmScheme.lowercase()
            val licenseRaw = channel.drmLicense
            // header বা অন্য কিছু থাকলে | এর আগ পর্যন্ত নেওয়া
            val licenseClean = licenseRaw.substringBefore('|')

            if (scheme.contains("clearkey") && !licenseClean.startsWith("http://", ignoreCase = true) && !licenseClean.startsWith("https://", ignoreCase = true)) {
                // ClearKey raw key format (kid:key)
                val parts = licenseClean.split(":")
                if (parts.size == 2) {
                    try {
                        val kidB64 = hexToBase64Url(parts[0])
                        val keyB64 = hexToBase64Url(parts[1])
                        val jsonResponse = """{"keys":[{"kty":"oct","kid":"$kidB64","k":"$keyB64"}],"type":"temporary"}""".toByteArray()
                        val callback = LocalMediaDrmCallback(jsonResponse)
                        val drmConfig = MediaItem.DrmConfiguration.Builder(C.CLEARKEY_UUID)
                            .setMediaDrmCallback(callback)
                            .build()
                        itemBuilder.setDrmConfiguration(drmConfig)
                    } catch (e: Exception) {
                        Log.e("PlayerManager", "Failed to parse ClearKey: $licenseClean", e)
                    }
                }
            } else {
                // URL based DRM (Widevine, PlayReady, ClearKey URL)
                val uuid = when {
                    scheme.contains("widevine") -> C.WIDEVINE_UUID
                    scheme.contains("playready") -> C.PLAYREADY_UUID
                    scheme.contains("clearkey") -> C.CLEARKEY_UUID
                    else -> null
                }
                if (uuid != null) {
                    val drmConfig = MediaItem.DrmConfiguration.Builder(uuid)
                        .setLicenseUri(licenseClean)
                        .build()
                    itemBuilder.setDrmConfiguration(drmConfig)
                }
            }
        }

        val item = itemBuilder.build()
        currentItem = item
        player.setMediaItem(item)
        player.prepare()
        player.playWhenReady = true
    }

    fun retry() {
        _state.value.current?.let(::play)
    }

    fun stopAndClear() {
        retryJob?.cancel()
        requestHeaders = emptyMap()
        currentItem = null
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
                videoWidth = 0,
                videoHeight = 0,
                videoAspect = 0f,
            )
        }
    }

    fun togglePlayPause() {
        when {
            player.playbackState == Player.STATE_IDLE -> retry()
            player.isPlaying -> player.pause()
            else -> player.play()
        }
    }

    fun pause() {
        if (player.isPlaying) player.pause()
    }

    fun setFullscreen(on: Boolean) { _state.update { it.copy(isFullscreen = on) } }
    fun setAspectMode(mode: AspectMode) { _state.update { it.copy(aspectMode = mode) } }
    fun clearError() { _state.update { it.copy(error = null) } }

    private fun handleError(error: PlaybackException) {
        retryJob?.cancel()

        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            player.seekToDefaultPosition()
            player.prepare()
            return
        }

        val item = currentItem
        if (error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED &&
            !hlsHintApplied && item != null
        ) {
            hlsHintApplied = true
            val hinted = item.buildUpon().setMimeType(MimeTypes.APPLICATION_M3U8).build()
            currentItem = hinted
            player.setMediaItem(hinted)
            player.prepare()
            return
        }

        val networkDown = error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
        if (networkDown && networkRetries < 12) {
            val wait = minOf(2_000L * (networkRetries + 1), 10_000L)
            networkRetries++
            _state.update { it.copy(isBuffering = true) }
            retryJob = scope.launch {
                delay(wait)
                player.prepare()
            }
            return
        }

        val cause = error.cause
        val clientError = cause is HttpDataSource.InvalidResponseCodeException &&
            cause.responseCode in 400..499
        if (!clientError && retriesLeft > 0) {
            val attempt = MAX_RETRIES - retriesLeft
            retriesLeft--
            _state.update { it.copy(isBuffering = true) }
            retryJob = scope.launch {
                delay(1_000L shl attempt)
                player.prepare()
            }
        } else {
            _state.update { it.copy(error = describe(error), isBuffering = false) }
        }
    }

    private fun describe(error: PlaybackException): String {
        val cause = error.cause
        return when {
            cause is HttpDataSource.InvalidResponseCodeException ->
                "Stream not available (HTTP ${cause.responseCode})"
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                "Can't reach the stream. Check your connection."
            error.errorCode == PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED ->
                "DRM Error: Could not acquire license."
            error.errorCode == PlaybackException.ERROR_CODE_DRM_UNSPECIFIED ->
                "DRM Error: Unsupported DRM or invalid configuration."
            else -> "Playback failed"
        }
    }

    private fun splitHeaders(raw: String): Pair<String, Map<String, String>> {
        val bar = raw.indexOf('|')
        if (bar < 0) return raw to emptyMap()

        val headers = LinkedHashMap<String, String>()
        for (pair in raw.substring(bar + 1).split('&')) {
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            val key = pair.substring(0, eq).trim()
            val rawValue = pair.substring(eq + 1)
            val value = runCatching { URLDecoder.decode(rawValue, "UTF-8") }.getOrDefault(rawValue)
            if (key.isNotEmpty()) headers[key] = value.trim()
        }
        return raw.substring(0, bar) to headers
    }

    fun applyTrackChoice(type: Int, choice: TrackChoice) {
        val b = player.trackSelectionParameters.buildUpon()
        when (choice) {
            TrackChoice.None -> b.setTrackTypeDisabled(type, true)
            TrackChoice.Auto -> b.setTrackTypeDisabled(type, false).clearOverridesOfType(type)
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
            format.width.toString(),
            format.height.toString(),
            format.bitrate.toString()
        ).joinToString("|")
    }

    private fun currentKeys(): Pair<String?, String?> {
        val params = player.trackSelectionParameters
        val disabledTypes = params.disabledTrackTypes

        fun keyOfType(type: Int): String? {
            if (disabledTypes.contains(type)) return ""

            val hasOverride = params.overrides.keys.any { it.type == type }
            if (!hasOverride) return null

            for (group in player.currentTracks.groups) {
                if (group.type != type) continue
                val override = params.overrides[group.mediaTrackGroup] ?: continue
                for (index in override.trackIndices) {
                    if (index < group.length) {
                        return formatKey(group, index)
                    }
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

    // নতুন: Hex থেকে Base64-URL-safe কনভার্টার (ClearKey-এর জন্য)
    private fun hexToBase64Url(hex: String): String {
        val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }
}
