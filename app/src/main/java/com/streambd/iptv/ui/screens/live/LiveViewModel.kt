package com.streambd.iptv.ui.screens.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streambd.iptv.data.db.ChannelEntity
import com.streambd.iptv.data.db.GroupEntity
import com.streambd.iptv.data.epg.EpgRepository
import com.streambd.iptv.data.epg.NowNext
import com.streambd.iptv.data.m3u.ChannelKind
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

    /** Room থেকে প্রথম তালিকা আসার আগে false। "Loading" ও "খালি" আলাদা করতে লাগে। */
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    val groups: StateFlow<List<GroupEntity>> =
        repo.liveGroups().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val allChannels: StateFlow<List<ChannelEntity>> =
        repo.liveChannels()
            .onEach { _loaded.value = true }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** গ্রুপ ফিল্টার করা তালিকা। Left/Right zap এই তালিকার ভেতরে ঘোরে। */
    val channels: StateFlow<List<ChannelEntity>> =
        combine(allChannels, selectedGroupId) { all, groupId ->
            when (groupId) {
                null -> all
                LIVE_GROUP_FAVORITES -> all.filter { it.isFavorite }
                else -> all.filter { it.groupId == groupId }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val selectedGroup: StateFlow<Long?> = selectedGroupId.asStateFlow()

    val playerState: StateFlow<PlayerUiState> = playerManager.state
    val jumpDisplay: StateFlow<String> = jumpController.display
    val sessionUnlocked: StateFlow<Boolean> = lockController.unlocked
    private val _nowNext = MutableStateFlow<NowNext?>(null)
    val nowNext: StateFlow<NowNext?> = _nowNext.asStateFlow()

    /** PIN-এর অপেক্ষায় থাকা লক করা চ্যানেল। */
    private val _pendingUnlock = MutableStateFlow<ChannelEntity?>(null)
    val pendingUnlock: StateFlow<ChannelEntity?> = _pendingUnlock.asStateFlow()

    val player get() = playerManager

    init {
        viewModelScope.launch {
            jumpController.commits.collect { raw ->
                val number = raw.toIntOrNull() ?: return@collect
                val target = repo.liveChannels().first().firstOrNull { it.number == number }
                    ?: return@collect
                selectedGroupId.value = null
                requestPlay(target)
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

    /** স্ক্রিনে ঢোকার সময়: আগের লাইভ চ্যানেল লাইভ প্রান্ত থেকে চলবে, VOD হলে বন্ধ। */
    fun onEnter() {
        val current = playerManager.state.value.current ?: return
        if (current.kind != ChannelKind.LIVE.name) {
            playerManager.stopAndClear()
        } else {
            playerManager.player.run {
                seekToDefaultPosition()
                play()
            }
        }
    }

    fun selectGroup(id: Long?) { selectedGroupId.value = id }

    /** তালিকায় ক্লিক, নম্বর জাম্প বা zap: লক থাকলে PIN, নইলে সরাসরি প্লে। */
    fun requestPlay(channel: ChannelEntity) {
        if (channel.isLocked && !lockController.unlocked.value) {
            _pendingUnlock.value = channel
        } else {
            playerManager.play(channel)
        }
    }

    suspend fun playById(id: Long) {
        val target = repo.liveChannels().first().firstOrNull { it.id == id } ?: return
        requestPlay(target)
    }

    /** +1 পরের, -1 আগের চ্যানেল (বর্তমান তালিকার ভেতরে ঘুরে আসে)। */
    fun step(delta: Int) {
        val list = channels.value.ifEmpty { allChannels.value }
        if (list.isEmpty()) return
        val currentId = playerManager.state.value.current?.id
        val index = list.indexOfFirst { it.id == currentId }
        val target = when {
            index >= 0 -> list[Math.floorMod(index + delta, list.size)]
            delta > 0 -> list.first()
            else -> list.last()
        }
        requestPlay(target)
    }

    fun confirmUnlock() {
        val target = _pendingUnlock.value ?: return
        _pendingUnlock.value = null
        lockController.unlock()
        playerManager.play(target)
    }

    fun dismissUnlock() { _pendingUnlock.value = null }

    fun setJumpEnabled(enabled: Boolean) = jumpController.setEnabled(enabled)

    suspend fun verifyPin(pin: String): Boolean = settingsStore.verifyPin(pin)
    fun relock() = lockController.relock()

    fun retry() = playerManager.retry()
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
