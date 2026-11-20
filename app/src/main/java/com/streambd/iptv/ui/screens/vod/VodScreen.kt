package com.streambd.iptv.ui.screens.vod

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C as Media3C
import androidx.media3.common.Player
import com.streambd.iptv.data.db.ChannelEntity
import com.streambd.iptv.data.m3u.ChannelKind
import com.streambd.iptv.ui.components.PanelList
import com.streambd.iptv.ui.components.PanelRow
import com.streambd.iptv.ui.components.PanelScaffold
import com.streambd.iptv.ui.components.PanelTab
import com.streambd.iptv.ui.player.FullscreenPlayer
import com.streambd.iptv.ui.player.PlayerDialogs
import com.streambd.iptv.ui.player.rememberPlayerDialogs
import com.streambd.iptv.ui.theme.*

/**
 * Movies/Series স্ক্রিন, LiveScreen-এর মতোই: ফুলস্ক্রিন প্লেয়ারের ওপর বাঁ দিকে তালিকা।
 * Series-এ প্রথমে সিরিজের তালিকা, নির্বাচন করলে এপিসোড।
 */
@Composable
fun VodScreen(
    onBack: () -> Unit,
    onOpenProviders: () -> Unit = {},
    directPlayId: Long? = null,
    vm: VodViewModel = hiltViewModel(),
) {
    val isSeries = vm.kind == ChannelKind.SERIES
    val groups by vm.groups.collectAsStateWithLifecycle()
    val allItems by vm.allItems.collectAsStateWithLifecycle()
    val selected by vm.selectedGroup.collectAsStateWithLifecycle()
    val items by vm.items.collectAsStateWithLifecycle()
    val seriesName by vm.selectedSeriesName.collectAsStateWithLifecycle()
    val seriesGrid by vm.seriesGroups.collectAsStateWithLifecycle()
    val episodes by vm.episodes.collectAsStateWithLifecycle()
    val lazyStatus by vm.lazyStatus.collectAsStateWithLifecycle()
    val rows by vm.rows.collectAsStateWithLifecycle()
    val path by vm.path.collectAsStateWithLifecycle()
    val topTabs by vm.topTabs.collectAsStateWithLifecycle()
    val state by vm.playerState.collectAsStateWithLifecycle()
    val unlocked by vm.sessionUnlocked.collectAsStateWithLifecycle()

    var listOpen by rememberSaveable { mutableStateOf(directPlayId == null) }
    var pendingUnlock by remember { mutableStateOf<ChannelEntity?>(null) }
    val dialogs = rememberPlayerDialogs()

    val seriesOpen = isSeries && seriesName != null
    val favoritesFlat = isSeries && selected == VOD_GROUP_FAVORITES
    val playingId = state.current?.id

    fun pick(item: ChannelEntity) {
        if (item.isLocked && !unlocked) {
            vm.stopForLock()
            pendingUnlock = item
        } else {
            vm.play(item)
        }
    }

    LaunchedEffect(Unit) { vm.onEnter() }

    // Search থেকে সরাসরি চালানো
    LaunchedEffect(directPlayId, allItems) {
        val id = directPlayId ?: return@LaunchedEffect
        val item = allItems.firstOrNull { it.id == id } ?: return@LaunchedEffect
        if (state.current?.id != id) pick(item)
    }

    DisposableEffect(vm) {
        onDispose { vm.player.pause() }
    }

    // সিরিজে এপিসোড শেষ হলে পরের এপিসোড নিজে থেকে চলে
    val exo = vm.player.player
    DisposableEffect(exo) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) vm.playNextEpisode()
            }
        }
        exo.addListener(listener)
        onDispose { exo.removeListener(listener) }
    }

    // এখান থেকে 'চ্যানেল চালু হলে তালিকা বন্ধ' লজিক সরানো হয়েছে।
    // এখন FullscreenPlayer-এর ৬ সেকেন্ডের অটো-হাইড টাইমার এটি সামলাবে।

    val tabs = remember(topTabs) {
        listOf(PanelTab(null, "All"), PanelTab(VOD_GROUP_FAVORITES, "Favorites")) +
            topTabs.map { PanelTab(it.first, it.second) }
    }

    val current = allItems.firstOrNull { it.id == playingId } ?: state.current
    val groupName = groups.firstOrNull { it.id == current?.groupId }?.name ?: vm.title
    val noun = vm.title.lowercase()

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        FullscreenPlayer(
            player = vm.player.player,
            state = state,
            isFavorite = current?.isFavorite == true,
            isLocked = current?.isLocked == true,
            kindLabel = vm.kindLabel,
            groupName = groupName,
            showNumber = false,
            onExit = onBack,
            onTogglePlay = vm::togglePlayPause,
            onToggleFavorite = { current?.let(vm::toggleFavorite) },
            onToggleLock = { current?.let(vm::toggleLock) },
            onOpenTracks = dialogs::openTracks,
            onOpenSettings = dialogs::openAspect,
            onRetry = vm::retry,
            listOpen = listOpen,
            onListOpenChange = { listOpen = it },
            listContent = {
                PanelScaffold(tabs, selected, vm::selectGroup) {
                    when {
                        seriesOpen -> Column(Modifier.fillMaxSize()) {
                            BackRow(seriesName.orEmpty(), onClick = vm::closeSeries)
                            PanelList(
                                items = episodes,
                                key = { it.id },
                                startIndex = episodes.indexOfFirst { it.id == playingId },
                                resetKey = seriesName,
                                emptyText = lazyStatus ?: "No episodes found.",
                                modifier = Modifier.weight(1f),
                            ) { episode, rowModifier ->
                                // নতুন: এপিসোডের ক্ষেত্রে allItems থেকে প্রিমিয়াম চেক করা হচ্ছে
                                val isPrem = allItems.firstOrNull { it.id == episode.id }?.isPremium == true
                                
                                PanelRow(
                                    title = episode.displayName,
                                    modifier = rowModifier,
                                    logoUrl = episode.logoUrl,
                                    subtitle = episode.subtitle,
                                    playing = episode.id == playingId,
                                    locked = episode.isLocked,
                                    favorite = episode.isFavorite,
                                    isPremium = isPrem, // নতুন
                                    // lazy এপিসোডের ডেটাবেসে সারি নেই, তাই ফেভারিট/লক মেনু নেই
                                    onLongClick = if (episode.lazyEntity == null) ({ dialogs.openActions(episode.id) }) else null,
                                ) {
                                    (allItems.firstOrNull { it.id == episode.id } ?: episode.lazyEntity)?.let(::pick)
                                }
                            }
                        }

                        else -> Column(Modifier.fillMaxSize()) {
                            if (path.isNotEmpty()) BackRow(path.joinToString(" \u203A "), onClick = vm::closeFolder)
                            PanelList(
                                items = rows,
                                key = { it.key },
                                startIndex = rows.indexOfFirst { it is VodRow.Item && it.item.id == playingId }
                                    .takeIf { it >= 0 } ?: vm.focusFor(),
                                resetKey = selected to path,
                                focusKey = selected to path,
                                emptyText = when {
                                    favoritesFlat -> "No favorite series yet.\nLong-press an episode to add one."
                                    selected == VOD_GROUP_FAVORITES -> "No favorite $noun yet.\nLong-press an item to add one."
                                    groups.isEmpty() -> "No $noun yet.\nAdd a playlist in Providers (Settings)."
                                    else -> "No $noun in this group."
                                },
                                emptyActionLabel = if (allItems.isEmpty()) "Add a provider" else null,
                                onEmptyAction = onOpenProviders,
                                modifier = Modifier.weight(1f),
                            ) { row, rowModifier ->
                                when (row) {
                                    is VodRow.Folder -> PanelRow(
                                        title = row.name,
                                        modifier = rowModifier,
                                        subtitle = row.count?.let { if (it == 1) "1 title" else "$it titles" },
                                        leadingIcon = Icons.Filled.Folder,
                                        showChevron = true,
                                    ) { vm.openFolder(row.name, rows.indexOf(row)) }

                                    is VodRow.Series -> PanelRow(
                                        title = row.group.name,
                                        modifier = rowModifier,
                                        logoUrl = row.group.poster,
                                        subtitle = when {
                                            row.group.lazyRef != null -> null
                                            row.group.episodeCount == 1 -> "1 episode"
                                            else -> "${row.group.episodeCount} episodes"
                                        },
                                    ) { vm.openSeries(row.group.name) }

                                    is VodRow.Item -> {
                                        val item = row.item
                                        PanelRow(
                                            title = item.name,
                                            modifier = rowModifier,
                                            logoUrl = item.logoUrl,
                                            subtitle = item.subtitle,
                                            playing = item.id == playingId,
                                            locked = item.isLocked,
                                            favorite = item.isFavorite,
                                            isPremium = item.isPremium,
                                            onLongClick = { dialogs.openActions(item.id) },
                                        ) { pick(item) }
                                    }
                                }
                            }
                        }
                    }
                }
            },
        )
    }

    // FullscreenPlayer-এর নিজের BackHandler আগে নিবন্ধিত, এটা পরে, তাই এটার অগ্রাধিকার বেশি:
    // এপিসোড তালিকা খোলা থাকলে Back প্রথমে সিরিজ তালিকায় ফেরে।
    // Back: এপিসোড → সিরিজ তালিকা → (যত স্তর) ফোল্ডার → উপরের স্তর; প্রতি ধাপে আগের সারিতে ফোকাস ফেরে
    BackHandler(enabled = listOpen && (seriesOpen || path.isNotEmpty())) { if (seriesOpen) vm.closeSeries() else vm.closeFolder() }

    PlayerDialogs(
        dialogs = dialogs,
        state = state,
        items = allItems,
        pendingUnlock = pendingUnlock,
        verifyPin = vm::verifyPin,
        onUnlocked = {
            pendingUnlock?.let { target ->
                pendingUnlock = null
                vm.unlockAndPlay(target)
            }
        },
        onDismissUnlock = { pendingUnlock = null },
        onApplyTracks = { video, audio ->
            vm.player.applyTrackChoice(Media3C.TRACK_TYPE_VIDEO, video)
            vm.player.applyTrackChoice(Media3C.TRACK_TYPE_AUDIO, audio)
        },
        onAspectChange = vm::setAspectMode,
        onToggleFavorite = vm::toggleFavorite,
        onToggleLock = vm::toggleLock,
    )
}

/** এপিসোড তালিকার মাথায় "‹ সিরিজের নাম"। */
@Composable
private fun BackRow(title: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(6.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(shape)
            .background(if (focused) Color.White.copy(alpha = 0.15f) else Color.Transparent)
            .border(1.dp, if (focused) Accent else Color.Transparent, shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = Accent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(
            title,
            color = Accent,
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}