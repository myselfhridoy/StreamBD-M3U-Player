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

/** StreamBD ট্যাবের একটা ফোল্ডার (একই নামের গ্রুপ সব অফিসিয়াল সোর্স মিলিয়ে এক ফোল্ডার)। */
data class LiveFolder(val key: String, val name: String, val count: Int)

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

    /** ট্যাব বারে দেখানোর ইউজারের নিজের গ্রুপ (অফিসিয়ালগুলো StreamBD ট্যাবের ভেতরে)। */
    val userGroups: StateFlow<List<GroupEntity>> =
        combine(groups, officialProviders) { g, official -> g.filter { it.providerId !in official } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val officialChannels: StateFlow<List<ChannelEntity>> =
        combine(allChannels, officialProviders) { all, official -> all.filter { it.providerId in official } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val hasStreamBd: StateFlow<Boolean> =
        officialChannels.map { it.isNotEmpty() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** groupId -> ফোল্ডার key (শুধু অফিসিয়াল গ্রুপ)। একই নামের গ্রুপ এক ফোল্ডার। */
    private val folderOfGroup: StateFlow<Map<Long, Pair<String, String>>> =
        combine(groups, officialProviders) { g, official ->
            g.filter { it.providerId in official }
                .associate { it.id to (it.name.trim().lowercase() to it.name.trim().ifBlank { "Others" }) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** মিল না থাকা/নামহীন গ্রুপের চ্যানেল "Others" ফোল্ডারে। */
    private fun folderKeyOf(c: ChannelEntity, map: Map<Long, Pair<String, String>>): Pair<String, String> =
        map[c.groupId]?.takeIf { it.first.isNotBlank() } ?: ("others" to "Others")

    val folders: StateFlow<List<LiveFolder>> =
        combine(officialChannels, folderOfGroup, groups) { chans, map, grps ->
            val orderOf = HashMap<String, Int>()
            grps.filter { it.id in map }.sortedWith(compareBy({ it.providerId }, { it.order })).forEach { g ->
                orderOf.putIfAbsent(map.getValue(g.id).first, orderOf.size)
            }
            val counts = LinkedHashMap<String, Pair<String, Int>>()
            for (c in chans) {
                val (key, name) = folderKeyOf(c, map)
                val prev = counts[key]
                counts[key] = (prev?.first ?: name) to ((prev?.second ?: 0) + 1)
            }
            counts.map { (k, v) -> LiveFolder(k, v.first, v.second) }
                .sortedWith(compareBy<LiveFolder> { if (it.key == "others") 1 else 0 }.thenBy { orderOf[it.key] ?: Int.MAX_VALUE })
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** StreamBD ট্যাবে কোন ফোল্ডার খোলা (null = ফোল্ডারের তালিকা)। */
    private val _openFolder = MutableStateFlow<String?>(null)
    val openFolder: StateFlow<String?> = _openFolder.asStateFlow()
    /** সর্বশেষ খোলা ফোল্ডার: ফিরে এলে সেখানে ফোকাস রাখার জন্য। */
    private val _lastFolder = MutableStateFlow<String?>(null)
    val lastFolder: StateFlow<String?> = _lastFolder.asStateFlow()

    fun openFolder(key: String) { _lastFolder.value = key; _openFolder.value = key }
    fun closeFolder() { _openFolder.value = null }

    /** ইউজারের আসল ট্যাব: না বাছলে StreamBD চ্যানেল থাকলে সেটাই ডিফল্ট। */
    val selectedGroup: StateFlow<Long?> =
        combine(pickedTab, hasStreamBd) { picked, has ->
            if (picked == TAB_UNSET) (if (has) LIVE_GROUP_STREAMBD else null) else picked
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** বর্তমান দৃশ্যের চ্যানেল। Left/Right zap এই তালিকার ভেতরে ঘোরে। */
    val channels: StateFlow<List<ChannelEntity>> =
        combine(allChannels, selectedGroup, _openFolder, officialChannels, folderOfGroup) { all, tab, folder, official, map ->
            when (tab) {
                null -> all
                LIVE_GROUP_FAVORITES -> all.filter { it.isFavorite }
                LIVE_GROUP_STREAMBD ->
                    if (folder == null) official else official.filter { folderKeyOf(it, map).first == folder }
                else -> all.filter { it.groupId == tab }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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

    fun selectGroup(id: Long?) { pickedTab.value = id }

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
