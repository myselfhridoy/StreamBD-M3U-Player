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

/** group-title-এ `A › B › C` লিখলে যত খুশি স্তরের ফোল্ডার হয় (add-on: group + subgroup, বা subgroup-এ নিজেই › দিয়ে)। */
private const val FOLDER_SEP = '\u203A'

private fun folderSegs(name: String): List<String> =
    name.split(FOLDER_SEP).map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf(name.trim()) }

/** ফোল্ডার-ভিউয়ের একটি সারি: ফোল্ডার, মুভি/এপিসোড, বা সিরিজ। */
sealed interface VodRow {
    val key: String
    data class Folder(val name: String, val count: Int?) : VodRow { override val key get() = "f:$name" }
    data class Item(val item: ChannelEntity) : VodRow { override val key get() = "i:${item.id}" }
    data class Series(val group: SeriesGroup) : VodRow { override val key get() = "s:${group.name}" }
}

@HiltViewModel
class VodViewModel @Inject constructor(
    private val repo: ChannelRepository,
    private val playerManager: PlayerManager,
    private val lockController: LockController,
    private val settingsStore: SettingsStore,
    private val watchProgress: WatchProgressRepository,
    private val addonResolver: AddonResolver,
    private val xtream: com.streambd.iptv.data.xtream.XtreamRepository,
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

    // ── ফোল্ডার নেভিগেশন (যত স্তর) ──
    /** উপরের ট্যাব: প্রতিটা গ্রুপ-নামের প্রথম অংশ (id = সেই নামের প্রথম গ্রুপের id)। সমতল প্লেলিস্টে আগের মতোই প্রতি গ্রুপে একটা ট্যাব। */
    val topTabs: StateFlow<List<Pair<Long, String>>> =
        groups.map { gs ->
            val seen = HashSet<String>()
            gs.mapNotNull { g ->
                val top = folderSegs(g.name).first()
                if (seen.add(top.lowercase())) g.id to top else null
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** ট্যাবের নিচে কোথায় আছি (ফোল্ডারের নামের তালিকা)। খালি = ট্যাবের মূল স্তর। */
    private val _path = MutableStateFlow<List<String>>(emptyList())
    val path: StateFlow<List<String>> = _path.asStateFlow()

    /** প্রতি স্তরে "কোন সারিতে ছিলাম": Back দিলে ঠিক সেখানেই ফোকাস ফেরে। */
    private val levelFocus = HashMap<String, Int>()
    private fun levelKey(path: List<String> = _path.value) = (selectedGroupId.value?.toString() ?: "all") + "|" + path.joinToString("/")
    fun focusFor(): Int = levelFocus[levelKey()] ?: 0

    fun openFolder(name: String, rowIndex: Int) {
        levelFocus[levelKey()] = rowIndex
        _path.value = _path.value + name
        selectedSeries.value = null
    }

    fun closeFolder() {
        if (_path.value.isEmpty()) return
        _path.value = _path.value.dropLast(1)
    }

    val rows: StateFlow<List<VodRow>> =
        combine(allItems, groups, selectedGroupId, _path) { all, grps, sel, path ->
            val segMap = grps.associate { it.id to folderSegs(it.name) }
            val nested = segMap.values.any { it.size > 1 }
            fun itemRows(list: List<ChannelEntity>): List<VodRow> =
                if (kind == ChannelKind.SERIES) SeriesGrouping.groups(list).map { VodRow.Series(it) } else list.map { VodRow.Item(it) }

            when {
                sel == VOD_GROUP_FAVORITES -> all.filter { it.isFavorite }.map { VodRow.Item(it) }
                sel == null && !nested -> itemRows(all)
                !nested -> itemRows(all.filter { it.groupId == sel })
                else -> {
                    val top = if (sel == null) null else segMap[sel]?.first()
                    val base = if (top == null) path else listOf(top) + path
                    val folders = LinkedHashMap<String, Pair<String, Int>>() // lower → (display, count)
                    val direct = ArrayList<ChannelEntity>()
                    for (c in all) {
                        val s = segMap[c.groupId] ?: continue
                        if (s.size < base.size) continue
                        var ok = true
                        for (i in base.indices) if (!s[i].equals(base[i], ignoreCase = true)) { ok = false; break }
                        if (!ok) continue
                        if (s.size == base.size) direct += c
                        else {
                            val nm = s[base.size]
                            val cur = folders[nm.lowercase()]
                            folders[nm.lowercase()] = nm to ((cur?.second ?: 0) + 1)
                        }
                    }
                    val folderRows = folders.values.sortedWith(compareBy({ !it.first.first().isLetter() }, { it.first.lowercase() }))
                        .map { VodRow.Folder(it.first, if (kind == ChannelKind.SERIES) null else it.second) }
                    folderRows + itemRows(direct)
                }
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
        _path.value = emptyList()
        selectedSeries.value = null
    }

    fun openSeries(name: String) {
        selectedSeries.value = name
        lazyJob?.cancel()
        lazyEpisodes.value = emptyList()
        _lazyStatus.value = null
        val group = (rows.value.firstNotNullOfOrNull { (it as? VodRow.Series)?.group?.takeIf { g -> g.name == name } }
            ?: seriesGroups.value.firstOrNull { it.name == name })
        val ref = group?.lazyRef ?: return
        _lazyStatus.value = "Loading episodes…"
        lazyJob = viewModelScope.launch {
            try {
                val list = if (ref.startsWith(com.streambd.iptv.data.xtream.XtreamApi.SERIES_SCHEME)) {
                    xtream.listEpisodes(ref)
                } else {
                    addonResolver.listEpisodes(ref, name)
                }
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
