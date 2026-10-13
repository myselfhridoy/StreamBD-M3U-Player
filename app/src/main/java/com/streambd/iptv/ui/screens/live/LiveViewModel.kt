package com.streambd.iptv.ui.screens.live

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streambd.iptv.R
import com.streambd.iptv.data.db.ChannelEntity
import com.streambd.iptv.data.db.GroupEntity
import com.streambd.iptv.data.repo.ChannelRepository
import com.streambd.iptv.player.AspectMode
import com.streambd.iptv.player.PlayerManager
import com.streambd.iptv.player.PlayerUiState
import com.streambd.iptv.player.TrackChoice
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LiveViewModel @Inject constructor(
    private val repo: ChannelRepository,
    private val playerManager: PlayerManager,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val selectedGroupId = MutableStateFlow<Long?>(null)
    private val focusedChannelId = MutableStateFlow<Long?>(null)

    val groups: StateFlow<List<GroupEntity>> =
        repo.liveGroups().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val channels: StateFlow<List<ChannelEntity>> =
        selectedGroupId.flatMapLatest { gid ->
            if (gid == null) repo.liveChannels() else repo.liveChannelsIn(gid)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val focusedChannel: StateFlow<ChannelEntity?> =
        combine(channels, focusedChannelId) { list, id ->
            list.firstOrNull { it.id == id } ?: list.firstOrNull()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val selectedGroup: StateFlow<Long?> = selectedGroupId.asStateFlow()

    val playerState: StateFlow<PlayerUiState> = playerManager.state
    val player get() = playerManager

    init {
        viewModelScope.launch {
            if (!repo.hasContent()) seedFromRaw()
        }
    }

    private suspend fun seedFromRaw() {
        runCatching {
            appContext.resources.openRawResource(R.raw.sample)
                .bufferedReader().use { it.readText() }
        }.onSuccess { repo.import(it) }
    }

    fun selectGroup(id: Long?) { selectedGroupId.value = id }
    fun onFocus(id: Long) { focusedChannelId.value = id }

    fun playPreview(channel: ChannelEntity) = playerManager.play(channel)
    fun openFullscreen() = playerManager.setFullscreen(true)
    fun closeFullscreen() = playerManager.setFullscreen(false)
    fun togglePlayPause() = playerManager.togglePlayPause()
    fun setAspectMode(mode: AspectMode) = playerManager.setAspectMode(mode)
    fun applyTrackChoice(type: Int, choice: TrackChoice) =
        playerManager.applyTrackChoice(type, choice)

    fun toggleFavorite(channel: ChannelEntity) {
        viewModelScope.launch { repo.setFavorite(channel.id, !channel.isFavorite) }
    }

    override fun onCleared() {
        // ExoPlayer lives in the singleton; nothing to release here.
        super.onCleared()
    }
}
