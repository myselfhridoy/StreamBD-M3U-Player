package com.streambd.iptv.ui.screens.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streambd.iptv.data.activation.ActivationRepository
import com.streambd.iptv.data.activation.ActivationState
import com.streambd.iptv.data.epg.EpgRepository
import com.streambd.iptv.data.repo.ChannelRepository
import com.streambd.iptv.data.settings.AppSettings
import com.streambd.iptv.data.settings.SettingsStore
import com.streambd.iptv.player.AspectMode
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsStore,
    private val repo: ChannelRepository,
    private val activation: ActivationRepository,
    private val epg: EpgRepository,
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

    fun setStartAfterReboot(v: Boolean) = viewModelScope.launch { settings.setStartAfterReboot(v) }
    fun setShowEpg(v: Boolean) = viewModelScope.launch { settings.setShowEpgOnSelect(v) }
    fun setAspect(m: AspectMode) = viewModelScope.launch { settings.setAspectMode(m) }
    fun setEpgShift(mins: Int) = viewModelScope.launch { settings.setEpgTimeShiftMins(mins) }
    fun setBuffer(ms: Int) = viewModelScope.launch { settings.setBufferTime(ms) }

    suspend fun verifyPin(pin: String) = settings.verifyPin(pin)
    fun setPin(pin: String) = viewModelScope.launch { settings.setPin(pin) }

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
        onDone()
    }
}
