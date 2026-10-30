package com.streambd.iptv.ui.screens.activation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streambd.iptv.data.activation.ActivationRepository
import com.streambd.iptv.data.activation.ActivationState
import com.streambd.iptv.data.activation.PlaylistData
import com.streambd.iptv.data.epg.EpgRepository
import com.streambd.iptv.data.repo.ChannelRepository
import com.streambd.iptv.data.repo.PlaylistSync
import com.streambd.iptv.data.repo.SyncException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

@HiltViewModel
class ActivationViewModel @Inject constructor(
    private val repo: ActivationRepository,
    private val sync: PlaylistSync,
    private val channelRepo: ChannelRepository,
    private val epgRepo: EpgRepository,
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
        _code.value = raw.filter(Char::isDigit).take(12)
        _error.value = null
    }

    fun clearError() {
        _error.value = null
    }

    fun activate() {
        val activationCode = _code.value.trim()
        if (activationCode.isEmpty() || _busy.value) return
        _busy.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                repo.activate(activationCode)
                    .onSuccess { active ->
                        // নতুন: অ্যাক্টিভেশন সফল হলে শুধুমাত্র সার্ভারের (providerId = 0L) পুরোনো প্লেলিস্ট মুছবে
                        // ইউজারের নিজের প্লেলিস্টগুলো ডেটাবেসে থেকে যাবে।
                        try {
                            channelRepo.clearProviderData(0L) // আগে ছিল clearAll()
                            epgRepo.clear() 
                        } catch (e: Exception) {
                            // ডেটা ক্লিয়ার করতে সমস্যা হলেও অ্যাক্টিভেশন সফল থাকবে
                        }
                        
                        _success.value = active
                    }
                    .onFailure { _error.value = it.message ?: "Activation failed" }
            } finally {
                _busy.value = false
            }
        }
    }

    /**
     * লোকাল সেটআপ: আগে লিংক নামিয়ে যাচাই ও ইমপোর্ট করে, তারপর সেভ করে।
     */
    fun activateWithPlaylistUrl(playlistUrl: String, epgUrl: String) {
        if (_busy.value) return
        val playlist = playlistUrl.trim()
        val epg = epgUrl.trim()
        if (playlist.toHttpUrlOrNull() == null || (epg.isNotEmpty() && epg.toHttpUrlOrNull() == null)) {
            _error.value = "Enter a valid link starting with http:// or https://"
            return
        }

        _busy.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                val playlistData = PlaylistData(name = "Local Playlist", m3uUrl = playlist, epgUrl = epg)
                
                val result = sync.syncAll(
                    playlists = listOf(playlistData),
                    preferDiscovered = epg.isBlank()
                )

                try {
                    epgRepo.clear()
                } catch (e: Exception) {
                    // ইগনোর
                }

                _success.value = repo.activateLocally(playlist, result.epgUrl)
            } catch (e: SyncException) {
                _error.value = e.message
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = e.message ?: "Could not save playlist link"
            } finally {
                _busy.value = false
            }
        }
    }

    fun consumeSuccess() {
        _success.value = null
    }
}
