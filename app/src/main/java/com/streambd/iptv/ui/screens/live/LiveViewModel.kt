package com.streambd.iptv.ui.screens.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streambd.iptv.data.db.AddonDao
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
/** "StreamBD IPTV" ট্যাব: অ্যাডমিনের দেওয়া প্লেলিস্ট + অফিসিয়াল (Catalog) add-on, ফোল্ডার আকারে। */
const val LIVE_GROUP_STREAMBD: Long = -2L
/** ইউজার এখনো কোনো ট্যাব বাছেনি: StreamBD চ্যানেল থাকলে সেই ট্যাব, নইলে All। */
private const val TAB_UNSET: Long = -100L

/**
 * StreamBD ট্যাবের একটা ফোল্ডার (একই নামের গ্রুপ সব অফিসিয়াল সোর্স মিলিয়ে এক ফোল্ডার)।
 * [key]: উপরের স্তরে "soccer", সাব-ফোল্ডারে "soccer\u203Apremier league"।
 */
data class LiveFolder(val key: String, val name: String, val count: Int)

/** গ্রুপের নামে এই চিহ্ন থাকলে `উপরের › নিচের › ...` = ফোল্ডার। (add-on `subgroup` ফিল্ডও এটাই বানায়) */
const val FOLDER_SEP = '\u203A'

/** গ্রুপের নাম থেকে ফোল্ডার পথ: `A › B › C` = যত স্তর খুশি। খালি নাম = "Others"। */
private val OTHERS = listOf("Others")
private fun segsOf(name: String): List<String> =
    name.split(FOLDER_SEP).map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { OTHERS }

