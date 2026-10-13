package com.streambd.iptv.ui.screens.activation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streambd.iptv.data.activation.ActivationRepository
import com.streambd.iptv.data.activation.ActivationState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.inject.Inject

@HiltViewModel
class ActivationViewModel @Inject constructor(
    private val repo: ActivationRepository,
) : ViewModel() {

    private val _code = MutableStateFlow("")
    val code: StateFlow<String> = _code.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _success = MutableStateFlow<ActivationState.Active?>(null)
    val success: StateFlow<ActivationState.Active?> = _success.asStateFlow()

    fun setCode(raw: String) {
        _code.value = raw.filter(Char::isLetterOrDigit).take(24)
        _error.value = null
    }

    fun activate() {
        val activationCode = _code.value.trim()
        if (activationCode.isEmpty() || _busy.value) return
        _busy.value = true
        _error.value = null
        viewModelScope.launch {
            repo.activate(activationCode)
                .onSuccess { _success.value = it }
                .onFailure { _error.value = it.message ?: "Activation failed" }
            _busy.value = false
        }
    }

    fun activateWithPlaylistUrl(playlistUrl: String, epgUrl: String) {
        if (_busy.value) return
        val playlist = playlistUrl.trim()
        val epg = epgUrl.trim()
        if (playlist.toHttpUrlOrNull() == null || (epg.isNotEmpty() && epg.toHttpUrlOrNull() == null)) {
            _error.value = "Enter a valid HTTP(S) URL"
            return
        }

        _busy.value = true
        _error.value = null
        viewModelScope.launch {
            runCatching { repo.activateLocally(playlist, epg) }
                .onSuccess { _success.value = it }
                .onFailure { _error.value = it.message ?: "Could not save playlist URL" }
            _busy.value = false
        }
    }

    fun consumeSuccess() {
        _success.value = null
    }
}