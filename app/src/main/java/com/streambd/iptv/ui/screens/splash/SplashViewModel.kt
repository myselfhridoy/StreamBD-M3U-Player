package com.streambd.iptv.ui.screens.splash

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streambd.iptv.data.activation.ActivationRepository
import com.streambd.iptv.data.activation.ActivationState
import com.streambd.iptv.data.epg.EpgRepository
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
    private val epgRepo: EpgRepository, // নতুন: পুরোনো EPG মুছতে
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

    fun changePlaylist() {
        viewModelScope.launch {
            clearLocalData()
            _nav.value = SplashNav.ToActivation
        }
    }

    private fun start() {
        job?.cancel()
        _failure.value = null
        job = viewModelScope.launch { decide() }
    }

    // নতুন হেল্পার ফাংশন: অ্যাক্টিভেশন না থাকলে বা এক্সপায়ার হলে সব লোকাল ডেটা মুছে ফেলা হবে
    private suspend fun clearLocalData() {
        try {
            activation.clear()
            repo.clearAll()
            epgRepo.clear()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear local data: ${e.message}", e)
        }
    }

    private suspend fun decide() {
        _status.value = "Starting…"
        delay(700L)

        var state: ActivationState = activation.current()
        if (state !is ActivationState.Active) {
            clearLocalData()
            _nav.value = SplashNav.ToActivation
            return
        }
        if (activation.isExpired(state)) {
            clearLocalData()
            _nav.value = SplashNav.ToActivation
            return
        }

        _status.value = "Checking subscription…"
        try {
            activation.revalidateIfNeeded()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "revalidate failed (continuing with local state): ${e.message}", e)
        }

        state = activation.current()
        if (state !is ActivationState.Active) {
            Log.i(TAG, "activation cleared after revalidation → Activation screen")
            clearLocalData()
            _nav.value = SplashNav.ToActivation
            return
        }
        if (activation.isExpired(state)) {
            clearLocalData()
            _nav.value = SplashNav.ToActivation
            return
        }

        // ────────────────────────────────────────────────────────────
        // ৪. Playlist download (মাল্টিপল প্লেলিস্ট)
        // ────────────────────────────────────────────────────────────
        if (!repo.hasContent()) {
            if (state.playlists.isEmpty()) {
                _failure.value = SplashFailure("No playlist link is saved for this activation.")
                return
            }
            
            _status.value = "Downloading playlists…"
            try {
                // নতুন: syncAll দিয়ে সব প্লেলিস্ট একসাথে ডাউনলোড
                val result = sync.syncAll(
                    playlists = state.playlists,
                    preferDiscovered = activation.isLocal(state)
                )
                val firstEpg = state.playlists.firstOrNull()?.epgUrl ?: ""
                if (result.epgUrl.isNotBlank() && result.epgUrl != firstEpg) {
                    activation.updateEpgUrl(result.epgUrl)
                }
            } catch (e: SyncException) {
                _failure.value = SplashFailure(e.message ?: "Could not load the playlists.")
                return
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
