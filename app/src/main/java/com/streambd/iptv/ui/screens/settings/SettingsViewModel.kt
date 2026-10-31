package com.streambd.iptv.ui.screens.settings

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streambd.iptv.data.activation.ActivationRepository
import com.streambd.iptv.data.activation.ActivationState
import com.streambd.iptv.data.db.ProviderDao
import com.streambd.iptv.data.epg.EpgRepository
import com.streambd.iptv.data.repo.ChannelRepository
import com.streambd.iptv.data.repo.PlaylistSync
import com.streambd.iptv.data.repo.SyncException
import com.streambd.iptv.data.settings.AppSettings
import com.streambd.iptv.data.settings.SettingsStore
import com.streambd.iptv.player.AspectMode
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

private const val TAG = "SettingsVM"

sealed interface RefreshState {
    data object Idle : RefreshState
    data object Running : RefreshState
    data class Done(val channels: Int, val programs: Int, val epgError: String?) : RefreshState
    data class Failed(val message: String) : RefreshState
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsStore,
    private val repo: ChannelRepository,
    private val activation: ActivationRepository,
    private val epg: EpgRepository,
    private val sync: PlaylistSync,
    private val providerDao: ProviderDao,
    @ApplicationContext private val ctx: Context,
) : ViewModel() {

    val state: StateFlow<AppSettings> =
        settings.flow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    val activationState: StateFlow<ActivationState> =
        activation.state.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            ActivationState.NotActivated,
        )

    private val _refresh = MutableStateFlow<RefreshState>(RefreshState.Idle)
    val refreshState: StateFlow<RefreshState> = _refresh.asStateFlow()

    private val _lastUpdateTime = MutableStateFlow(0L)
    val lastUpdateTime: StateFlow<Long> = _lastUpdateTime.asStateFlow()

    init {
        loadLastUpdateTime()
    }

    fun loadLastUpdateTime() {
        _lastUpdateTime.value = sync.lastPlaylistUpdateTime()
    }

    fun setStartAfterReboot(v: Boolean) = viewModelScope.launch { settings.setStartAfterReboot(v) }
    fun setShowEpg(v: Boolean) = viewModelScope.launch { settings.setShowEpgOnSelect(v) }
    fun setAspect(m: AspectMode) = viewModelScope.launch { settings.setAspectMode(m) }
    fun setEpgShift(mins: Int) = viewModelScope.launch { settings.setEpgTimeShiftMins(mins) }
    fun setBuffer(ms: Int) = viewModelScope.launch { settings.setBufferTime(ms) }

    suspend fun verifyPin(pin: String) = settings.verifyPin(pin)
    fun setPin(pin: String) = viewModelScope.launch { settings.setPin(pin) }

    /**
     * সংরক্ষিত লিংক থেকে প্লেলিস্ট ও EPG আবার আনে। (সার্ভার + ইউজারের সব প্লেলিস্ট)
     */
    fun refreshPlaylist() {
        if (_refresh.value is RefreshState.Running) return
        viewModelScope.launch {
            _refresh.value = RefreshState.Running
            
            var totalChannels = 0
            var totalPrograms = 0
            var epgError: String? = null
            var hasError = false

            val active = activation.current() as? ActivationState.Active
            
            // ১. সার্ভারের প্লেলিস্ট আপডেট করা (যদি থাকে)
            if (active != null && active.playlists.isNotEmpty()) {
                try {
                    val result = sync.syncAll(
                        playlists = active.playlists,
                        preferDiscovered = activation.isLocal(active),
                        isPremium = true
                    )
                    totalChannels += result.channels
                    totalPrograms += result.programs
                    if (result.epgError != null) epgError = result.epgError
                    
                    val firstEpg = active.playlists.firstOrNull()?.epgUrl ?: ""
                    if (result.epgUrl.isNotBlank() && result.epgUrl != firstEpg) {
                        activation.updateEpgUrl(result.epgUrl)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Server playlists sync failed: ${e.message}")
                    hasError = true
                }
            }

            // ২. ইউজারের নিজের সব প্লেলিস্ট আপডেট করা
            val providerEpgUrls = linkedSetOf<String>()
            var providerChannels = 0
            try {
                val userProviders = providerDao.getAll()
                for (p in userProviders) {
                    try {
                        val synced = sync.syncPlaylist(p.url, providerId = p.id)
                        totalChannels += synced.channels
                        providerChannels += synced.channels
                        providerEpgUrls.addAll(synced.epgUrls)
                    } catch (e: Exception) {
                        Log.w(TAG, "User playlist ${p.name} sync failed: ${e.message}")
                        hasError = true
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch user providers: ${e.message}")
                hasError = true
            }

            // ২.৫ সব চ্যানেল সেভ হওয়ার পর Providers-এর EPG একবার ডাউনলোড
            if (providerChannels > 0) {
                if (providerEpgUrls.isEmpty()) {
                    if (epgError == null && totalPrograms == 0) {
                        epgError = "Your provider playlist has no EPG link (x-tvg-url)."
                    }
                } else {
                    try {
                        totalPrograms += sync.syncEpg(providerEpgUrls.joinToString("\n"))
                        epgError = null // এখন গাইড এসেছে
                    } catch (e: SyncException) {
                        epgError = e.message
                    } catch (e: Exception) {
                        epgError = e.message ?: "EPG download failed."
                    }
                }
            }

            loadLastUpdateTime()

            // রেজাল্ট চেক
            if (totalChannels == 0 && hasError) {
                _refresh.value = RefreshState.Failed("Could not refresh playlists. Check your connection.")
            } else if (totalChannels == 0 && !hasError) {
                _refresh.value = RefreshState.Failed("No channels found to refresh.")
            } else {
                _refresh.value = RefreshState.Done(totalChannels, totalPrograms, epgError)
            }
        }
    }

    fun consumeRefresh() {
        _refresh.value = RefreshState.Idle
    }

    /** Wipes caches (Coil/OkHttp live under cacheDir). Returns bytes freed. */
    suspend fun clearDiskCache(): Long = withContext(Dispatchers.IO) {
        val freed = ctx.cacheDir.walkTopDown().filter(File::isFile).sumOf(File::length)
        ctx.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        ctx.externalCacheDir?.listFiles()?.forEach { it.deleteRecursively() }
        freed
    }

    /** Full reset: caches + DB + DataStore. Caller navigates to Activation. */
    fun resetApplication(onDone: () -> Unit) = viewModelScope.launch {
        withContext(Dispatchers.IO) { clearDiskCache() }
        repo.clearAll()
        epg.clear()
        activation.clear()
        settings.clearAll()
        sync.clearState()
        _lastUpdateTime.value = 0L
        onDone()
    }
}
