package com.streambd.iptv.ui.screens.title

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import com.streambd.iptv.data.addon.SourceFinder
import com.streambd.iptv.data.addon.TitleQuery
import com.streambd.iptv.data.addon.TitleSource
import com.streambd.iptv.data.db.ChannelEntity
import com.streambd.iptv.data.repo.WatchProgressRepository
import com.streambd.iptv.data.tmdb.TmdbDetail
import com.streambd.iptv.data.tmdb.TmdbEpisode
import com.streambd.iptv.data.tmdb.TmdbImages
import com.streambd.iptv.data.tmdb.TmdbRepository
import com.streambd.iptv.data.util.stableId
import com.streambd.iptv.player.PlayerManager
import com.streambd.iptv.player.PlayerUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TitleUi(
    val loading: Boolean = true,
    val error: String? = null,
    val detail: TmdbDetail? = null,
    /** সিরিজ: বাছা সিজন ও তার এপিসোড। */
    val season: Int? = null,
    val episodes: List<TmdbEpisode> = emptyList(),
    val episodesLoading: Boolean = false,
    val episodesError: String? = null,
    /** সিরিজ: বাছা এপিসোড (null = এখনো বাছা হয়নি, এপিসোড তালিকা দেখাচ্ছে)। */
    val episode: TmdbEpisode? = null,
    /** সোর্স খোঁজা চলছে? [pending] = এখনো উত্তর দেয়নি এমন add-on সংখ্যা। */
    val searching: Boolean = false,
    val pending: Int = 0,
    val total: Int = 0,
    val sources: List<TitleSource> = emptyList(),
    val sourceErrors: List<String> = emptyList(),
    val noAddons: Boolean = false,
)

