package com.streambd.iptv.ui.screens.ftp

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import com.streambd.iptv.data.activation.ActivationRepository
import com.streambd.iptv.data.activation.ActivationState
import com.streambd.iptv.data.db.ChannelEntity
import com.streambd.iptv.data.db.ProviderDao
import com.streambd.iptv.data.db.ProviderEntity
import com.streambd.iptv.data.ftp.FtpBrowser
import com.streambd.iptv.data.ftp.FtpEntry
import com.streambd.iptv.data.ftp.sortEntries
import com.streambd.iptv.data.repo.WatchProgressRepository
import com.streambd.iptv.data.util.stableId
import com.streambd.iptv.player.ExternalSubtitle
import com.streambd.iptv.player.PlayerManager
import com.streambd.iptv.player.PlayerUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

const val PROVIDER_TYPE_FTP = "FTP"
private const val KEY_LAST_SERVER = "last_server_id"

data class Crumb(val name: String, val url: String)

data class FtpUiState(
    val serverId: Long? = null,
    val crumbs: List<Crumb> = emptyList(),
    val entries: List<FtpEntry> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val query: String = "",
    /** অ্যাক্টিভেশন নেই/মেয়াদ শেষ: কনটেন্ট দেখানো হয় না। */
    val blocked: Boolean = false,
    /** এই ফোল্ডারে ফিরে এলে যে আইটেমে ফোকাস দিতে হবে। */
    val focusUrl: String? = null,
)

