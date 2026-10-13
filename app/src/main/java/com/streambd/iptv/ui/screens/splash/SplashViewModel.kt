package com.streambd.iptv.ui.screens.splash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streambd.iptv.data.activation.ActivationRepository
import com.streambd.iptv.data.activation.ActivationState
import com.streambd.iptv.data.epg.EpgRepository
import com.streambd.iptv.data.repo.ChannelRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject

sealed interface SplashNav {
    data object ToActivation : SplashNav
    data object ToHome : SplashNav
}

@HiltViewModel
class SplashViewModel @Inject constructor(
    private val activation: ActivationRepository,
    private val repo: ChannelRepository,
    private val epg: EpgRepository,
    private val http: OkHttpClient,
) : ViewModel() {

    private val _nav = MutableStateFlow<SplashNav?>(null)
    val nav: StateFlow<SplashNav?> = _nav.asStateFlow()

    private val _status = MutableStateFlow("Starting…")
    val status: StateFlow<String> = _status.asStateFlow()

    init {
        viewModelScope.launch { decide() }
    }

    private suspend fun decide() {
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

        if (!repo.hasContent() && state.playlistUrl.isNotBlank()) {
            _status.value = "Downloading Updates…"
            runCatching {
                val body = withContext(Dispatchers.IO) {
                    val request = Request.Builder().url(state.playlistUrl).build()
                    http.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            throw IllegalStateException("Playlist server returned ${response.code}")
                        }
                        response.body?.string().orEmpty()
                    }
                }
                if (body.isNotBlank()) repo.import(body)
            }
        }

        if (!epg.hasContent() && state.epgUrl.isNotBlank()) {
            _status.value = "Downloading EPG…"
            runCatching { epg.importFromUrl(state.epgUrl) }
        }

        viewModelScope.launch { runCatching { activation.revalidateIfNeeded() } }
        _nav.value = SplashNav.ToHome
    }
}