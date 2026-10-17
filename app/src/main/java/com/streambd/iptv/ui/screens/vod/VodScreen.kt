package com.streambd.iptv.ui.screens.vod

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C as Media3C
import coil.compose.AsyncImage
import com.streambd.iptv.data.db.ChannelEntity
import com.streambd.iptv.data.db.GroupEntity
import com.streambd.iptv.data.m3u.ChannelKind
import com.streambd.iptv.data.vod.EpisodeEntry
import com.streambd.iptv.data.vod.SeriesGroup
import com.streambd.iptv.ui.components.PinDialog
import com.streambd.iptv.ui.components.ChannelActionsDialog
import com.streambd.iptv.ui.player.FullscreenPlayer
import com.streambd.iptv.ui.player.PlayerSettingsDialog
import com.streambd.iptv.ui.player.TrackSelectionDialog
import com.streambd.iptv.ui.player.VolumeOverlay
import com.streambd.iptv.ui.theme.*

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
    var showTracks by remember { mutableStateOf(false) }
    var showPlayerSettings by remember { mutableStateOf(false) }
    var pendingUnlock by remember { mutableStateOf<ChannelEntity?>(null) }
    var actionsItemId by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(directPlayId, allItems) {
        val id = directPlayId ?: return@LaunchedEffect
        val item = allItems.firstOrNull { it.id == id } ?: return@LaunchedEffect
        if (item.isLocked && !unlocked) pendingUnlock = item else vm.play(item)
    }

    DisposableEffect(vm) {
        onDispose { vm.player.pause() }
    }

    val activity = LocalContext.current as? ComponentActivity
    LaunchedEffect(state.isFullscreen) {
        val window = activity?.window ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (state.isFullscreen) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    val seriesOpen = isSeries && seriesName != null
    val favoritesFlat = isSeries && selected == VOD_GROUP_FAVORITES
    BackHandler(enabled = seriesOpen && !state.isFullscreen) { vm.closeSeries() }

    Box(Modifier.fillMaxSize().background(BgDark)) {
        if (!state.isFullscreen) {
            Column(Modifier.fillMaxSize()) {
                if (seriesOpen) {
                    TopBar(seriesName.orEmpty(), showBack = true, onBack = vm::closeSeries)
                } else {
                    TopBar(vm.title, showBack = true, onBack = onBack)
                    GroupTabs(groups, selected, vm::selectGroup)
                }

                when {
                    seriesOpen -> EpisodeList(
                        episodes = episodes,
                        onPick = { episode ->
                            val item = allItems.firstOrNull { it.id == episode.id }
                            if (item != null) {
                                if (item.isLocked && !unlocked) {
                                    vm.stopForLock()
                                    pendingUnlock = item
                                } else {
                                    vm.play(item)
                                }
                            }
                        },
                        onLongPress = { id -> actionsItemId = id },
                    )

                    isSeries && !favoritesFlat -> {
                        if (seriesGrid.isEmpty()) {
                            EmptyState(if (groups.isEmpty()) "No series in your playlist." else "No series in this group.")
                        } else {
                            SeriesGrid(seriesGrid, onPick = { vm.openSeries(it.name) })
                        }
                    }

                    else -> {
                        if (items.isEmpty()) {
                            EmptyState(
                                when {
                                    favoritesFlat ->
                                        "No favorite series yet.\nTap the heart in the player to add one."
                                    selected == VOD_GROUP_FAVORITES ->
                                        "No favorite ${vm.title.lowercase()} yet.\nTap the heart in the player to add one."
                                    groups.isEmpty() -> "No ${vm.title.lowercase()} in your playlist."
                                    else -> "No ${vm.title.lowercase()} in this group."
                                },
                            )
                        } else {
                            PosterGrid(
                                items = items,
                                onPick = { item ->
                                    if (item.isLocked && !unlocked) {
                                        vm.stopForLock()
                                        pendingUnlock = item
                                    } else {
                                        vm.play(item)
                                    }
                                },
                                onLongPress = { id -> actionsItemId = id },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }

        if (state.isFullscreen) {
            val current = allItems.firstOrNull { it.id == state.current?.id } ?: state.current
            val groupName = groups.firstOrNull { it.id == current?.groupId }?.name ?: vm.title
            FullscreenPlayer(
                player = vm.player.player,
                state = state,
                isFavorite = current?.isFavorite == true,
                isLocked = current?.isLocked == true,
                kindLabel = vm.kindLabel,
                groupName = groupName,
                showNumber = false,
                onExit = vm::closeFullscreen,
                onTogglePlay = vm::togglePlayPause,
                onToggleFavorite = { current?.let(vm::toggleFavorite) },
                onToggleLock = { current?.let(vm::toggleLock) },
                onOpenTracks = { showTracks = true },
                onOpenSettings = { showPlayerSettings = true },
            )
        }
    }

    if (state.volumeOverlayVisible) VolumeOverlay(level = state.volumeLevel)

    if (showTracks) {
        TrackSelectionDialog(
            info = state.tracks,
            onConfirm = { video, audio ->
                vm.player.applyTrackChoice(Media3C.TRACK_TYPE_VIDEO, video)
                vm.player.applyTrackChoice(Media3C.TRACK_TYPE_AUDIO, audio)
                showTracks = false
            },
            onDismiss = { showTracks = false },
        )
    }

    if (showPlayerSettings) {
        PlayerSettingsDialog(
            currentAspect = state.aspectMode,
            onAspectChange = vm::setAspectMode,
            onDismiss = { showPlayerSettings = false },
        )
    }

    pendingUnlock?.let { target ->
        PinDialog(
            title = "Enter PIN",
            verify = vm::verifyPin,
            onSuccess = {
                pendingUnlock = null
                vm.unlockAndPlay(target)
            },
            onDismiss = { pendingUnlock = null },
        )
    }

    val actionsItem = actionsItemId?.let { id -> allItems.firstOrNull { it.id == id } }
    if (actionsItem != null) {
        ChannelActionsDialog(
            channel = actionsItem,
            onToggleFavorite = { vm.toggleFavorite(actionsItem) },
            onToggleLock = { vm.toggleLock(actionsItem) },
            onDismiss = { actionsItemId = null },
        )
    }
}

@Composable
private fun TopBar(title: String, showBack: Boolean, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(TopBar).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showBack) {
            Icon(
                Icons.Filled.ArrowBack,
                contentDescription = "Back",
                tint = TextPrimary,
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onBack).padding(8.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = title,
            color = TextPrimary,
            fontSize = 20.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun EmptyState(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(message, color = TextMuted, fontSize = 15.sp)
    }
}

@Composable
private fun SeriesGrid(groups: List<SeriesGroup>, onPick: (SeriesGroup) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 150.dp),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(groups, key = { it.name }) { group ->
            SeriesCell(group) { onPick(group) }
        }
    }
}

@Composable
private fun SeriesCell(group: SeriesGroup, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(
        Modifier.clip(RoundedCornerShape(8.dp)).background(if (focused) Panel else Color.Transparent)
            .border(2.dp, if (focused) Accent else Color.Transparent, RoundedCornerShape(8.dp))
            .onFocusChanged { focused = it.isFocused }.clickable(onClick = onClick).padding(6.dp),
    ) {
        AsyncImage(
            model = group.poster,
            contentDescription = group.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(6.dp)).background(Surface1),
        )
        Spacer(Modifier.height(8.dp))
        Text(group.name, color = TextPrimary, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            if (group.episodeCount == 1) "1 episode" else "${group.episodeCount} episodes",
            color = TextMuted,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun EpisodeList(
    episodes: List<EpisodeEntry>,
    onPick: (EpisodeEntry) -> Unit,
    onLongPress: (Long) -> Unit,
) {
    if (episodes.isEmpty()) {
        EmptyState("No episodes found.")
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        itemsIndexed(episodes, key = { _, episode -> episode.id }) { _, episode ->
            EpisodeRow(
                episode = episode,
                onClick = { onPick(episode) },
                onLongPress = { onLongPress(episode.id) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EpisodeRow(episode: EpisodeEntry, onClick: () -> Unit, onLongPress: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(8.dp)).background(if (focused) Panel else Color.Transparent)
            .border(2.dp, if (focused) Accent else Color.Transparent, RoundedCornerShape(8.dp))
            .onFocusChanged { focused = it.isFocused }
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongPress()
                },
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = episode.logoUrl,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)),
        )
        Spacer(Modifier.width(14.dp))
        Text(
            episode.displayName,
            color = TextPrimary,
            fontSize = 15.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (episode.isLocked) {
            Icon(Icons.Filled.Lock, contentDescription = "Locked", tint = Accent, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
        }
        if (episode.isFavorite) {
            Icon(Icons.Filled.Favorite, contentDescription = "Favorite", tint = Accent, modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
private fun GroupTabs(groups: List<GroupEntity>, selectedId: Long?, onSelect: (Long?) -> Unit) {
    LazyRow(
        Modifier.fillMaxWidth().background(TopBar).padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            GroupTab("Favorites", selectedId == VOD_GROUP_FAVORITES) {
                onSelect(VOD_GROUP_FAVORITES)
            }
        }
        item { GroupTab("All", selectedId == null) { onSelect(null) } }
        items(groups, key = { it.id }) { group ->
            GroupTab(group.name, selectedId == group.id) { onSelect(group.id) }
        }
    }
}

@Composable
private fun GroupTab(label: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val color = when {
        selected -> Accent
        focused -> TextPrimary
        else -> TextMuted
    }
    Text(
        text = label,
        color = color,
        fontSize = 15.sp,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
private fun PosterGrid(
    items: List<ChannelEntity>,
    onPick: (ChannelEntity) -> Unit,
    onLongPress: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 150.dp),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        items(items, key = { it.id }) { item ->
            PosterCell(
                item = item,
                onClick = { onPick(item) },
                onLongPress = { onLongPress(item.id) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PosterCell(item: ChannelEntity, onClick: () -> Unit, onLongPress: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    Column(
        Modifier.clip(RoundedCornerShape(8.dp)).background(if (focused) Panel else Color.Transparent)
            .border(2.dp, if (focused) Accent else Color.Transparent, RoundedCornerShape(8.dp))
            .onFocusChanged { focused = it.isFocused }
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongPress()
                },
            )
            .padding(6.dp),
    ) {
        Box {
            AsyncImage(
                model = item.logoUrl,
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(6.dp)).background(Surface1),
            )
            if (item.isLocked) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(6.dp)
                        .clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.55f))
                        .padding(4.dp),
                ) {
                    Icon(Icons.Filled.Lock, contentDescription = "Locked", tint = Accent, modifier = Modifier.size(16.dp))
                }
            }
            if (item.isFavorite) {
                Box(
                    Modifier.align(Alignment.TopStart).padding(6.dp)
                        .clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.55f))
                        .padding(4.dp),
                ) {
                    Icon(
                        Icons.Filled.Favorite,
                        contentDescription = "Favorite",
                        tint = Accent,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(item.name, color = TextPrimary, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}