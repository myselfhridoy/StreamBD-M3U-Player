package com.streambd.iptv.player

import com.streambd.iptv.Brand
import android.content.Context
import android.media.MediaDrm
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
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
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import java.io.IOException
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.streambd.iptv.data.db.ChannelEntity
import com.streambd.iptv.data.db.StreamSource
import com.streambd.iptv.data.db.allSources
import com.streambd.iptv.data.repo.WatchProgressRepository
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
    private val progress: WatchProgressRepository,
) {
    private companion object {
        const val TAG = "PlayerManager"
        const val DEFAULT_BUFFER_MS = 2_500
        const val MAX_RETRIES = 3
        const val USER_AGENT = Brand.USER_AGENT
        const val STALL_MS = 15_000L // এর বেশি বাফারিং হলে ব্যাকআপ সোর্সে যাবে

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

    // ব্যাকআপ সোর্স
    private var autoBackup = true
    private var sources: List<StreamSource> = emptyList()
    private var sourceIdx = 0
    // DRM: প্রথম চেষ্টায় ClearKey=single-session, Widevine/PlayReady=multi-session; DRM এরর হলে একবার উল্টোটা
    private var drmAlt = false
    private var drmAltTried = false
    private var drmAltPending = false
    private var failoverCount = 0          // শেষ সফল চালুর পর কতবার সরা হয়েছে
    private var bufferingSince = 0L
    private val lastGoodSource = HashMap<Long, Int>() // এই সেশনে যে সোর্স চলেছিল
    private var noticeJob: Job? = null

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
                failoverCount = 0
                rememberGoodSource()
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
    // ───────── সমস্যা খোঁজার লাইভ লগ (Stream info ওভারলে দেখায়) ─────────
    private val diagBuf = ArrayDeque<String>()
    private var diagT0 = SystemClock.elapsedRealtime()

    private fun diag(msg: String) {
        val t = (SystemClock.elapsedRealtime() - diagT0) / 1000f
        val line = String.format(java.util.Locale.US, "%5.1fs %s", t, msg.replace('\n', ' ').take(150))
        synchronized(diagBuf) {
            diagBuf.addLast(line)
            while (diagBuf.size > 14) diagBuf.removeFirst()
            val snap = diagBuf.toList()
            _state.update { it.copy(diagLog = snap) }
        }
    }

    private fun diagReset() {
        synchronized(diagBuf) {
            diagBuf.clear()
            diagT0 = SystemClock.elapsedRealtime()
            _state.update { it.copy(diagLog = emptyList()) }
        }
    }

    private val analyticsListener = object : AnalyticsListener {
        override fun onDrmSessionAcquired(eventTime: AnalyticsListener.EventTime, state: Int) {
            diag("drm session acquired")
        }

        override fun onDrmKeysLoaded(eventTime: AnalyticsListener.EventTime) {
            diag("drm keys loaded")
        }

        override fun onDrmSessionManagerError(eventTime: AnalyticsListener.EventTime, error: Exception) {
            diag("drm manager error: ${error.javaClass.simpleName}: ${error.message}")
        }

        override fun onVideoCodecError(eventTime: AnalyticsListener.EventTime, videoCodecError: Exception) {
            diag("video codec error: ${videoCodecError.javaClass.simpleName}: ${videoCodecError.message}")
        }

        override fun onAudioCodecError(eventTime: AnalyticsListener.EventTime, audioCodecError: Exception) {
            diag("audio codec error: ${audioCodecError.javaClass.simpleName}: ${audioCodecError.message}")
        }

        override fun onLoadError(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData,
            error: IOException,
            wasCanceled: Boolean,
        ) {
            diag("load error ${loadEventInfo.uri.host}: ${error.javaClass.simpleName} ${error.message ?: ""}")
        }

        override fun onVideoInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?,
        ) {
            diag("video ${format.sampleMimeType} ${format.width}x${format.height} codecs=${format.codecs} drm=${format.drmInitData != null}")
        }

        override fun onAudioInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?,
        ) {
            diag("audio ${format.sampleMimeType} ch=${format.channelCount} drm=${format.drmInitData != null}")
        }

        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            diag("video decoder $decoderName")
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
            var sinceSave = 0
            while (isActive) {
                _state.update {
                    it.copy(
                        position = player.currentPosition.coerceAtLeast(0L),
                        duration = player.duration.coerceAtLeast(0L),
                    )
                }
                // প্রতি ~১০ সেকেন্ডে মুভি/এপিসোডের অগ্রগতি সেভ
                if (++sinceSave >= 20) {
                    sinceSave = 0
                    if (player.isPlaying) saveProgress()
                }
                // ১৫ সেকেন্ডের বেশি আটকে থাকলে ব্যাকআপ সোর্সে সরা
                if (sources.size > 1 && player.playWhenReady && player.playbackState == Player.STATE_BUFFERING) {
                    val now = SystemClock.elapsedRealtime()
                    if (bufferingSince == 0L) {
                        bufferingSince = now
                    } else if (now - bufferingSince >= STALL_MS) {
                        bufferingSince = 0L
                        tryFailover("stalled")
                    }
                } else {
                    bufferingSince = 0L
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
                autoBackup = s.autoBackup
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

        saveProgress() // আগের মুভির অগ্রগতি রেখে নতুনটা শুরু

        diagReset()
        sources = channel.allSources()
        // সব সোর্স ব্যর্থ হওয়ার পর Retry হলে আবার মূল সোর্স থেকে; নইলে এই সেশনে যেটা চলেছিল সেটা
        if (force && same && _state.value.error != null) lastGoodSource.remove(channel.id)
        val start = lastGoodSource[channel.id]?.takeIf { it in sources.indices } ?: 0
        failoverCount = 0
        bufferingSince = 0L
        startSource(channel, start, resumeAtMs, subtitles, notice = null)
    }

    /** [sources]-এর [idx]-তম সোর্স চালু করে। [notice] দিলে স্ক্রিনে কয়েক সেকেন্ড বার্তা দেখায়। */
    private fun startSource(
        channel: ChannelEntity,
        idx: Int,
        resumeAtMs: Long,
        subtitles: List<ExternalSubtitle>,
        notice: String?,
    ) {
        val src = sources.getOrNull(idx) ?: StreamSource(channel.streamUrl, channel.drmScheme, channel.drmLicense)
        sourceIdx = idx.coerceIn(0, (sources.size - 1).coerceAtLeast(0))

        retryJob?.cancel()
        retriesLeft = MAX_RETRIES
        networkRetries = 0
        hintIndex = 0
        restoredForMediaId = null
        lastSubtitles = subtitles
        if (drmAltPending) {
            drmAltPending = false // DRM-বিকল্প চেষ্টা: drmAlt অপরিবর্তিত
        } else {
            drmAlt = false
            drmAltTried = false
        }

        val (rawUrl, rawHeaders) = splitHeaders(src.url)

        val drmScheme = rawHeaders.entries.firstOrNull { it.key.equals("drmScheme", ignoreCase = true) }?.value
            ?: src.drmScheme
        val drmLicense = rawHeaders.entries.firstOrNull { it.key.equals("drmLicense", ignoreCase = true) }?.value
            ?: src.drmLicense

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
                sourceIndex = sourceIdx,
                sourceCount = sources.size.coerceAtLeast(1),
                sourceNotice = notice,
            )
        }
        if (notice != null) scheduleNoticeClear()

        val item = try {
            buildMediaItem(channel.id.toString(), rawUrl, cleanHeaders, drmScheme, drmLicense, subtitles)
        } catch (e: DrmSetupException) {
            Log.e(TAG, "DRM setup failed: ${e.message}")
            diag("DRM setup failed: ${e.message}")
            // এই ডিভাইসে এই DRM চলে না: ব্যাকআপ (হয়তো DRM ছাড়া) থাকলে সেটায় যাই
            if (tryFailover("drm setup")) return
            fail(e.message ?: "DRM setup failed")
            return
        } catch (e: Throwable) {
            Log.e(TAG, "Playback start failed: ${e.message}", e)
            if (tryFailover("start failed")) return
            fail("Playback failed: ${e.message}")
            return
        }

        currentItem = item
        diag("start src ${idx + 1}/${sources.size} drm=${drmScheme ?: "none"} alt=$drmAlt host=${rawUrl.substringAfter("://").substringBefore('/')}")
        applyLanguagePrefs()
        if (resumeAtMs != C.TIME_UNSET) player.setMediaItem(item, resumeAtMs) else player.setMediaItem(item)
        player.prepare()
        player.playWhenReady = true
    }

    /** ব্যর্থ বা আটকে গেলে পরের সোর্সে যায়। সম্ভব না হলে false (তখন আগের মতো রিট্রাই/এরর)। */
    private fun tryFailover(why: String): Boolean {
        val ch = _state.value.current ?: return false
        if (!autoBackup || sources.size <= 1 || failoverCount >= sources.size - 1) return false
        failoverCount++
        val next = (sourceIdx + 1) % sources.size
        Log.i(TAG, "Backup failover: source ${sourceIdx + 1} -> ${next + 1} ($why)")
        startSource(ch, next, C.TIME_UNSET, lastSubtitles, notice = "Switching to source ${next + 1} of ${sources.size}")
        return true
    }

    /** প্লেয়ারের "Source" বাটন: পরের সোর্সে যায় (শেষেরটা থেকে আবার প্রথমটায়)। */
    fun cycleSource() {
        val ch = _state.value.current ?: return
        if (sources.size <= 1) return
        failoverCount = 0
        val next = (sourceIdx + 1) % sources.size
        startSource(ch, next, C.TIME_UNSET, lastSubtitles, notice = "Source ${next + 1} of ${sources.size}")
    }

    private fun scheduleNoticeClear() {
        noticeJob?.cancel()
        noticeJob = scope.launch {
            delay(3_000L)
            _state.update { it.copy(sourceNotice = null) }
        }
    }

    /** যে সোর্স চলল তা এই সেশনের জন্য মনে রাখে: চ্যানেলে ফিরলে সরাসরি ওটা। মূলটা চললে মুছে দেয়। */
    private fun rememberGoodSource() {
        val id = _state.value.current?.id ?: return
        if (sources.size <= 1) return
        if (sourceIdx == 0) lastGoodSource.remove(id) else lastGoodSource[id] = sourceIdx
    }

    private fun isOnline(): Boolean = try {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            true
        } else {
            cm.getNetworkCapabilities(cm.activeNetwork)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        }
    } catch (e: Exception) {
        true // অনুমতি না থাকলে অফলাইন ধরব না
    }

    /**
     * প্লেলিস্ট আপডেটে চলমান চ্যানেলের URL (টোকেন) বদলে গেলে এবং প্লেয়ার আটকে/এরর অবস্থায় থাকলে
     * নতুন URL দিয়ে নিজে থেকে আবার চালায়। ঠিকঠাক চলতে থাকলে কিছু করে না, যাতে দেখার সময় স্ট্রিম না কাটে।
     */
    fun recoverWith(fresh: ChannelEntity) {
        val s = _state.value
        val cur = s.current ?: return
        if (cur.id != fresh.id || (cur.allSources() == fresh.allSources())) return
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
                        .setMultiSession(drmAlt)
                        .setPlayClearContentWithoutKey(true)
                        .build(),
                )
            } else {
                builder.setDrmConfiguration(
                    MediaItem.DrmConfiguration.Builder(uuid)
                        .setLicenseUri(license)
                        .setLicenseRequestHeaders(headers)
                        .setMultiSession(!drmAlt)
                        .setPlayClearContentWithoutKey(true)
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
        sources = emptyList()
        sourceIdx = 0

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
                sourceIndex = 0,
                sourceCount = 1,
                sourceNotice = null,
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
        saveProgress()
        retryJob?.cancel()
        requestHeaders = emptyMap()
        currentItem = null
        sources = emptyList()
        sourceIdx = 0
        failoverCount = 0
        player.stop()
        player.clearMediaItems()
        _state.update {
            it.copy(
                current = null,
                sourceIndex = 0,
                sourceCount = 1,
                sourceNotice = null,
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
            player.isPlaying -> { saveProgress(); player.pause() }
            else -> player.play()
        }
    }

    fun pause() {
        if (player.isPlaying) {
            saveProgress()
            player.pause()
        }
    }

    /** মুভি/সিরিজ হলে বর্তমান জায়গা সেভ করে (লাইভ হলে কিছু করে না)। */
    private fun saveProgress() {
        val ch = _state.value.current ?: return
        if (ch.kind != "MOVIE" && ch.kind != "SERIES") return
        if (player.isCurrentMediaItemLive) return
        val duration = player.duration
        if (duration == C.TIME_UNSET || duration <= 0L) return
        val position = player.currentPosition
        scope.launch { runCatching { progress.save(ch, position, duration) } }
    }

    fun setFullscreen(on: Boolean) { _state.update { it.copy(isFullscreen = on) } }
    fun setAspectMode(mode: AspectMode) { _state.update { it.copy(aspectMode = mode) } }
    fun clearError() { _state.update { it.copy(error = null) } }

    private fun handleError(error: PlaybackException) {
        Log.e(TAG, "Player error ${error.errorCodeName}: ${error.cause?.message}", error)
        diag("ERROR ${error.errorCodeName}${causeDetail(error)}")
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

        // DRM এরর হলে আগে একবার বিকল্প সেশন-মোডে একই সোর্স আবার চেষ্টা (কিছু ডিভাইসে একটা মোডই চলে)
        val hasDrm = item?.localConfiguration?.drmConfiguration != null
        val drmError = error.errorCode in 6000..6999 &&
            error.errorCode != PlaybackException.ERROR_CODE_DRM_SCHEME_UNSUPPORTED
        val cur = _state.value.current
        if (hasDrm && drmError && !drmAltTried && cur != null) {
            drmAltTried = true
            drmAlt = !drmAlt
            drmAltPending = true
            Log.w(TAG, "DRM error ${error.errorCodeName}: retrying with alternate session mode (alt=$drmAlt)")
            val resume = if (!player.isCurrentMediaItemLive && player.currentPosition > 5_000L) player.currentPosition else C.TIME_UNSET
            startSource(cur, sourceIdx, resume, lastSubtitles, notice = null)
            return
        }

        // ব্যাকআপ সোর্স থাকলে নিজে থেকে পরেরটায়। ডিভাইস অফলাইন হলে নয়, তখন আগের মতো নেটওয়ার্ক-রিট্রাই।
        val connectionError = error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
        if (!(connectionError && !isOnline()) && tryFailover("error ${error.errorCodeName}")) return

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
                "DRM error: could not get the license." + causeDetail(error)
            error.errorCode in 6000..6999 ->
                "DRM error (${error.errorCodeName})" + causeDetail(error)
            else -> "Playback failed (${error.errorCodeName})" + causeDetail(error)
        }
    }

    /** সমস্যা খুঁজতে: ত্রুটির সবচেয়ে ভেতরের কারণ (ছোট করে)। যেমন " [MediaDrmStateException: ...]"। */
    private fun causeDetail(error: PlaybackException): String {
        var t: Throwable? = error.cause
        var last: Throwable? = null
        var depth = 0
        while (t != null && depth++ < 8) {
            last = t
            t = t.cause
        }
        val root = last ?: return ""
        val msg = (root.message ?: "").replace('\n', ' ').trim().take(110)
        return " [${root.javaClass.simpleName}${if (msg.isNotEmpty()) ": $msg" else ""}]"
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
