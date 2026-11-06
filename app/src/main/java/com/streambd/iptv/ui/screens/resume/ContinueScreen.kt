package com.streambd.iptv.ui.screens.resume

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C as Media3C
import androidx.media3.common.Player
import coil.compose.AsyncImage
import com.streambd.iptv.ui.components.AppDialog
import com.streambd.iptv.ui.components.AppDialogButton
import com.streambd.iptv.ui.player.FullscreenPlayer
import com.streambd.iptv.ui.player.PlayerDialogs
import com.streambd.iptv.ui.player.formatClock
import com.streambd.iptv.ui.player.rememberPlayerDialogs
import com.streambd.iptv.ui.theme.*
import com.streambd.iptv.util.DeviceType
import kotlinx.coroutines.delay

/** অসম্পূর্ণ দেখা মুভি/এপিসোড (M3U ও FTP) — যেখানে থেমেছিল সেখান থেকে চালু করে। */
@Composable
fun ContinueScreen(
    onBack: () -> Unit,
    vm: ContinueViewModel = hiltViewModel(),
) {
    val ctx = LocalContext.current
    val isTv = remember { DeviceType.isTv(ctx) }
    val items by vm.items.collectAsStateWithLifecycle()
    val playing by vm.playing.collectAsStateWithLifecycle()
    val playerState by vm.playerState.collectAsStateWithLifecycle()
    val unlocked by vm.sessionUnlocked.collectAsStateWithLifecycle()
    val dialogs = rememberPlayerDialogs()

    var menuFor by remember { mutableStateOf<ContinueItem?>(null) }
    var pendingUnlock by remember { mutableStateOf<ContinueItem?>(null) }

    fun open(item: ContinueItem, fromStart: Boolean = false) {
        if (item.channel?.isLocked == true && !unlocked) {
            pendingUnlock = item
        } else {
            vm.play(item, fromStart)
        }
    }

    // ভিডিও শেষ হলে তালিকায় ফেরে (শেষ হওয়া আইটেম তালিকা থেকে সরে যায়)
    val exo = vm.player.player
    DisposableEffect(exo, playing != null) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) vm.closePlayer()
            }
        }
        if (playing != null) exo.addListener(listener)
        onDispose { exo.removeListener(listener) }
    }
    DisposableEffect(vm) { onDispose { vm.player.pause() } }

    val current = playing
    if (current != null) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            FullscreenPlayer(
                player = exo,
                state = playerState,
                isFavorite = false,
                isLocked = false,
                kindLabel = if (current.isFtp) "FTP" else current.entry.kind,
                groupName = "Continue Watching",
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
            dialogs = dialogs,
            state = playerState,
            items = emptyList(),
            pendingUnlock = null,
            verifyPin = { false },
            onUnlocked = {},
            onDismissUnlock = {},
            onApplyTracks = { video, audio ->
                vm.player.applyTrackChoice(Media3C.TRACK_TYPE_VIDEO, video)
                vm.player.applyTrackChoice(Media3C.TRACK_TYPE_AUDIO, audio)
            },
            onAspectChange = vm.player::setAspectMode,
            onToggleFavorite = {},
            onToggleLock = {},
        )
        return
    }

    val firstFocus = remember { FocusRequester() }
    val list = items
    LaunchedEffect(list?.isNotEmpty(), menuFor, pendingUnlock) {
        if (list.isNullOrEmpty() || menuFor != null || pendingUnlock != null) return@LaunchedEffect
        delay(80)
        if (isTv) runCatching { firstFocus.requestFocus() }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(BgDark)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Continue Watching",
                color = TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (!list.isNullOrEmpty()) {
                Text("Hold OK for options", color = TextMuted, fontSize = 12.sp)
            }
        }

        Box(Modifier.fillMaxSize()) {
            when {
                list == null -> CircularProgressIndicator(color = Accent, modifier = Modifier.align(Alignment.Center))
                list.isEmpty() -> Text(
                    "Nothing to continue yet.\nMovies and episodes you stop halfway will appear here.",
                    color = TextMuted,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(list, key = { _, it -> it.entry.id }) { index, item ->
                        ContinueRow(
                            item = item,
                            modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
                            onClick = { open(item) },
                            onLongClick = { menuFor = item },
                        )
                    }
                }
            }
        }
    }

    menuFor?.let { item ->
        ContinueMenu(
            item = item,
            onResume = { menuFor = null; open(item) },
            onStartOver = { menuFor = null; open(item, fromStart = true) },
            onRemove = { menuFor = null; vm.remove(item) },
            onDismiss = { menuFor = null },
        )
    }

    // লক করা M3U মুভি/এপিসোডের জন্য PIN
    PlayerDialogs(
        dialogs = dialogs,
        state = playerState,
        items = emptyList(),
        pendingUnlock = pendingUnlock?.channel,
        verifyPin = vm::verifyPin,
        onUnlocked = {
            pendingUnlock?.let { target ->
                pendingUnlock = null
                vm.unlockAndPlay(target)
            }
        },
        onDismissUnlock = { pendingUnlock = null },
        onApplyTracks = { _, _ -> },
        onAspectChange = {},
        onToggleFavorite = {},
        onToggleLock = {},
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContinueRow(
    item: ContinueItem,
    modifier: Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    val e = item.entry
    val fraction = if (e.durationMs > 0) (e.positionMs.toFloat() / e.durationMs).coerceIn(0f, 1f) else 0f
    val label = if (item.isFtp) "FTP" else e.kind

    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(shape)
            .background(if (focused) Surface1 else Color.Transparent)
            .border(2.dp, if (focused) Accent else Color.Transparent, shape)
            .onFocusChanged { focused = it.isFocused }
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(56.dp).clip(RoundedCornerShape(6.dp)).background(Surface1),
            contentAlignment = Alignment.Center,
        ) {
            if (!e.logoUrl.isNullOrBlank()) {
                AsyncImage(
                    model = e.logoUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(Icons.Filled.Movie, contentDescription = null, tint = TextMuted, modifier = Modifier.size(28.dp))
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(e.title, color = TextPrimary, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text(
                "$label  •  ${formatClock(e.positionMs)} / ${formatClock(e.durationMs)}",
                color = TextMuted,
                fontSize = 12.sp,
                maxLines = 1,
            )
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.25f)),
            ) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(Accent))
            }
        }
    }
}

@Composable
private fun ContinueMenu(
    item: ContinueItem,
    onResume: () -> Unit,
    onStartOver: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val first = remember { FocusRequester() }
    AppDialog(onDismiss = onDismiss) {
        Text(
            item.entry.title,
            color = TextPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 8.dp),
        )
        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            AppDialogButton("RESUME", Accent, Modifier.focusRequester(first), onResume)
            AppDialogButton("START FROM BEGINNING", TextPrimary, onClick = onStartOver)
            AppDialogButton("REMOVE FROM LIST", Danger, onClick = onRemove)
            AppDialogButton("CANCEL", TextMuted, onClick = onDismiss)
        }
    }
    LaunchedEffect(Unit) { delay(100); runCatching { first.requestFocus() } }
}
