package com.streambd.iptv.ui.screens.vod

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
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

    // এখান থেকে 'চ্যানেল চালু হলে তালিকা বন্ধ' লজিক সরানো হয়েছে।
    // এখন FullscreenPlayer-এর ৬ সেকেন্ডের অটো-হাইড টাইমার এটি সামলাবে।

    val tabs = remember(groups) {
        listOf(PanelTab(null, "All"), PanelTab(VOD_GROUP_FAVORITES, "Favorites")) +
            groups.map { PanelTab(it.id, it.name) }
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
            onRetry = { vm.player.retry() },
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
                                emptyText = "No episodes found.",
                                modifier = Modifier.weight(1f),
                            ) { episode, rowModifier ->
                                PanelRow(
                                    title = episode.displayName,
                                    modifier = rowModifier,
                                    logoUrl = episode.logoUrl,
                                    playing = episode.id == playingId,
                                    locked = episode.isLocked,
                                    favorite = episode.isFavorite,
                                    onLongClick = { dialogs.openActions(episode.id) },
                                ) {
                                    allItems.firstOrNull { it.id == episode.id }?.let(::pick)
                                }
                            }
                        }

                        isSeries && !favoritesFlat -> PanelList(
                            items = seriesGrid,
                            key = { it.name },
                            startIndex = 0,
                            resetKey = selected,
                            emptyText = if (groups.isEmpty()) "No series in your playlist." else "No series in this group.",
                        ) { group, rowModifier ->
                            PanelRow(
                                title = group.name,
                                modifier = rowModifier,
                                logoUrl = group.poster,
                                subtitle = if (group.episodeCount == 1) "1 episode" else "${group.episodeCount} episodes",
                            ) { vm.openSeries(group.name) }
                        }

                        else -> PanelList(
                            items = items,
                            key = { it.id },
                            startIndex = items.indexOfFirst { it.id == playingId },
                            resetKey = selected,
                            emptyText = when {
                                favoritesFlat -> "No favorite series yet.\nLong-press an episode to add one."
                                selected == VOD_GROUP_FAVORITES -> "No favorite $noun yet.\nLong-press an item to add one."
                                groups.isEmpty() -> "No $noun in your playlist."
                                else -> "No $noun in this group."
                            },
                        ) { item, rowModifier ->
                            PanelRow(
                                title = item.name,
                                modifier = rowModifier,
                                logoUrl = item.logoUrl,
                                playing = item.id == playingId,
                                locked = item.isLocked,
                                favorite = item.isFavorite,
                                onLongClick = { dialogs.openActions(item.id) },
                            ) { pick(item) }
                        }
                    }
                }
            },
        )
    }

    // FullscreenPlayer-এর নিজের BackHandler আগে নিবন্ধিত, এটা পরে, তাই এটার অগ্রাধিকার বেশি:
    // এপিসোড তালিকা খোলা থাকলে Back প্রথমে সিরিজ তালিকায় ফেরে।
    BackHandler(enabled = seriesOpen && listOpen) { vm.closeSeries() }

    // শেয়ার্ড ডায়ালগ (Track, Aspect, PIN, Actions) একসাথে হ্যান্ডেল করা হচ্ছে
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