@HiltViewModel
class FtpViewModel @Inject constructor(
    providerDao: ProviderDao,
    private val browser: FtpBrowser,
    private val playerManager: PlayerManager,
    private val watchProgress: WatchProgressRepository,
    private val activation: ActivationRepository,
    @ApplicationContext context: Context,
) : ViewModel() {

    // শেষ ব্যবহার করা সার্ভার মনে রাখার জন্য (অ্যাপ বন্ধ করে খুললেও থাকে)
    private val prefs = context.getSharedPreferences("ftp_prefs", Context.MODE_PRIVATE)

    val servers: StateFlow<List<ProviderEntity>> = providerDao.observeAll()
        .map { list -> list.filter { it.type == PROVIDER_TYPE_FTP }.sortedBy { it.name.lowercase() } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _ui = MutableStateFlow(FtpUiState())
    val ui: StateFlow<FtpUiState> = _ui.asStateFlow()

    private val _playing = MutableStateFlow<FtpEntry?>(null)
    val playing: StateFlow<FtpEntry?> = _playing.asStateFlow()

    val playerState: StateFlow<PlayerUiState> = playerManager.state
    val player get() = playerManager

    private var loadJob: Job? = null
    private val lastOpened = HashMap<String, String>() // ফোল্ডার URL -> শেষ খোলা আইটেমের URL
    private val subsByFolder = HashMap<String, List<FtpEntry>>() // ফোল্ডার URL -> সেখানের সাবটাইটেল ফাইল

    init {
        viewModelScope.launch {
            if (!isActivated()) {
                _ui.update { it.copy(blocked = true) }
                return@launch
            }
            servers.collect { list ->
                val sel = _ui.value.serverId
                if (list.none { it.id == sel }) {
                    val remembered = prefs.getLong(KEY_LAST_SERVER, -1L)
                    val pick = list.firstOrNull { it.id == remembered } ?: list.firstOrNull()
                    if (pick == null) _ui.update { FtpUiState() } else selectServer(pick)
                }
            }
        }
    }

    /** অ্যাক্টিভ ও মেয়াদোত্তীর্ণ নয় — না হলে FTP ব্যবহার করা যাবে না। */
    private suspend fun isActivated(): Boolean {
        val s = activation.current()
        return s is ActivationState.Active && !activation.isExpired(s)
    }

    fun selectServer(server: ProviderEntity) {
        if (server.id == _ui.value.serverId && _ui.value.crumbs.isNotEmpty()) return
        prefs.edit().putLong(KEY_LAST_SERVER, server.id).apply()
        _ui.update {
            FtpUiState(serverId = server.id, crumbs = listOf(Crumb(server.name, server.url)), loading = true)
        }
        load(server.url)
    }

    fun open(entry: FtpEntry) {
        val cur = _ui.value.crumbs.lastOrNull() ?: return
        lastOpened[cur.url] = entry.url
        _ui.update { it.copy(crumbs = it.crumbs + Crumb(entry.name, entry.url), query = "", loading = true, error = null) }
        load(entry.url)
    }

    /** এক ধাপ উপরে। উপরে যাওয়ার কিছু না থাকলে false। */
    fun up(): Boolean {
        val crumbs = _ui.value.crumbs
        if (crumbs.size <= 1) return false
        goTo(crumbs.size - 2)
        return true
    }

    fun goTo(index: Int) {
        val crumbs = _ui.value.crumbs
        if (index !in crumbs.indices) return
        val target = crumbs.take(index + 1)
        _ui.update { it.copy(crumbs = target, query = "", loading = true, error = null) }
        load(target.last().url)
    }

    fun reload() {
        val url = _ui.value.crumbs.lastOrNull()?.url ?: return
        browser.forget(url)
        _ui.update { it.copy(loading = true, error = null) }
        load(url, force = true)
    }

    fun setQuery(q: String) = _ui.update { it.copy(query = q) }

    private fun load(url: String, force: Boolean = false) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                val all = browser.list(url, force)
                val sorted = sortEntries(all.filter { it.isDir || it.isVideo })
                subsByFolder[url] = all.filter { it.isSubtitle }
                _ui.update {
                    // ব্যবহারকারী ততক্ষণে অন্য ফোল্ডারে গেলে পুরনো ফল বসাব না
                    if (it.crumbs.lastOrNull()?.url != url) it
                    else it.copy(entries = sorted, loading = false, error = null, focusUrl = lastOpened[url])
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update {
                    if (it.crumbs.lastOrNull()?.url != url) it
                    else it.copy(entries = emptyList(), loading = false, error = e.message ?: "Failed to load.")
                }
            }
        }
    }

    // ───────── প্লে ─────────

    fun play(entry: FtpEntry) {
        viewModelScope.launch {
            if (!isActivated()) {
                playerManager.stopAndClear()
                _playing.value = null
                _ui.update { it.copy(blocked = true, entries = emptyList()) }
                return@launch
            }
            _ui.value.crumbs.lastOrNull()?.let { lastOpened[it.url] = entry.url }
            _playing.value = entry
            val resume = watchProgress.resumePositionFor(stableId("ftp:${entry.url}"))
            playerManager.play(
                toChannel(entry),
                resumeAtMs = resume ?: C.TIME_UNSET,
                subtitles = subtitlesFor(entry),
            )
        }
    }

    /**
     * ভিডিওর পাশে একই নামে শুরু হওয়া সাবটাইটেল: "Movie.mkv" -> "Movie.srt", "Movie.en.srt"।
     * নামের পরের অংশ ফাঁকা বা '.' দিয়ে শুরু না হলে ধরা হয় না ("Movie 2.srt" ≠ "Movie.mkv"-এর)।
     */
    private fun subtitlesFor(video: FtpEntry): List<ExternalSubtitle> {
        val folder = _ui.value.crumbs.lastOrNull()?.url ?: return emptyList()
        val subs = subsByFolder[folder].orEmpty()
        if (subs.isEmpty()) return emptyList()
        val base = video.name.substringBeforeLast('.')
        val otherBases = _ui.value.entries.filter { it.isVideo && it.url != video.url }
            .map { it.name.substringBeforeLast('.') }
            .filter { it.length > base.length && it.startsWith(base, ignoreCase = true) }
        return subs.mapNotNull { s ->
            val sb = s.name.substringBeforeLast('.')
            if (!sb.startsWith(base, ignoreCase = true)) return@mapNotNull null
            val rest = sb.substring(base.length)
            if (rest.isNotEmpty() && !rest.startsWith(".")) return@mapNotNull null
            // আরও লম্বা নামের অন্য ভিডিওর সাবটাইটেল হলে বাদ
            if (otherBases.any { sb.startsWith(it, ignoreCase = true) }) return@mapNotNull null
            val tag = rest.trim('.', ' ', '_', '-')
            ExternalSubtitle(
                url = s.url,
                label = tag.ifBlank { "Subtitle" },
                language = tag.takeIf { it.length in 2..3 && it.all(Char::isLetter) }?.lowercase(),
            )
        }.take(6)
    }

    /** এই ফোল্ডারের পরের ভিডিও (এপিসোড) — থাকলে true। */
    fun playNext(): Boolean {
        val cur = _playing.value ?: return false
        val videos = _ui.value.entries.filter { it.isVideo }
        val i = videos.indexOfFirst { it.url == cur.url }
        val next = videos.getOrNull(i + 1) ?: return false
        play(next)
        return true
    }

    fun hasNext(): Boolean {
        val cur = _playing.value ?: return false
        val videos = _ui.value.entries.filter { it.isVideo }
        return videos.indexOfFirst { it.url == cur.url } in 0 until videos.lastIndex
    }

    fun closePlayer() {
        playerManager.stopAndClear()
        _playing.value = null
        _ui.update { it.copy(focusUrl = lastOpened[it.crumbs.lastOrNull()?.url]) }
    }

    /** ডেটাবেসে না রেখে শুধু প্লেয়ারের জন্য অস্থায়ী আইটেম। */
    private fun toChannel(e: FtpEntry) = ChannelEntity(
        id = stableId("ftp:${e.url}"),
        number = 0,
        name = e.name.substringBeforeLast('.'),
        logoUrl = null,
        streamUrl = e.url,
        groupId = 0L,
        tvgId = null,
        kind = "MOVIE",
        providerId = _ui.value.serverId ?: 0L,
    )

    override fun onCleared() {
        if (_playing.value != null) playerManager.stopAndClear()
        super.onCleared()
    }
}
