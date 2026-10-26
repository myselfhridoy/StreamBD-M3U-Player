package com.streambd.iptv.ui.screens.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streambd.iptv.data.db.ChannelEntity
import com.streambd.iptv.data.db.GroupEntity
import com.streambd.iptv.data.epg.EpgRepository
import com.streambd.iptv.data.epg.NowNext
import com.streambd.iptv.data.repo.ChannelRepository
import com.streambd.iptv.data.settings.SettingsStore
import com.streambd.iptv.player.AspectMode
import com.streambd.iptv.player.ChannelJumpController
import com.streambd.iptv.player.LockController
import com.streambd.iptv.player.PlayerManager
import com.streambd.iptv.player.PlayerUiState
import com.streambd.iptv.player.TrackChoice
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

const val LIVE_GROUP_FAVORITES: Long = -1L

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LiveViewModel @Inject constructor(
    private val repo: ChannelRepository,
    private val playerManager: PlayerManager,
    private val jumpController: ChannelJumpController,
    private val lockController: LockController,
    private val settingsStore: SettingsStore,
    private val epg: EpgRepository,
) : ViewModel() {

    private val selectedGroupId = MutableStateFlow<Long?>(null)
    private val focusedChannelId = MutableStateFlow<Long?>(null)

    val groups: StateFlow<List<GroupEntity>> =
        repo.liveGroups().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val allChannels: StateFlow<List<ChannelEntity>> =
        repo.liveChannels().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val channels: StateFlow<List<ChannelEntity>> =
        combine(allChannels, selectedGroupId) { all, groupId ->
            when (groupId) {
                null -> all
                LIVE_GROUP_FAVORITES -> all.filter { it.isFavorite }
                else -> all.filter { it.groupId == groupId }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val focusedChannel: StateFlow<ChannelEntity?> =
        combine(channels, focusedChannelId) { list, id ->
            list.firstOrNull { it.id == id } ?: list.firstOrNull()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val selectedGroup: StateFlow<Long?> = selectedGroupId.asStateFlow()

    val playerState: StateFlow<PlayerUiState> = playerManager.state
    val jumpDisplay: StateFlow<String> = jumpController.display
    val sessionUnlocked: StateFlow<Boolean> = lockController.unlocked
    private val _nowNext = MutableStateFlow<NowNext?>(null)
    val nowNext: StateFlow<NowNext?> = _nowNext.asStateFlow()
    val player get() = playerManager

    init {
        viewModelScope.launch {
            jumpController.commits.collect { raw ->
                val number = raw.toIntOrNull() ?: return@collect
                val target = repo.liveChannels().first().firstOrNull { it.number == number }
                    ?: return@collect
                selectedGroupId.value = null
                focusChannel(target.id)
            }
        }
        viewModelScope.launch {
            combine(playerManager.state, settingsStore.flow) { player, settings ->
                Triple(player.current?.tvgId, settings.showEpgOnSelect, settings.epgTimeShiftMins)
            }.distinctUntilChanged().collectLatest { (tvgId, showEpg, shiftMins) ->
                if (!showEpg || tvgId.isNullOrBlank()) {
                    _nowNext.value = null
                    return@collectLatest
                }
                while (true) {
                    _nowNext.value = runCatching { epg.nowNext(tvgId, shiftMins) }.getOrNull()
                    delay(60_000L)
                }
            }
        }
    }

    fun selectGroup(id: Long?) { selectedGroupId.value = id }
    fun onFocus(id: Long) = focusChannel(id)

    fun focusChannel(id: Long) {
        focusedChannelId.value = id
        val target = allChannels.value.firstOrNull { it.id == id }
        if (target?.isLocked == true && !lockController.unlocked.value) {
            playerManager.stopAndClear()
        }
    }

    fun setJumpEnabled(enabled: Boolean) = jumpController.setEnabled(enabled)

    fun playPreview(channel: ChannelEntity) {
        if (channel.isLocked && !lockController.unlocked.value) {
            playerManager.stopAndClear()
            return
        }
        playerManager.play(channel)
    }

    fun unlockAndPlay(channel: ChannelEntity) {
        lockController.unlock()
        playerManager.play(channel)
        playerManager.setFullscreen(true)
    }

    suspend fun verifyPin(pin: String): Boolean = settingsStore.verifyPin(pin)
    fun relock() = lockController.relock()

    fun openFullscreen() = playerManager.setFullscreen(true)
    fun closeFullscreen() = playerManager.setFullscreen(false)
    fun togglePlayPause() = playerManager.togglePlayPause()
    fun setAspectMode(mode: AspectMode) {
        playerManager.setAspectMode(mode)
        viewModelScope.launch { settingsStore.setAspectMode(mode) }
    }
    fun applyTrackChoice(type: Int, choice: TrackChoice) =
        playerManager.applyTrackChoice(type, choice)

    fun toggleFavorite(channel: ChannelEntity) {
        viewModelScope.launch { repo.setFavorite(channel.id, !channel.isFavorite) }
    }

    fun toggleLock(channel: ChannelEntity) {
        viewModelScope.launch { repo.setLocked(channel.id, !channel.isLocked) }
    }

    override fun onCleared() {
        // ExoPlayer lives in the singleton; nothing to release here.
        super.onCleared()
    }
}