@HiltViewModel
class TitleViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val tmdb: TmdbRepository,
    private val finder: SourceFinder,
    private val playerManager: PlayerManager,
    private val watchProgress: WatchProgressRepository,
) : ViewModel() {

    private val type: String = savedState.get<String>("type") ?: "movie"
    private val tmdbId: Long = savedState.get<Long>("id") ?: 0L
    val isTv get() = type == "tv"

    private val _ui = MutableStateFlow(TitleUi())
    val ui: StateFlow<TitleUi> = _ui.asStateFlow()

    private val _playing = MutableStateFlow<ChannelEntity?>(null)
    val playing: StateFlow<ChannelEntity?> = _playing.asStateFlow()
    val player get() = playerManager
    val playerState: StateFlow<PlayerUiState> = playerManager.state

    private var findJob: Job? = null
    private var playedAddon: String? = null

    init { load() }

    fun load() {
        viewModelScope.launch {
            _ui.update { TitleUi(loading = true) }
            try {
                val d = tmdb.detail(type, tmdbId)
                _ui.update { it.copy(loading = false, detail = d) }
                if (isTv) {
                    val first = d.seasons.firstOrNull { it.n >= 1 } ?: d.seasons.firstOrNull()
                    if (first != null) selectSeason(first.n)
                } else {
                    findSources(null, null)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(loading = false, error = e.message ?: "Could not load this title.") }
            }
        }
    }

    fun selectSeason(n: Int) {
        findJob?.cancel()
        _ui.update { it.copy(season = n, episodes = emptyList(), episodesLoading = true, episodesError = null, episode = null, sources = emptyList(), searching = false) }
        viewModelScope.launch {
            try {
                val eps = tmdb.season(tmdbId, n)
                if (_ui.value.season == n) _ui.update { it.copy(episodes = eps, episodesLoading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (_ui.value.season == n) _ui.update { it.copy(episodesLoading = false, episodesError = e.message ?: "Could not load episodes.") }
            }
        }
    }

    fun selectEpisode(ep: TmdbEpisode) {
        _ui.update { it.copy(episode = ep) }
        findSources(_ui.value.season, ep)
    }

    /** সোর্স তালিকা থেকে এপিসোড তালিকায় ফেরা। */
    fun backToEpisodes() {
        findJob?.cancel()
        _ui.update { it.copy(episode = null, sources = emptyList(), searching = false, sourceErrors = emptyList(), noAddons = false) }
    }

    fun retrySources() = findSources(_ui.value.season, _ui.value.episode)

    /** [autoPlayFrom] != null: পরের এপিসোডে নিজে থেকে (একই add-on-এর প্রথম সোর্স) চালু করে। */
    private fun findSources(season: Int?, ep: TmdbEpisode?, autoPlayFrom: String? = null) {
        val d = _ui.value.detail ?: return
        findJob?.cancel()
        findJob = viewModelScope.launch {
            _ui.update { it.copy(searching = true, sources = emptyList(), sourceErrors = emptyList(), noAddons = false, pending = 0, total = 0) }
            val list = finder.capableAddons()
            if (list.isEmpty()) {
                _ui.update { it.copy(searching = false, noAddons = true) }
                if (autoPlayFrom != null) closePlayer()
                return@launch
            }
            _ui.update { it.copy(pending = list.size, total = list.size) }
            val q = TitleQuery(
                tmdbId = tmdbId, imdbId = d.imdbId, type = type, title = d.item.title,
                originalTitle = d.item.originalTitle, year = d.item.year, season = season, episode = ep?.n,
            )
            var autoStarted = false
            try {
                finder.find(q, list).collect { batch ->
                    _ui.update {
                        it.copy(
                            pending = (it.pending - 1).coerceAtLeast(0),
                            sources = it.sources + batch.sources,
                            sourceErrors = if (batch.error != null) it.sourceErrors + "${batch.addonName}: ${batch.error}" else it.sourceErrors,
                        )
                    }
                    if (autoPlayFrom != null && !autoStarted) {
                        val pick = batch.sources.firstOrNull()?.takeIf { batch.addonName == autoPlayFrom }
                        if (pick != null) { autoStarted = true; play(pick) }
                    }
                }
            } finally {
                _ui.update { it.copy(searching = false, pending = 0) }
            }
            if (autoPlayFrom != null && !autoStarted) {
                // পছন্দের add-on পায়নি: যেকোনো প্রথম সোর্স, নইলে বন্ধ
                _ui.value.sources.firstOrNull()?.let { play(it) } ?: closePlayer()
            }
        }
    }

    fun play(src: TitleSource) {
        val d = _ui.value.detail ?: return
        val season = _ui.value.season
        val ep = _ui.value.episode
        val name = if (isTv && season != null && ep != null) {
            "${d.item.title} S%02dE%02d".format(season, ep.n) + (ep.name.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
        } else d.item.title
        val entity = ChannelEntity(
            // সোর্স বদলালেও একই আইডি: দেখার অগ্রগতি ধরে থাকে
            id = stableId("tmdb:$type:$tmdbId:${season ?: 0}:${ep?.n ?: 0}"),
            number = 0, name = name, logoUrl = TmdbImages.poster(d.item.poster),
            streamUrl = src.streamUrl, groupId = 0L, tvgId = null,
            kind = if (isTv) "SERIES" else "MOVIE",
            season = if (isTv) season else null, episode = if (isTv) ep?.n else null,
            serieTitle = if (isTv) d.item.title else null,
        )
        playedAddon = src.addonName
        _playing.value = entity
        viewModelScope.launch {
            val resume = watchProgress.resumePositionFor(entity.id)
            playerManager.play(entity, resumeAtMs = resume ?: C.TIME_UNSET)
        }
    }

    /** ভিডিও শেষ: সিরিজে পরের এপিসোড (একই add-on থেকে) চালু করে true; নইলে false। */
    fun playNext(): Boolean {
        if (!isTv) return false
        val ui = _ui.value
        val cur = ui.episode ?: return false
        val next = ui.episodes.firstOrNull { it.n > cur.n } ?: return false
        _ui.update { it.copy(episode = next) }
        findSources(ui.season, next, autoPlayFrom = playedAddon)
        return true
    }

    fun closePlayer() {
        playerManager.stopAndClear()
        _playing.value = null
    }

    override fun onCleared() {
        if (_playing.value != null) playerManager.stopAndClear()
        super.onCleared()
    }
}
