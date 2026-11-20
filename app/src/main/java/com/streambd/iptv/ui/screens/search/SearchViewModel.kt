package com.streambd.iptv.ui.screens.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streambd.iptv.data.db.ChannelEntity
import com.streambd.iptv.data.repo.ChannelRepository
import com.streambd.iptv.data.tmdb.TmdbItem
import com.streambd.iptv.data.tmdb.TmdbRepository
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.catch
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repo: ChannelRepository,
    private val tmdb: TmdbRepository,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    val results: StateFlow<List<ChannelEntity>> = _query
        .debounce(250L)
        .distinctUntilChanged()
        .flatMapLatest(repo::searchAll)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** TMDB: ৩+ অক্ষরে সার্চ, ফাঁকা থাকলে ট্রেন্ডিং। ব্যর্থ হলে চুপচাপ খালি (লোকাল সার্চ চলতেই থাকে)। */
    val tmdbResults: StateFlow<List<TmdbItem>> = _query
        .debounce(450L)
        .map { it.trim() }
        .distinctUntilChanged()
        .flatMapLatest { q ->
            flow {
                emit(
                    when {
                        q.isEmpty() -> tmdb.trending()
                        q.length >= 3 -> tmdb.search(q)
                        else -> emptyList()
                    },
                )
            }.catch { emit(emptyList()) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setQuery(query: String) {
        _query.value = query
    }
}
