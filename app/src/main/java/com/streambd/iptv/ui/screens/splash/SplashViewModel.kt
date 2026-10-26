package com.streambd.iptv.ui.screens.splash

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streambd.iptv.data.activation.ActivationRepository
import com.streambd.iptv.data.activation.ActivationState
import com.streambd.iptv.data.repo.ChannelRepository
import com.streambd.iptv.data.repo.PlaylistSync
import com.streambd.iptv.data.repo.SyncException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface SplashNav {
    data object ToActivation : SplashNav
    data object ToHome : SplashNav
}

data class SplashFailure(val message: String)

private const val TAG = "Splash"

@HiltViewModel
class SplashViewModel @Inject constructor(
    private val activation: ActivationRepository,
    private val repo: ChannelRepository,
    private val sync: PlaylistSync,
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

        val state = activation.current()
        if (state !is ActivationState.Active) {
            _nav.value = SplashNav.ToActivation
            return
        }
        if (activation.isExpired(state)) {
            activation.clear()
            _nav.value = SplashNav.ToActivation
            return
        }

        // চ্যানেল না থাকলে প্লেলিস্ট আনতেই হবে, ব্যর্থ হলে এগোনো যাবে না
        if (!repo.hasContent()) {
            if (state.playlistUrl.isBlank()) {
                _failure.value = SplashFailure("No playlist link is saved for this activation.")
                return
            }
            _status.value = "Downloading playlist…"
            try {
                sync.syncPlaylist(state.playlistUrl)
            } catch (e: SyncException) {
                _failure.value = SplashFailure(e.message ?: "Could not load the playlist.")
                return
            }
        }

        // EPG ব্যর্থ হলে অ্যাপ চালু হবে, শুধু Guide ফাঁকা থাকবে
        if (state.epgUrl.isNotBlank() && sync.isEpgDue()) {
            _status.value = "Updating TV guide…"
            try {
                sync.syncEpg(state.epgUrl)
            } catch (e: SyncException) {
                Log.w(TAG, "EPG sync failed: ${e.message}")
            }
        }

        viewModelScope.launch { runCatching { activation.revalidateIfNeeded() } }
        _nav.value = SplashNav.ToHome
    }
}