private fun startsWithPath(s: List<String>, base: List<String>): Boolean {
    if (s.size < base.size) return false
    for (i in base.indices) if (!s[i].equals(base[i], ignoreCase = true)) return false
    return true
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LiveViewModel @Inject constructor(
    private val repo: ChannelRepository,
    private val playerManager: PlayerManager,
    private val jumpController: ChannelJumpController,
    private val lockController: LockController,
    private val settingsStore: SettingsStore,
    private val epg: EpgRepository,
    addonDao: AddonDao,
    folderPrefs: com.streambd.iptv.data.settings.FolderProviders,
) : ViewModel() {

    private val pickedTab = MutableStateFlow<Long?>(TAB_UNSET)

    /** Room থেকে প্রথম তালিকা আসার আগে false। "Loading" ও "খালি" আলাদা করতে লাগে। */
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    val groups: StateFlow<List<GroupEntity>> =
        repo.liveGroups().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val allChannels: StateFlow<List<ChannelEntity>> =
        repo.liveChannels()
            .onEach { _loaded.value = true }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** অফিসিয়াল সোর্স: সার্ভারের প্লেলিস্ট (providerId 0) + Catalog থেকে নেওয়া add-on। */
    private val officialProviders: StateFlow<Set<Long>> =
        addonDao.observeAll()
            .map { list -> buildSet { add(0L); list.filter { it.origin == "CATALOG" }.forEach { add(it.providerId) } } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), setOf(0L))

    /** ফোল্ডার-সক্ষম provider: অফিসিয়াল + সব add-on + `#EXTM3U x-sb-folders="1"` চিহ্নিত প্লেলিস্ট। অন্যদের `›` সমতল গ্রুপই থাকে। */
    private val folderCapable: StateFlow<Set<Long>> =
        combine(officialProviders, addonDao.observeAll(), folderPrefs.ids) { off, addons, pl -> off + addons.map { it.providerId } + pl }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), setOf(0L))

    /** ফোল্ডার-সক্ষম কিন্তু অফিসিয়াল নয় (নিজের add-on, চিহ্নিত প্লেলিস্ট): উপরের স্তর = ট্যাব। */
    private val ownFolderProviders: StateFlow<Set<Long>> =
        combine(folderCapable, officialProviders) { f, o -> f - o }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** ট্যাব বারে দেখানোর সমতল গ্রুপ (ফোল্ডার-সক্ষম নয়)। */
    val userGroups: StateFlow<List<GroupEntity>> =
        combine(groups, folderCapable) { g, cap -> g.filter { it.providerId !in cap } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** নিজস্ব ফোল্ডার-সোর্সের উপরের-স্তর ট্যাব (id = সেই নামের প্রথম গ্রুপের id)। */
    val folderTabs: StateFlow<List<Pair<Long, String>>> =
        combine(groups, ownFolderProviders) { g, own ->
            val seen = HashSet<String>()
            g.filter { it.providerId in own }.sortedWith(compareBy({ it.providerId }, { it.order })).mapNotNull { gr ->
                val top = segsOf(gr.name).first()
                if (seen.add(top.lowercase())) gr.id to top else null
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val topOfTab: StateFlow<Map<Long, String>> =
        folderTabs.map { it.toMap() }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** groupId -> ফোল্ডার পথ (শুধু ফোল্ডার-সক্ষম গ্রুপ)। */
    private val segsOfGroup: StateFlow<Map<Long, List<String>>> =
        combine(groups, folderCapable) { g, cap -> g.filter { it.providerId in cap }.associate { it.id to segsOf(it.name) } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val officialChannels: StateFlow<List<ChannelEntity>> =
        combine(allChannels, officialProviders) { all, official -> all.filter { it.providerId in official } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val hasStreamBd: StateFlow<Boolean> =
        officialChannels.map { it.isNotEmpty() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** ইউজারের আসল ট্যাব: না বাছলে StreamBD চ্যানেল থাকলে সেটাই ডিফল্ট। */
    val selectedGroup: StateFlow<Long?> =
        combine(pickedTab, hasStreamBd) { picked, has ->
            if (picked == TAB_UNSET) (if (has) LIVE_GROUP_STREAMBD else null) else picked
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** বর্তমান ট্যাব কি ফোল্ডার-ট্যাব (StreamBD, বা নিজস্ব ফোল্ডার-সোর্সের কোনো ট্যাব)? */
    val inFolderTab: StateFlow<Boolean> =
        combine(selectedGroup, topOfTab) { s, t -> s == LIVE_GROUP_STREAMBD || (s != null && s in t) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // ── ফোল্ডার নেভিগেশন (যত স্তর) ──
    /** ট্যাবের নিচে কোথায় আছি (ফোল্ডারের নামের তালিকা)। খালি = ট্যাবের মূল স্তর। */
    private val _path = MutableStateFlow<List<String>>(emptyList())
    val path: StateFlow<List<String>> = _path.asStateFlow()

    /** প্রতি স্তরে "কোন সারিতে ছিলাম": Back দিলে ঠিক সেখানেই ফোকাস ফেরে। */
    private val levelFocus = HashMap<String, Int>()
    private fun levelKey() = "${selectedGroup.value}|" + _path.value.joinToString("/")
    fun focusFor(): Int = levelFocus[levelKey()] ?: -1

    fun openFolder(name: String, rowIndex: Int) {
        levelFocus[levelKey()] = rowIndex
        _path.value = _path.value + name
    }

    /** এক ধাপ পেছনে। */
    fun closeFolder() { if (_path.value.isNotEmpty()) _path.value = _path.value.dropLast(1) }

    private data class ScopeView(val scope: List<ChannelEntity>, val folders: List<LiveFolder>, val direct: List<ChannelEntity>)

    private val scopeView: StateFlow<ScopeView> =
        combine(allChannels, groups, selectedGroup, _path, segsOfGroup, officialProviders, topOfTab) { a ->
            @Suppress("UNCHECKED_CAST") val all = a[0] as List<ChannelEntity>
            @Suppress("UNCHECKED_CAST") val grps = a[1] as List<GroupEntity>
            val tab = a[2] as Long?
            @Suppress("UNCHECKED_CAST") val path = a[3] as List<String>
            @Suppress("UNCHECKED_CAST") val segMap = a[4] as Map<Long, List<String>>
            @Suppress("UNCHECKED_CAST") val official = a[5] as Set<Long>
            @Suppress("UNCHECKED_CAST") val topMap = a[6] as Map<Long, String>
            when {
                tab == null -> ScopeView(all, emptyList(), all)
                tab == LIVE_GROUP_FAVORITES -> all.filter { it.isFavorite }.let { ScopeView(it, emptyList(), it) }
                tab == LIVE_GROUP_STREAMBD || tab in topMap -> {
                    val streamBd = tab == LIVE_GROUP_STREAMBD
                    val top = if (streamBd) null else topMap[tab]
                    val base = (if (top == null) emptyList() else listOf(top)) + path
                    val pool = if (streamBd) all.filter { it.providerId in official }
                    else all.filter { it.providerId !in official && segMap.containsKey(it.groupId) }
                    val scope = ArrayList<ChannelEntity>()
                    val direct = ArrayList<ChannelEntity>()
                    val counts = LinkedHashMap<String, Pair<String, Int>>() // lower → (display, count)
                    for (c in pool) {
                        val s = segMap[c.groupId] ?: OTHERS // মিল না থাকা/নামহীন গ্রুপ "Others"
                        if (!startsWithPath(s, base)) continue
                        scope += c
                        if (s.size == base.size) direct += c
                        else {
                            val nm = s[base.size]
                            val prev = counts[nm.lowercase()]
                            counts[nm.lowercase()] = nm to ((prev?.second ?: 0) + 1)
                        }
                    }
                    // গ্রুপের ক্রম অনুযায়ী (প্রথম দেখা পথ আগে)
                    val orderOf = HashMap<String, Int>()
                    grps.filter { g -> segMap[g.id]?.let { it.size > base.size && startsWithPath(it, base) } == true }
                        .sortedWith(compareBy({ it.providerId }, { it.order }))
                        .forEach { g -> orderOf.putIfAbsent(segMap.getValue(g.id)[base.size].lowercase(), orderOf.size) }
                    val others = streamBd && path.isEmpty()
                    val folders = counts.map { (k, v) -> LiveFolder((base + v.first).joinToString(FOLDER_SEP.toString()).lowercase(), v.first, v.second) }
                        .sortedWith(
                            compareBy<LiveFolder> { if (others && it.name.equals("Others", true)) 1 else 0 }
                                .thenBy { orderOf[it.name.lowercase()] ?: Int.MAX_VALUE },
                        )
                    ScopeView(scope, folders, direct)
                }
                else -> all.filter { it.groupId == tab }.let { ScopeView(it, emptyList(), it) }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScopeView(emptyList(), emptyList(), emptyList()))

    /** খোলা স্তরের ফোল্ডারগুলো। */
    val folders: StateFlow<List<LiveFolder>> =
        scopeView.map { it.folders }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** বর্তমান স্কোপের সব চ্যানেল (সাব-ফোল্ডারসহ)। Left/Right zap এই তালিকার ভেতরে ঘোরে। */
    val channels: StateFlow<List<ChannelEntity>> =
        scopeView.map { it.scope }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** তালিকায় সরাসরি দেখানোর চ্যানেল: ফোল্ডার-ট্যাবে শুধু এই স্তরের নিজের, নইলে পুরো স্কোপ। */
    val listChannels: StateFlow<List<ChannelEntity>> =
        combine(scopeView, inFolderTab) { v, f -> if (f) v.direct else v.scope }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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
                pickedTab.value = null
                _path.value = emptyList()
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

        // প্লেলিস্ট আপডেটে চলমান চ্যানেলের টোকেন বদলালে, আটকে থাকা প্লেব্যাক নিজে থেকে নতুন URL-এ চলবে
        viewModelScope.launch {
            repo.liveChannels().collect { list ->
                val cur = playerManager.state.value.current ?: return@collect
                list.firstOrNull { it.id == cur.id }?.let(playerManager::recoverWith)
            }
        }

        // শেষ দেখা লাইভ চ্যানেল মনে রাখা (বুটে সরাসরি চালানোর জন্য)
        viewModelScope.launch {
            playerManager.state
                .map { it.current }
                .distinctUntilChanged { a, b -> a?.id == b?.id }
                .collect { channel ->
                    if (channel != null && channel.kind == ChannelKind.LIVE.name) {
                        settingsStore.setLastLiveChannel(channel.id)
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

    fun selectGroup(id: Long?) { pickedTab.value = id; _path.value = emptyList() }

    /** তালিকায় ক্লিক, নম্বর জাম্প বা zap: লক থাকলে PIN, নইলে সরাসরি প্লে। */
    fun requestPlay(channel: ChannelEntity) {
        if (channel.isLocked && !lockController.unlocked.value) {
            _pendingUnlock.value = channel
        } else {
            playerManager.play(channel)
        }
    }

    /** চ্যানেল পেলে চালু করে true; না পেলে (প্লেলিস্ট বদলে গেছে) false। */
    suspend fun playById(id: Long): Boolean {
        val target = repo.liveChannels().first().firstOrNull { it.id == id } ?: return false
        requestPlay(target)
        return true
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

    /** Retry সবসময় ডাটাবেসের সর্বশেষ URL (নতুন টোকেন) দিয়ে চালায়, পুরনো কপি দিয়ে নয়। */
    fun retry() {
        val cur = playerManager.state.value.current ?: return
        viewModelScope.launch {
            val fresh = repo.liveChannels().first().firstOrNull { it.id == cur.id } ?: cur
            playerManager.play(fresh, force = true)
        }
    }
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