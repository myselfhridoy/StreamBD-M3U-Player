package com.streambd.iptv.ui.screens.title

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C as Media3C
import androidx.media3.common.Player
import coil.compose.AsyncImage
import com.streambd.iptv.data.addon.TitleSource
import com.streambd.iptv.data.tmdb.TmdbImages
import com.streambd.iptv.ui.player.FullscreenPlayer
import com.streambd.iptv.ui.player.PlayerDialogs
import com.streambd.iptv.ui.player.rememberPlayerDialogs
import com.streambd.iptv.ui.theme.*
import kotlinx.coroutines.delay

/** TMDB শর্ত: ডেটা দেখানো স্ক্রিনে এই নোটিশ থাকতে হবে। */
const val TMDB_NOTICE = "This product uses the TMDB API but is not endorsed or certified by TMDB."

@Composable
fun TitleScreen(onBack: () -> Unit, vm: TitleViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val playing by vm.playing.collectAsStateWithLifecycle()
    val playerState by vm.playerState.collectAsStateWithLifecycle()
    val dialogs = rememberPlayerDialogs()
    val exo = vm.player.player

    // ভিডিও শেষ: সিরিজে পরের এপিসোড, নইলে প্লেয়ার বন্ধ
    DisposableEffect(exo, playing != null) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED && !vm.playNext()) vm.closePlayer()
            }
        }
        if (playing != null) exo.addListener(listener)
        onDispose { exo.removeListener(listener) }
    }
    DisposableEffect(vm) { onDispose { vm.player.pause() } }

    if (playing != null) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            FullscreenPlayer(
                player = exo,
                state = playerState,
                isFavorite = false,
                isLocked = false,
                kindLabel = if (vm.isTv) "SERIES" else "MOVIE",
                groupName = ui.detail?.item?.title.orEmpty(),
                showNumber = false,
                showFavoriteLock = false,
                onExit = vm::closePlayer,
                onTogglePlay = vm.player::togglePlayPause,
                onToggleFavorite = {},
                onToggleLock = {},
                onOpenTracks = dialogs::openTracks,
                onOpenSettings = dialogs::openAspect,
                onRetry = { vm.player.retry() },
            )
        }
        PlayerDialogs(
            dialogs = dialogs, state = playerState, items = emptyList(), pendingUnlock = null,
            verifyPin = { false }, onUnlocked = {}, onDismissUnlock = {},
            onApplyTracks = { video, audio ->
                vm.player.applyTrackChoice(Media3C.TRACK_TYPE_VIDEO, video)
                vm.player.applyTrackChoice(Media3C.TRACK_TYPE_AUDIO, audio)
            },
            onAspectChange = vm.player::setAspectMode,
            onToggleFavorite = {}, onToggleLock = {},
        )
        return
    }

    // Back: সোর্স তালিকা (সিরিজ) → এপিসোড তালিকা → বের হওয়া
    BackHandler(enabled = ui.episode != null) { vm.backToEpisodes() }
    BackHandler(enabled = ui.episode == null) { onBack() }

    Column(Modifier.fillMaxSize().background(BgDark).windowInsetsPadding(WindowInsets.safeDrawing)) {
        when {
            ui.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = Accent) }
            ui.error != null -> Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
                Text(ui.error.orEmpty(), color = TextMuted, fontSize = 15.sp)
                Spacer(Modifier.height(16.dp))
                FocusRow("RETRY", null, modifier = Modifier.width(220.dp)) { vm.load() }
                Spacer(Modifier.height(8.dp))
                FocusRow("BACK", null, modifier = Modifier.width(220.dp)) { onBack() }
            }
            else -> Row(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp)) {
                InfoColumn(ui, Modifier.weight(0.38f).fillMaxHeight())
                Spacer(Modifier.width(20.dp))
                Box(Modifier.weight(0.62f).fillMaxHeight()) { ActionColumn(ui, vm) }
            }
        }
    }
}

