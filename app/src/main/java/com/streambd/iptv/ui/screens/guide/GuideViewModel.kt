package com.streambd.iptv.ui.screens.guide

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streambd.iptv.data.db.ChannelEntity
import com.streambd.iptv.data.db.GroupEntity
import com.streambd.iptv.data.db.ProgramEntity
import com.streambd.iptv.data.epg.EpgRepository
import com.streambd.iptv.data.repo.ChannelRepository
import com.streambd.iptv.data.settings.AppSettings
import com.streambd.iptv.data.settings.SettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

const val GROUP_FAVORITES = -1L
val GROUP_ALL: Long? = null

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class GuideViewModel @Inject constructor(
    private val channels: ChannelRepository,
    private val epg: EpgRepository,
    settings: SettingsStore,
) : ViewModel() {

    val settingsFlow: StateFlow<AppSettings> =
        settings.flow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    val groups: StateFlow<List<GroupEntity>> =
        channels.liveGroups().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val selectedGroupId = MutableStateFlow<Long?>(GROUP_ALL)
    val selectedGroup: StateFlow<Long?> = selectedGroupId.asStateFlow()

    private val hourOffset = MutableStateFlow(0)
    val hourOffsetFlow: StateFlow<Int> = hourOffset.asStateFlow()

    private val allLive: StateFlow<List<ChannelEntity>> =
        channels.liveChannels().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val channelsFiltered: StateFlow<List<ChannelEntity>> =
        combine(allLive, selectedGroupId) { list, groupId ->
            when (groupId) {
                null -> list
                GROUP_FAVORITES.toLong() -> list.filter { it.isFavorite }
                else -> list.filter { it.groupId == groupId }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val programsByTvg: StateFlow<Map<String, List<ProgramEntity>>> =
        channelsFiltered
            .map { list -> list.mapNotNull { it.tvgId }.distinct() }
            .distinctUntilChanged()
            .flatMapLatest { tvgIds ->
                if (tvgIds.isEmpty()) {
                    flowOf(emptyMap())
                } else {
                    val now = System.currentTimeMillis()
                    epg.observeWindow(
                        tvgIds,
                        from = now - 12L * 3600 * 1000,
                        to = now + 60L * 3600 * 1000,
                    ).map { programs -> programs.groupBy { it.channelTvgId } }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun selectGroup(id: Long?) {
        selectedGroupId.value = id
    }

    fun shiftWindowBy(hours: Int) {
        hourOffset.value += hours
    }

    fun resetWindow() {
        hourOffset.value = 0
    }

    fun groupLabel(groupId: Long?, groups: List<GroupEntity>): String = when (groupId) {
        null -> "All Channels"
        GROUP_FAVORITES -> "Favorites"
        else -> groups.firstOrNull { it.id == groupId }?.name ?: "All Channels"
    }
}