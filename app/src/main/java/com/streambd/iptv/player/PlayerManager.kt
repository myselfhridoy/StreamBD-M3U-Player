package com.streambd.iptv.player

import android.content.Context
import android.media.MediaDrm
import android.net.Uri
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
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
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

/** ভিডিওর সাথে বাইরে থেকে জোড়া সাবটাইটেল (SRT/VTT/ASS)। */
data class ExternalSubtitle(val url: String, val label: String?, val language: String?)

@OptIn(UnstableApi::class)
@Singleton
class PlayerManager @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val settings: SettingsStore,
) {
    private companion object {
        const val TAG = "PlayerManager"
        const val DEFAULT_BUFFER_MS = 2_500
        const val MAX_RETRIES = 3
        const val USER_AGENT = "StreamBD/1.0"

        val FORMAT_HINTS = listOf(
            MimeTypes.APPLICATION_M3U8,
            MimeTypes.APPLICATION_MPD,
            MimeTypes.APPLICATION_SS,
        )
    }

    private class DrmSetupException(message: String) : Exception(message)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var requestHeaders: Map<String, String> = emptyMap()

    private val httpFactory = DefaultHttpDataSource.Factory()
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(15_000)
        .setAllowCrossProtocolRedirects(true)

    // DefaultDataSource: http(s) ছাড়াও rtmp:// (media3-datasource-rtmp থাকলে), file, content সামলায়
    private val dataSourceFactory = ResolvingDataSource.Factory(
        DefaultDataSource.Factory(appContext, httpFactory),
        ResolvingDataSource.Resolver { dataSpec ->
            val headers = requestHeaders
            val hasUserAgent = headers.keys.any { it.equals("User-Agent", ignoreCase = true) }
            dataSpec.withAdditionalHeaders(
                if (hasUserAgent) headers else headers + ("User-Agent" to USER_AGENT),
            )
        },
    )

    private val drmProvider = AppDrmProvider(dataSourceFactory)

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private var retriesLeft = MAX_RETRIES
    private var networkRetries = 0
    private var retryJob: Job? = null
    private var restoredForMediaId: String? = null
    private var currentItem: MediaItem? = null
    private var hintIndex = 0
    private var lastSubtitles: List<ExternalSubtitle> = emptyList()
    private var prefAudioLang = ""
    private var prefSubLang = ""

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

    // স্ট্রিম তথ্য ওভারলের জন্য: কোন ডিকোডার চলছে ও কত ফ্রেম বাদ পড়ছে
    private val analyticsListener = object : AnalyticsListener {
        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            _state.update { it.copy(videoDecoder = decoderName) }
        }

        override fun onAudioDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            _state.update { it.copy(audioDecoder = decoderName) }
        }

        override fun onDroppedVideoFrames(
            eventTime: AnalyticsListener.EventTime,
            droppedFrames: Int,
            elapsedMs: Long,
        ) {
            _state.update { it.copy(droppedFrames = it.droppedFrames + droppedFrames) }
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
        // ★ FFmpeg অডিও এক্সটেনশন (AAR যোগ করা থাকলে) শুধু তখনই কাজে লাগে যখন ডিভাইসে AC-3/E-AC-3/DTS-এর
        //   নিজস্ব ডিকোডার নেই। না থাকলে এই সেটিংয়ে কিছুই বদলায় না।
        val renderersFactory = DefaultRenderersFactory(appContext)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)
        return ExoPlayer.Builder(appContext, renderersFactory)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(dataSourceFactory)
                    .setDrmSessionManagerProvider(drmProvider),
            )
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
            .also {
                it.addListener(playerListener)
                it.addAnalyticsListener(analyticsListener)
            }
    }

    init {
        Log.i(TAG, "FFmpeg audio decoders available: ${androidx.media3.decoder.ffmpeg.FfmpegLibrary.isAvailable()}")
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
                // ★ নতুন: seekStepMs আপডেট
                _state.update { 
                    it.copy(
                        aspectMode = s.aspectMode, 
                        seekStepMs = s.seekStepMs.toLong() 
                    ) 
                }
                if (s.audioLang != prefAudioLang || s.subtitleLang != prefSubLang) {
                    prefAudioLang = s.audioLang
                    prefSubLang = s.subtitleLang
                    applyLanguagePrefs()
                }
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
        applyLanguagePrefs()
        restoredForMediaId = null
        player.volume = volume
        player.playWhenReady = playWhenReady
        if (item != null) {
            player.setMediaItem(item, if (live) C.TIME_UNSET else position)
            player.prepare()
        }
    }

    /**
     * [force] = true হলে একই চ্যানেল চললেও নতুন করে শুরু হয় (নতুন URL/টোকেন বসাতে)।
     * [resumeAtMs] দিলে মুভি/এপিসোড ওই জায়গা থেকে চলে।
     */
    fun play(
        channel: ChannelEntity,
        force: Boolean = false,
        resumeAtMs: Long = C.TIME_UNSET,
        subtitles: List<ExternalSubtitle> = emptyList(),
    ) {
        val same = _state.value.current?.id == channel.id
        if (!force && same && _state.value.error == null && player.playbackState != Player.STATE_IDLE) return

        retryJob?.cancel()
        retriesLeft = MAX_RETRIES
        networkRetries = 0
        hintIndex = 0
        restoredForMediaId = null
        lastSubtitles = subtitles

        val (rawUrl, rawHeaders) = splitHeaders(channel.streamUrl)

        val drmScheme = rawHeaders.entries.firstOrNull { it.key.equals("drmScheme", ignoreCase = true) }?.value
            ?: channel.drmScheme
        val drmLicense = rawHeaders.entries.firstOrNull { it.key.equals("drmLicense", ignoreCase = true) }?.value
            ?: channel.drmLicense

        val cleanHeaders = rawHeaders.filterKeys {
            !it.equals("drmScheme", ignoreCase = true) && !it.equals("drmLicense", ignoreCase = true)
        }
        requestHeaders = cleanHeaders

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
                videoDecoder = null,
                audioDecoder = null,
                droppedFrames = 0,
                drmInfo = drmInfoFor(drmScheme),
            )
        }

        val item = try {
            buildMediaItem(channel.id.toString(), rawUrl, cleanHeaders, drmScheme, drmLicense, subtitles)
        } catch (e: DrmSetupException) {
            Log.e(TAG, "DRM setup failed: ${e.message}")
            fail(e.message ?: "DRM setup failed")
            return
        } catch (e: Throwable) {
            Log.e(TAG, "Playback start failed: ${e.message}", e)
            fail("Playback failed: ${e.message}")
            return
        }

        currentItem = item
        applyLanguagePrefs()
        if (resumeAtMs != C.TIME_UNSET) player.setMediaItem(item, resumeAtMs) else player.setMediaItem(item)
        player.prepare()
        player.playWhenReady = true
    }

    /**
     * প্লেলিস্ট আপডেটে চলমান চ্যানেলের URL (টোকেন) বদলে গেলে এবং প্লেয়ার আটকে/এরর অবস্থায় থাকলে
     * নতুন URL দিয়ে নিজে থেকে আবার চালায়। ঠিকঠাক চলতে থাকলে কিছু করে না, যাতে দেখার সময় স্ট্রিম না কাটে।
     */
    fun recoverWith(fresh: ChannelEntity) {
        val s = _state.value
        val cur = s.current ?: return
        if (cur.id != fresh.id || cur.streamUrl == fresh.streamUrl) return
        if (s.error == null && !(s.isBuffering && !s.isPlaying)) return
        val resume = if (!player.isCurrentMediaItemLive && player.currentPosition > 5_000L) {
            player.currentPosition
        } else {
            C.TIME_UNSET
        }
        play(fresh, force = true, resumeAtMs = resume, subtitles = lastSubtitles)
    }

    private fun buildMediaItem(
        mediaId: String,
        url: String,
        headers: Map<String, String>,
        drmScheme: String?,
        drmLicense: String?,
        subtitles: List<ExternalSubtitle> = emptyList(),
    ): MediaItem {
        val builder = MediaItem.Builder().setUri(url).setMediaId(mediaId)
        guessMimeType(url)?.let { builder.setMimeType(it) }
        if (subtitles.isNotEmpty()) {
            builder.setSubtitleConfigurations(
                subtitles.map { s ->
                    MediaItem.SubtitleConfiguration.Builder(Uri.parse(s.url))
                        .setMimeType(subtitleMime(s.url))
                        .setLanguage(s.language)
                        .setLabel(s.label)
                        .build()
                },
            )
        }

        if (!drmScheme.isNullOrBlank() && !drmLicense.isNullOrBlank()) {
            val scheme = drmScheme.lowercase()
            val uuid = when {
                scheme.contains("widevine") -> C.WIDEVINE_UUID
                scheme.contains("playready") -> C.PLAYREADY_UUID
                scheme.contains("clearkey") -> C.CLEARKEY_UUID
                else -> null
            } ?: throw DrmSetupException("Unknown DRM type: $drmScheme")

            if (!MediaDrm.isCryptoSchemeSupported(uuid)) {
                throw DrmSetupException("This device does not support $drmScheme DRM.")
            }

            val license = drmLicense.substringBefore('|').trim()
            val isUrl = license.startsWith("http://", ignoreCase = true) ||
                license.startsWith("https://", ignoreCase = true)

            if (uuid == C.CLEARKEY_UUID && !isUrl) {
                val json = ClearKeys.toLicenseJson(license)
                    ?: throw DrmSetupException("Invalid ClearKey. Use kid:key (32 hex characters each).")
                drmProvider.setClearKeys(mediaId, json)
                builder.setDrmConfiguration(
                    MediaItem.DrmConfiguration.Builder(C.CLEARKEY_UUID)
                        .setMultiSession(true)
                        .build(),
                )
            } else {
                builder.setDrmConfiguration(
                    MediaItem.DrmConfiguration.Builder(uuid)
                        .setLicenseUri(license)
                        .setLicenseRequestHeaders(headers)
                        .setMultiSession(true)
                        .build(),
                )
            }
        }
        return builder.build()
    }

    /**
     * সেটিংসের পছন্দের অডিও/সাবটাইটেল ভাষা প্লেয়ারে বসায়। প্রতিটি নতুন স্ট্রিম শুরুতে চলে, তাই আগের
     * স্ট্রিমে হাতে বন্ধ করা সাবটাইটেল পরের স্ট্রিমে আটকে থাকে না।
     */
    private fun applyLanguagePrefs() {
        val b = player.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguage(prefAudioLang.takeIf { it.isNotBlank() })
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
        when (prefSubLang) {
            "" -> b.setPreferredTextLanguage(null).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            "off" -> b.setPreferredTextLanguage(null).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            else -> b.setPreferredTextLanguage(prefSubLang)
                .setSelectUndeterminedTextLanguage(true)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
        }
        player.trackSelectionParameters = b.build()
    }

    /** ওভারলে দেখানোর জন্য: DRM-এর ধরন ও এই ডিভাইস সেটা সাপোর্ট করে কিনা। */
    private fun drmInfoFor(scheme: String?): String? {
        if (scheme.isNullOrBlank()) return null
        val s = scheme.lowercase()
        val (name, uuid) = when {
            s.contains("widevine") -> "Widevine" to C.WIDEVINE_UUID
            s.contains("playready") -> "PlayReady" to C.PLAYREADY_UUID
            s.contains("clearkey") -> "ClearKey" to C.CLEARKEY_UUID
            else -> return scheme
        }
        val ok = runCatching { MediaDrm.isCryptoSchemeSupported(uuid) }.getOrDefault(false)
        return "$name (device: ${if (ok) "supported" else "NOT supported"})"
    }

    private fun subtitleMime(url: String): String {
        val path = url.lowercase().substringBefore('?').substringBefore('#')
        return when {
            path.endsWith(".vtt") -> MimeTypes.TEXT_VTT
            path.endsWith(".ass") || path.endsWith(".ssa") -> MimeTypes.TEXT_SSA
            else -> MimeTypes.APPLICATION_SUBRIP
        }
    }

    private fun guessMimeType(url: String): String? {
        val lower = url.lowercase()
        val path = lower.substringBefore('?').substringBefore('#')
        return when {
            path.endsWith(".mpd") || "format=mpd" in lower || "type=mpd" in lower -> MimeTypes.APPLICATION_MPD
            path.endsWith(".ism") || ".ism/manifest" in path -> MimeTypes.APPLICATION_SS
            else -> null
        }
    }

    private fun fail(message: String) {
        _state.update { it.copy(error = message, isBuffering = false) }
    }

    fun playCatchup(channel: ChannelEntity, catchupUrl: String) {
        if (catchupUrl.isBlank()) return

        retryJob?.cancel()
        retriesLeft = MAX_RETRIES
        networkRetries = 0
        hintIndex = 0
        restoredForMediaId = null

        val (rawUrl, rawHeaders) = splitHeaders(catchupUrl)
        val cleanHeaders = rawHeaders.filterKeys {
            !it.equals("drmScheme", ignoreCase = true) && !it.equals("drmLicense", ignoreCase = true)
        }
        requestHeaders = cleanHeaders

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
                videoDecoder = null,
                audioDecoder = null,
                droppedFrames = 0,
                drmInfo = null,
            )
        }

        try {
            val builder = MediaItem.Builder()
                .setUri(rawUrl)
                .setMediaId(channel.id.toString())
            guessMimeType(rawUrl)?.let { builder.setMimeType(it) }
            val item = builder.build()

            currentItem = item
            applyLanguagePrefs()
            player.setMediaItem(item)
            player.prepare()
            player.playWhenReady = true
        } catch (e: Throwable) {
            Log.e(TAG, "Catchup start failed: ${e.message}", e)
            fail("Catchup failed: ${e.message}")
        }
    }

    fun retry() {
        _state.value.current?.let { play(it, force = true, subtitles = lastSubtitles) }
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
        Log.e(TAG, "Player error ${error.errorCodeName}: ${error.cause?.message}", error)
        retryJob?.cancel()

        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            player.seekToDefaultPosition()
            player.prepare()
            return
        }

        val item = currentItem
        val formatGuessWrong = error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
            error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ||
            error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED
        if (formatGuessWrong && item != null) {
            val tried = item.localConfiguration?.mimeType
            while (hintIndex < FORMAT_HINTS.size && FORMAT_HINTS[hintIndex] == tried) hintIndex++
            if (hintIndex < FORMAT_HINTS.size) {
                val hinted = item.buildUpon().setMimeType(FORMAT_HINTS[hintIndex++]).build()
                currentItem = hinted
                player.setMediaItem(hinted)
                player.prepare()
                return
            }
        }

        if (error.errorCode in 6000..6999) {
            _state.update { it.copy(error = describe(error), isBuffering = false) }
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
                "DRM error: could not get the license."
            error.errorCode in 6000..6999 ->
                "DRM error (${error.errorCodeName})"
            else -> "Playback failed (${error.errorCodeName})"
        }
    }

    /**
     * স্ট্রিম URL-এর পরের হেডার পার্স করে। দুই ধরনই চলে:
     *  - `url|Referer:https://x.com/|User-Agent:Mozilla/5.0 ...`  (প্লেলিস্টে সাধারণ)
     *  - `url|User-Agent=Mozilla/5.0&Referer=https%3A%2F%2Fx.com%2F`  (Kodi-স্টাইল)
     */
    private fun splitHeaders(raw: String): Pair<String, Map<String, String>> {
        val bar = raw.indexOf('|')
        if (bar < 0) return raw to emptyMap()

        val headers = LinkedHashMap<String, String>()
        for (segment in raw.substring(bar + 1).split('|')) {
            val eq = segment.indexOf('=')
            val colon = segment.indexOf(':')
            if (colon > 0 && (eq < 0 || colon < eq)) {
                // "Key: Value"
                val key = segment.substring(0, colon).trim()
                val value = segment.substring(colon + 1).trim()
                if (key.isNotEmpty() && key.none { it.isWhitespace() }) headers[key] = value
            } else {
                // "Key=Value&Key2=Value2"
                for (pair in segment.split('&')) {
                    val e = pair.indexOf('=')
                    if (e <= 0) continue
                    val key = pair.substring(0, e).trim()
                    val rawValue = pair.substring(e + 1)
                    val value = runCatching { URLDecoder.decode(rawValue, "UTF-8") }.getOrDefault(rawValue)
                    if (key.isNotEmpty()) headers[key] = value.trim()
                }
            }
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
}
