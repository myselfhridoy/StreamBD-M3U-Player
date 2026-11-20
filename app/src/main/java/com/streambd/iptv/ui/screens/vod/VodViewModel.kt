package com.streambd.iptv.ui.screens.vod

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import com.streambd.iptv.data.addon.AddonResolver
import com.streambd.iptv.data.db.ChannelEntity
import com.streambd.iptv.data.db.GroupEntity
import com.streambd.iptv.data.m3u.ChannelKind
import com.streambd.iptv.data.repo.ChannelRepository
import com.streambd.iptv.data.repo.WatchProgressRepository
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
    private val watchProgress: WatchProgressRepository,
    private val addonResolver: AddonResolver,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    // new: "RADIO" string handle করা হলো
    val kind: ChannelKind = when (savedStateHandle.get<String>("kind")?.uppercase()) {
        "SERIES" -> ChannelKind.SERIES
        "RADIO" -> ChannelKind.RADIO
        "LIVE" -> ChannelKind.LIVE
        else -> ChannelKind.MOVIE
    }

    // fixed: RADIO branch যোগ করা হলো
    val title: String = when (kind) {
        ChannelKind.SERIES -> "Series"
        ChannelKind.MOVIE -> "Movies"
        ChannelKind.LIVE -> "Live"
        ChannelKind.RADIO -> "Radio"
    }

    // fixed: RADIO branch যোগ করা হলো
    val kindLabel: String = when (kind) {
        ChannelKind.SERIES -> "SERIES"
        ChannelKind.MOVIE -> "MOVIE"
        ChannelKind.LIVE -> "LIVE"
        ChannelKind.RADIO -> "RADIO"
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

    // lazy সিরিজ (add-on `lazy:true`): এপিসোড ডেটাবেসে নেই, সিরিজ খোলার সময় add-on থেকে আসে
    private val lazyEpisodes = MutableStateFlow<List<EpisodeEntry>>(emptyList())
    private val _lazyStatus = MutableStateFlow<String?>(null)
    /** lazy এপিসোড লোড হচ্ছে ("Loading episodes…") বা ব্যর্থ হয়েছে (কারণ); null = স্বাভাবিক। */
    val lazyStatus: StateFlow<String?> = _lazyStatus.asStateFlow()
    private var lazyJob: kotlinx.coroutines.Job? = null

    val episodes: StateFlow<List<EpisodeEntry>> =
        combine(allItems, selectedSeries, lazyEpisodes) { all, seriesName, lazy ->
            if (kind == ChannelKind.SERIES && seriesName != null) {
                SeriesGrouping.episodes(all, seriesName).ifEmpty { lazy }
            } else {
                emptyList()
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val playerState: StateFlow<PlayerUiState> = playerManager.state
    val sessionUnlocked: StateFlow<Boolean> = lockController.unlocked
    val player get() = playerManager

    init {
        // প্লেলিস্ট আপডেটে টোকেন বদলালে আটকে থাকা প্লেব্যাক নতুন URL-এ চলবে
        viewModelScope.launch {
            repo.byKind(kind).collect { list ->
                val cur = playerManager.state.value.current ?: return@collect
                list.firstOrNull { it.id == cur.id }?.let(playerManager::recoverWith)
            }
        }
    }

    /** Retry সর্বশেষ URL দিয়ে চালায়। FTP-র মতো ডাটাবেসে না থাকা আইটেম হলে আগেরটাই। */
    fun retry() {
        val cur = playerManager.state.value.current ?: return
        val fresh = allItems.value.firstOrNull { it.id == cur.id } ?: cur
        playerManager.play(fresh, force = true)
    }

    fun onEnter() {
        val current = playerManager.state.value.current ?: return
        if (current.kind != kind.name) playerManager.stopAndClear()
    }

    fun selectGroup(id: Long?) {
        selectedGroupId.value = id
        selectedSeries.value = null
    }

    fun openSeries(name: String) {
        selectedSeries.value = name
        lazyJob?.cancel()
        lazyEpisodes.value = emptyList()
        _lazyStatus.value = null
        val group = seriesGroups.value.firstOrNull { it.name == name }
        val ref = group?.lazyRef ?: return
        _lazyStatus.value = "Loading episodes…"
        lazyJob = viewModelScope.launch {
            try {
                val list = addonResolver.listEpisodes(ref, name)
                val entries = list.map { e ->
                    val id = java.util.UUID.nameUUIDFromBytes("lazy|$ref|${e.streamUrl}".toByteArray()).mostSignificantBits
                    val label = e.name.ifBlank {
                        if (e.season != null && e.episode != null) "S%02dE%02d".format(e.season, e.episode) else name
                    }
                    val entity = ChannelEntity(
                        id = id, number = 0, name = label, logoUrl = e.logo ?: group.poster,
                        streamUrl = e.streamUrl, groupId = group.lazyGroupId, tvgId = null,
                        kind = ChannelKind.SERIES.name, season = e.season, episode = e.episode,
                        serieTitle = name, subtitle = e.subtitle,
                    )
                    EpisodeEntry(
                        id = id, season = e.season, episode = e.episode, displayName = label, fullName = label,
                        streamUrl = e.streamUrl, logoUrl = e.logo ?: group.poster, isLocked = false, isFavorite = false,
                        subtitle = e.subtitle, lazyEntity = entity,
                    )
                }.sortedWith(compareBy({ it.season ?: Int.MAX_VALUE }, { it.episode ?: Int.MAX_VALUE }, { it.fullName }))
                lazyEpisodes.value = entries
                _lazyStatus.value = if (entries.isEmpty()) "No episodes found." else null
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _lazyStatus.value = "Could not load episodes: ${e.message ?: "unknown error"}"
            } catch (e: OutOfMemoryError) {
                _lazyStatus.value = "Could not load episodes (not enough memory)."
            }
        }
    }

    fun closeSeries() {
        lazyJob?.cancel()
        lazyEpisodes.value = emptyList()
        _lazyStatus.value = null
        selectedSeries.value = null
    }

    fun play(item: ChannelEntity) {
        viewModelScope.launch {
            // আগে দেখা থাকলে যেখানে থেমেছিল সেখান থেকে চলবে
            val resume = watchProgress.resumePositionFor(item.id)
            playerManager.play(item, resumeAtMs = resume ?: C.TIME_UNSET)
            playerManager.setFullscreen(true)
        }
    }

    /** সিরিজের এপিসোড তালিকা খোলা থাকলে চলমান এপিসোডের পরেরটা চালায়। না থাকলে false। */
    fun playNextEpisode(): Boolean {
        if (kind != ChannelKind.SERIES) return false
        val cur = playerManager.state.value.current ?: return false
        val eps = episodes.value
        val i = eps.indexOfFirst { it.id == cur.id }
        if (i < 0) return false
        val nextId = eps.getOrNull(i + 1)?.id ?: return false
        val next = allItems.value.firstOrNull { it.id == nextId } ?: eps.getOrNull(i + 1)?.lazyEntity ?: return false
        if (next.isLocked && !lockController.unlocked.value) return false
        play(next)
        return true
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