@Composable
private fun InfoColumn(ui: TitleUi, modifier: Modifier) {
    val d = ui.detail ?: return
    Column(modifier) {
        Row(Modifier.fillMaxWidth()) {
            AsyncImage(
                model = TmdbImages.poster(d.item.poster), contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.width(110.dp).height(165.dp).clip(RoundedCornerShape(8.dp)).background(Surface1),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(d.item.title, color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                val meta = listOfNotNull(
                    d.item.year.takeIf { it.isNotBlank() },
                    d.runtime.takeIf { it > 0 }?.let { "$it min" },
                    d.item.rating.takeIf { it > 0 }?.let { "★ $it" },
                ).joinToString("  ·  ")
                if (meta.isNotEmpty()) Text(meta, color = TextMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                if (d.genres.isNotEmpty()) Text(d.genres.joinToString(", "), color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
        if (d.tagline.isNotBlank()) Text(d.tagline, color = Accent, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
        Text(
            d.item.overview, color = TextPrimary, fontSize = 13.sp, maxLines = 9, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        Spacer(Modifier.weight(1f))
        Text(TMDB_NOTICE, color = TextMuted, fontSize = 10.sp)
    }
}

@Composable
private fun ActionColumn(ui: TitleUi, vm: TitleViewModel) {
    val d = ui.detail ?: return
    if (vm.isTv && ui.episode == null) {
        // সিজন চিপ + এপিসোড তালিকা
        Column(Modifier.fillMaxSize()) {
            val seasons = d.seasons
            LazyRow(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(seasons, key = { it.n }) { s ->
                    Chip(if (s.n == 0) "Specials" else "Season ${s.n}", selected = s.n == ui.season) { vm.selectSeason(s.n) }
                }
            }
            when {
                ui.episodesLoading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = Accent) }
                ui.episodesError != null -> Text(ui.episodesError, color = TextMuted, fontSize = 14.sp)
                ui.episodes.isEmpty() -> Text("No episodes listed.", color = TextMuted, fontSize = 14.sp)
                else -> {
                    val first = remember(ui.season) { FocusRequester() }
                    LaunchedEffect(ui.season, ui.episodes.size) { delay(120); runCatching { first.requestFocus() } }
                    LazyColumn(Modifier.fillMaxSize()) {
                        itemsIndexed(ui.episodes, key = { _, e -> e.n }) { i, e ->
                            FocusRow(
                                title = "${e.n}. ${e.name}".trim(),
                                subtitle = listOfNotNull(e.airDate.takeIf { it.isNotBlank() }, e.runtime.takeIf { it > 0 }?.let { "$it min" }).joinToString("  ·  ").ifEmpty { null },
                                imageUrl = TmdbImages.still(e.still),
                                modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                            ) { vm.selectEpisode(e) }
                        }
                    }
                }
            }
        }
    } else {
        SourcesPanel(ui, vm)
    }
}

@Composable
private fun SourcesPanel(ui: TitleUi, vm: TitleViewModel) {
    Column(Modifier.fillMaxSize()) {
        val ep = ui.episode
        if (ep != null) {
            Text("S%02dE%02d  ${ep.name}".format(ui.season ?: 0, ep.n), color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
        }
        val status = when {
            ui.noAddons -> "No installed add-on can find sources. Install one that supports search (for example MovieLinkBD)."
            ui.searching -> "Searching ${ui.total} add-on${if (ui.total == 1) "" else "s"}…  ${ui.total - ui.pending}/${ui.total} done"
            ui.sources.isEmpty() -> "No source found in your add-ons."
            else -> "${ui.sources.size} source${if (ui.sources.size == 1) "" else "s"} found"
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
            if (ui.searching) { CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(8.dp)) }
            Text(status, color = TextMuted, fontSize = 13.sp, modifier = Modifier.weight(1f))
            if (!ui.searching && !ui.noAddons) Chip("Retry", selected = false) { vm.retrySources() }
            if (vm.isTv && ep != null) { Spacer(Modifier.width(8.dp)); Chip("Episodes", selected = false) { vm.backToEpisodes() } }
        }
        val first = remember { FocusRequester() }
        LaunchedEffect(ui.sources.isNotEmpty()) { if (ui.sources.isNotEmpty()) { delay(120); runCatching { first.requestFocus() } } }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            itemsIndexed(ui.sources) { i, s ->
                FocusRow(
                    title = s.label,
                    subtitle = listOfNotNull(s.addonName, s.quality, s.subtitle).distinct().joinToString("  ·  "),
                    modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                ) { vm.play(s) }
            }
            if (ui.sourceErrors.isNotEmpty() && !ui.searching && ui.sources.isEmpty()) {
                items(ui.sourceErrors) { Text(it, color = TextMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp)) }
            }
        }
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Text(
        text, fontSize = 13.sp, fontWeight = FontWeight.Medium,
        color = if (selected) Color.Black else TextPrimary,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) Accent else Surface1)
            .border(2.dp, if (focused) Accent else Color.Transparent, RoundedCornerShape(16.dp))
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

@Composable
private fun FocusRowImpl(
    title: String,
    subtitle: String?,
    imageUrl: String?,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier.fillMaxWidth().padding(vertical = 3.dp)
            .clip(RoundedCornerShape(8.dp)).background(if (focused) Panel else Color.Transparent)
            .border(2.dp, if (focused) Accent else Color.Transparent, RoundedCornerShape(8.dp))
            .onFocusChanged { focused = it.isFocused }.clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (imageUrl != null) {
            AsyncImage(
                model = imageUrl, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.width(88.dp).height(50.dp).clip(RoundedCornerShape(6.dp)).background(Surface1),
            )
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) Text(subtitle, color = TextMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun FocusRow(
    title: String,
    subtitle: String?,
    imageUrl: String? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) = FocusRowImpl(title, subtitle, imageUrl, modifier, onClick)
