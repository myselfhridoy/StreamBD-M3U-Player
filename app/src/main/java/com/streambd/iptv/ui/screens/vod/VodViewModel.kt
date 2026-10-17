package com.streambd.iptv.ui.screens.vod

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streambd.iptv.data.db.ChannelEntity
import com.streambd.iptv.data.db.GroupEntity
import com.streambd.iptv.data.m3u.ChannelKind
import com.streambd.iptv.data.repo.ChannelRepository
import com.streambd.iptv.data.settings.SettingsStore
import com.streambd.iptv.data.vod.EpisodeEntry
import com.streambd.iptv.data.vod.SeriesGroup
import com.streambd.iptv.data.vod.SeriesGrouping
import com.streambd.iptv.player.AspectMode
import com.streambd.iptv.player.LockController
import com.streambd.iptv.player.PlayerManager
import com.streambd.iptv.player.PlayerUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

const val VOD_GROUP_FAVORITES: Long = -1L

@HiltViewModel
class VodViewModel @Inject constructor(
    private val repo: ChannelRepository,
    private val playerManager: PlayerManager,
    private val lockController: LockController,
    private val settingsStore: SettingsStore,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val kind: ChannelKind = when (savedStateHandle.get<String>("kind")?.uppercase()) {
        "SERIES" -> ChannelKind.SERIES
        else -> ChannelKind.MOVIE
    }

    val title: String = when (kind) {
        ChannelKind.SERIES -> "Series"
        ChannelKind.MOVIE -> "Movies"
        ChannelKind.LIVE -> "Live"
    }

    val kindLabel: String = when (kind) {
        ChannelKind.SERIES -> "SERIES"
        ChannelKind.MOVIE -> "MOVIE"
        ChannelKind.LIVE -> "LIVE"
    }

    val groups: StateFlow<List<GroupEntity>> =
        repo.groupsByKind(kind).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val allItems: StateFlow<List<ChannelEntity>> =
        repo.byKind(kind).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val selectedGroupId = MutableStateFlow<Long?>(null)
    val selectedGroup: StateFlow<Long?> = selectedGroupId.asStateFlow()

    val items: StateFlow<List<ChannelEntity>> =
        combine(allItems, selectedGroupId) { all, groupId ->
            when (groupId) {
                null -> all
                VOD_GROUP_FAVORITES -> all.filter { it.isFavorite }
                else -> all.filter { it.groupId == groupId }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val selectedSeries = MutableStateFlow<String?>(null)
    val selectedSeriesName: StateFlow<String?> = selectedSeries.asStateFlow()

    val seriesGroups: StateFlow<List<SeriesGroup>> =
        items.map { list ->
            if (kind == ChannelKind.SERIES) SeriesGrouping.groups(list) else emptyList()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val episodes: StateFlow<List<EpisodeEntry>> =
        combine(allItems, selectedSeries) { all, seriesName ->
            if (kind == ChannelKind.SERIES && seriesName != null) {
                SeriesGrouping.episodes(all, seriesName)
            } else {
                emptyList()
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val playerState: StateFlow<PlayerUiState> = playerManager.state
    val sessionUnlocked: StateFlow<Boolean> = lockController.unlocked
    val player get() = playerManager

    fun selectGroup(id: Long?) {
        selectedGroupId.value = id
        selectedSeries.value = null
    }

    fun openSeries(name: String) {
        selectedSeries.value = name
    }

    fun closeSeries() {
        selectedSeries.value = null
    }

    fun play(item: ChannelEntity) {
        playerManager.play(item)
        playerManager.setFullscreen(true)
    }

    fun unlockAndPlay(item: ChannelEntity) {
        lockController.unlock()
        play(item)
    }

    fun stopForLock() = playerManager.stopAndClear()

    suspend fun verifyPin(pin: String): Boolean = settingsStore.verifyPin(pin)

    fun closeFullscreen() = playerManager.setFullscreen(false)
    fun togglePlayPause() = playerManager.togglePlayPause()
    fun setAspectMode(mode: AspectMode) {
        playerManager.setAspectMode(mode)
        viewModelScope.launch { settingsStore.setAspectMode(mode) }
    }

    fun toggleFavorite(item: ChannelEntity) {
        viewModelScope.launch { repo.setFavorite(item.id, !item.isFavorite) }
    }

    fun toggleLock(item: ChannelEntity) {
        viewModelScope.launch { repo.setLocked(item.id, !item.isLocked) }
    }
}