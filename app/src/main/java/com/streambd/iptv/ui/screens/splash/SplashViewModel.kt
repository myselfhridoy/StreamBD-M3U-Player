package com.streambd.iptv.ui.screens.splash

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streambd.iptv.data.activation.ActivationRepository
import com.streambd.iptv.data.activation.ActivationState
import com.streambd.iptv.data.repo.ChannelRepository
import com.streambd.iptv.data.repo.PlaylistSync
import com.streambd.iptv.data.repo.SyncException
import com.streambd.iptv.data.settings.SettingsStore
import com.streambd.iptv.util.LaunchState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

sealed interface SplashNav {
    data object ToActivation : SplashNav
    data object ToHome : SplashNav
    data class ToLive(val channelId: Long) : SplashNav
}

data class SplashFailure(val message: String)

private const val TAG = "Splash"

@HiltViewModel
class SplashViewModel @Inject constructor(
    private val activation: ActivationRepository,
    private val repo: ChannelRepository,
    private val sync: PlaylistSync,
    private val launchState: LaunchState,
    private val settings: SettingsStore,
) : ViewModel() {

    private val _nav = MutableStateFlow<SplashNav?>(null)
    val nav: StateFlow<SplashNav?> = _nav.asStateFlow()

    private val _status = MutableStateFlow("Starting…")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _failure = MutableStateFlow<SplashFailure?>(null)
    val failure: StateFlow<SplashFailure?> = _failure.asStateFlow()

    private var job: Job? = null

    init {
        start()
    }

    fun retry() = start()

    /** প্লেলিস্ট/কোড বদলাতে Activation স্ক্রিনে ফেরা। */
    fun changePlaylist() {
        viewModelScope.launch {
            activation.clear()
            _nav.value = SplashNav.ToActivation
        }
    }

    private fun start() {
        job?.cancel()
        _failure.value = null
        job = viewModelScope.launch { decide() }
    }

    private suspend fun decide() {
        _status.value = "Starting…"
        delay(700L)

        // ────────────────────────────────────────────────────────────
        // ১. লোকাল activation চেক — কোনো state নেই বা মেয়াদ শেষ হলে সরাসরি Activation
        // ────────────────────────────────────────────────────────────
        var state: ActivationState = activation.current()
        if (state !is ActivationState.Active) {
            _nav.value = SplashNav.ToActivation
            return
        }
        if (activation.isExpired(state)) {
            activation.clear()
            _nav.value = SplashNav.ToActivation
            return
        }

        // ────────────────────────────────────────────────────────────
        // ২. Server revalidation — playlist ডাউনলোডের *আগে*।
        //    কারণ: revoked হলে playlist নামিয়ে কোনো লাভ নেই।
        //    বেশিরভাগ cold start-এ intervalOn() false ফেরায় → নেটওয়ার্ক কল হয় না → দ্রুত।
        //    শুধু ৬ ঘণ্টা / ১ ঘণ্টা (trial) পার হলে আসল নেটওয়ার্ক কল হয়।
        // ────────────────────────────────────────────────────────────
        _status.value = "Checking subscription…"
        try {
            activation.revalidateIfNeeded()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // নেটওয়ার্ক সমস্যা — পুরনো state রাখি, AutoRefresher পরে retry করবে
            Log.w(TAG, "revalidate failed (continuing with local state): ${e.message}", e)
        }

        // ────────────────────────────────────────────────────────────
        // ৩. revalidation-এ state মুছে গেলে (revoked/suspended/expired) → Activation
        //    এছাড়া playlistUrl/epgUrl server-এ বদলালে সেটাও এখানে পড়া হয়
        // ────────────────────────────────────────────────────────────
        state = activation.current()
        if (state !is ActivationState.Active) {
            Log.i(TAG, "activation cleared after revalidation → Activation screen")
            _nav.value = SplashNav.ToActivation
            return
        }
        if (activation.isExpired(state)) {
            activation.clear()
            _nav.value = SplashNav.ToActivation
            return
        }

        // ────────────────────────────────────────────────────────────
        // ৪. Playlist download (শুধু চ্যানেল না থাকলে)
        // ────────────────────────────────────────────────────────────
        var epgUrl = state.epgUrl

        if (!repo.hasContent()) {
            if (state.playlistUrl.isBlank()) {
                _failure.value = SplashFailure("No playlist link is saved for this activation.")
                return
            }
            _status.value = "Downloading playlist…"
            try {
                val synced = sync.syncPlaylist(state.playlistUrl)
                epgUrl = sync.chooseEpg(state.epgUrl, synced.epgUrls, activation.isLocal(state))
                if (epgUrl != state.epgUrl) activation.updateEpgUrl(epgUrl)
            } catch (e: SyncException) {
                _failure.value = SplashFailure(e.message ?: "Could not load the playlist.")
                return
            }
        }

        // ────────────────────────────────────────────────────────────
        // ৫. EPG sync (ব্যর্থ হলেও এগোবে — শুধু Guide ফাঁকা থাকবে)
        // ────────────────────────────────────────────────────────────
        if (epgUrl.isNotBlank() && sync.isEpgDue()) {
            _status.value = "Updating TV guide…"
            try {
                sync.syncEpg(epgUrl)
            } catch (e: SyncException) {
                Log.w(TAG, "EPG sync failed: ${e.message}")
            }
        }

        // ────────────────────────────────────────────────────────────
        // ৬. Navigate
        // ────────────────────────────────────────────────────────────
        val resumeId = if (launchState.consumeBoot()) settings.lastLiveChannel() else -1L
        _nav.value = if (resumeId > 0L && repo.hasContent()) {
            SplashNav.ToLive(resumeId)
        } else {
            SplashNav.ToHome
        }
    }
}
