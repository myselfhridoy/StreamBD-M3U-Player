package com.streambd.iptv.ui.player

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.exoplayer.ExoPlayer
import coil.compose.AsyncImage
import com.streambd.iptv.data.epg.NowNext
import com.streambd.iptv.player.PlayerUiState
import com.streambd.iptv.ui.theme.*
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * ZalTV-স্টাইল ফুলস্ক্রিন প্লেয়ার।
 *
 * - [listContent] দিলে (Live) ট্যাপ/OK তালিকা টগল করে, [onStep] দিয়ে Left/Right চ্যানেল বদলায়।
 * - [listContent] না দিলে (Movies/Series) ট্যাপে কন্ট্রোল টগল, OK-তে play/pause, Left/Right-এ ±১০ সেকেন্ড।
 * - কন্ট্রোল প্যানেল: উপরে সোয়াইপ/Up দেখায়, নিচে সোয়াইপ/Down লুকায়, ৪ সেকেন্ডে নিজে লুকায়।
 */
@Composable
fun FullscreenPlayer(
    player: ExoPlayer,
    state: PlayerUiState,
    isFavorite: Boolean,
    isLocked: Boolean,
    kindLabel: String,
    groupName: String,
    nowNext: NowNext? = null,
    showNumber: Boolean = true,
    onExit: () -> Unit,
    onTogglePlay: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleLock: () -> Unit,
    onOpenTracks: () -> Unit,
    onOpenSettings: () -> Unit,
    onStep: ((Int) -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
    listOpen: Boolean = false,
    onListOpenChange: (Boolean) -> Unit = {},
    listContent: (@Composable BoxScope.() -> Unit)? = null,
) {
    val hasList = listContent != null
    val activity = LocalContext.current as? ComponentActivity
    val view = LocalView.current
    val focusManager = LocalFocusManager.current

    // ফুলস্ক্রিন: সিস্টেম বার লুকানো, স্ক্রিন জাগানো
    DisposableEffect(activity) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        view.keepScreenOn = true
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            view.keepScreenOn = false
        }
    }

    val rootFocus = remember { FocusRequester() }
    val controlsFocus = remember { FocusRequester() }
    var controlsVisible by remember { mutableStateOf(false) }
    var interactive by remember { mutableStateOf(false) } // TV: ফোকাস কন্ট্রোলের ভেতরে
    var tick by remember { mutableIntStateOf(0) }          // প্রতিটা ইনপুটে ৪ সেকেন্ডের টাইমার রিসেট

    fun showControls(interactiveMode: Boolean = false) {
        controlsVisible = true
        if (interactiveMode) interactive = true
        tick++
    }

    fun hideControls() {
        controlsVisible = false
        interactive = false
    }

    val listOpenNow by rememberUpdatedState(listOpen)
    val hasListNow by rememberUpdatedState(hasList)
    val listChangeNow by rememberUpdatedState(onListOpenChange)

    // ৪ সেকেন্ড পর অটো-হাইড (pause/buffering/error-এ খোলা থাকে)
    LaunchedEffect(controlsVisible, tick, state.isPlaying) {
        if (controlsVisible && state.isPlaying) {
            delay(4_000L)
            controlsVisible = false
            interactive = false
        }
    }

    // নতুন চ্যানেল শুরু বা error হলে তথ্য দেখানো
    LaunchedEffect(state.current?.id) {
        if (state.current != null) showControls()
    }
    LaunchedEffect(state.error) {
        if (state.error != null) showControls()
    }
    // তালিকা খুললে কন্ট্রোল সরে যায়
    LaunchedEffect(listOpen) {
        if (listOpen) hideControls()
    }

    // ফোকাস কোথায় থাকবে: তালিকা খোলা → তালিকা নিজে; কন্ট্রোলে ঢুকলে → play বাটন; নইলে root
    LaunchedEffect(controlsVisible, interactive, listOpen) {
        if (listOpen) return@LaunchedEffect
        val target = if (controlsVisible && interactive) controlsFocus else rootFocus
        runCatching { target.requestFocus() }
    }

    BackHandler(enabled = true) {
        if (listOpen && hasList && state.current != null) onListOpenChange(false) else onExit()
    }

    fun stepOrSeek(delta: Int) {
        val step = onStep
        if (step != null) {
            step(delta)
        } else {
            val duration = player.duration
            val target = player.currentPosition + delta * 10_000L
            player.seekTo(if (duration > 0) target.coerceIn(0L, duration) else target.coerceAtLeast(0L))
            showControls()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(onTap = {
                    when {
                        hasListNow -> listChangeNow(!listOpenNow)
                        controlsVisible -> hideControls()
                        else -> showControls()
                    }
                })
            }
            .pointerInput(Unit) {
                val threshold = 48.dp.toPx()
                var total = 0f
                detectVerticalDragGestures(
                    onDragStart = { total = 0f },
                    onDragCancel = { total = 0f },
                    onDragEnd = {
                        if (!listOpenNow) {
                            if (total < -threshold) showControls()
                            else if (total > threshold) hideControls()
                        }
                    },
                    onVerticalDrag = { _, dy -> total += dy },
                )
            }
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (controlsVisible) tick++

                // মিডিয়া ও চ্যানেল কী সবসময় কাজ করে
                when (event.key) {
                    Key.MediaPlayPause -> { onTogglePlay(); return@onPreviewKeyEvent true }
                    Key.MediaPlay -> { if (!state.isPlaying) onTogglePlay(); return@onPreviewKeyEvent true }
                    Key.MediaPause -> { if (state.isPlaying) onTogglePlay(); return@onPreviewKeyEvent true }
                    Key.ChannelUp -> { stepOrSeek(1); return@onPreviewKeyEvent true }
                    Key.ChannelDown -> { stepOrSeek(-1); return@onPreviewKeyEvent true }
                    else -> Unit
                }

                // তালিকা খোলা থাকলে নেভিগেশন ফোকাস সিস্টেমের হাতে
                if (listOpen) return@onPreviewKeyEvent false

                val repeat = event.nativeKeyEvent.repeatCount > 0
                val inControls = controlsVisible && interactive
                when (event.key) {
                    Key.DirectionUp -> {
                        if (inControls) focusManager.moveFocus(FocusDirection.Up) else showControls(true)
                        true
                    }
                    Key.DirectionDown -> {
                        if (inControls && !focusManager.moveFocus(FocusDirection.Down)) hideControls()
                        true
                    }
                    Key.DirectionLeft, Key.DirectionRight -> {
                        if (inControls) {
                            false
                        } else {
                            // চ্যানেল বদলে key-repeat উপেক্ষা (এক চাপে এক চ্যানেল), seek-এ repeat চলে
                            if (!repeat || onStep == null) stepOrSeek(if (event.key == Key.DirectionRight) 1 else -1)
                            true
                        }
                    }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        if (inControls) {
                            false
                        } else {
                            if (!repeat) {
                                when {
                                    state.error != null -> (onRetry ?: onTogglePlay)()
                                    hasList -> onListOpenChange(true)
                                    else -> onTogglePlay()
                                }
                            }
                            true
                        }
                    }
                    else -> false
                }
            },
    ) {
        PlayerSurface(
            player = player,
            aspectMode = state.aspectMode,
            modifier = Modifier.fillMaxSize(),
        )

        if (state.isBuffering && state.error == null) {
            CircularProgressIndicator(color = Accent, modifier = Modifier.align(Alignment.Center))
        }

        state.error?.let { message ->
            ErrorOverlay(
                message = message,
                onRetry = onRetry,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        val ch = state.current
        AnimatedVisibility(
            visible = controlsVisible && !listOpen && ch != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            if (ch != null) {
                ControlsPanel(
                    state = state,
                    channelName = if (showNumber) "%02d  ${ch.name}".format(ch.number) else ch.name,
                    logoUrl = ch.logoUrl,
                    groupName = groupName,
                    kindLabel = kindLabel,
                    nowNext = nowNext,
                    isFavorite = isFavorite,
                    isLocked = isLocked,
                    playFocus = controlsFocus,
                    onSeek = { ms -> player.seekTo(ms); tick++ },
                    onTogglePlay = { tick++; onTogglePlay() },
                    onToggleFavorite = { tick++; onToggleFavorite() },
                    onToggleLock = { tick++; onToggleLock() },
                    onOpenTracks = { tick++; onOpenTracks() },
                    onOpenAspect = { tick++; onOpenSettings() },
                )
            }
        }

        AnimatedVisibility(
            visible = listOpen && listContent != null,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(Modifier.fillMaxSize()) {
                listContent?.invoke(this)
            }
        }
    }
}

/* ------------------------------ controls ------------------------------ */

@Composable
private fun ControlsPanel(
    state: PlayerUiState,
    channelName: String,
    logoUrl: String?,
    groupName: String,
    kindLabel: String,
    nowNext: NowNext?,
    isFavorite: Boolean,
    isLocked: Boolean,
    playFocus: FocusRequester,
    onSeek: (Long) -> Unit,
    onTogglePlay: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleLock: () -> Unit,
    onOpenTracks: () -> Unit,
    onOpenAspect: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.88f))))
            .navigationBarsPadding()
            .displayCutoutPadding()
            .padding(start = 24.dp, end = 24.dp, top = 40.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            AsyncImage(
                model = logoUrl,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(6.dp)),
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(channelName, color = TextPrimary, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(groupName, color = TextMuted, fontSize = 12.sp, maxLines = 1)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (kindLabel != "LIVE") InfoChip(kindLabel)
                    aspectLabel(state.videoAspect)?.let { InfoChip(it) }
                    if (state.videoWidth > 0 && state.videoHeight > 0) {
                        InfoChip("${state.videoWidth}x${state.videoHeight}")
                    }
                }
                if (nowNext != null) {
                    Spacer(Modifier.height(6.dp))
                    NowNextStrip(nowNext)
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ControlButton(
                icon = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                description = if (state.isPlaying) "Pause" else "Play",
                modifier = Modifier.focusRequester(playFocus),
                onClick = onTogglePlay,
            )
            if (state.duration > 0L) {
                Spacer(Modifier.width(8.dp))
                ThinSeekBar(
                    position = state.position,
                    duration = state.duration,
                    onSeek = onSeek,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "${formatClock(state.position)} / ${formatClock(state.duration)}",
                    color = TextPrimary,
                    fontSize = 12.sp,
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            ControlButton(
                icon = if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                description = "Favorite",
                tint = if (isFavorite) Accent else TextPrimary,
                onClick = onToggleFavorite,
            )
            Spacer(Modifier.width(4.dp))
            ControlButton(
                icon = if (isLocked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                description = "Lock",
                tint = if (isLocked) Accent else TextPrimary,
                onClick = onToggleLock,
            )
            Spacer(Modifier.width(4.dp))
            ControlButton(icon = Icons.Filled.Settings, description = "Tracks", onClick = onOpenTracks)
            Spacer(Modifier.weight(1f))
            ControlButton(icon = Icons.Filled.AspectRatio, description = "Aspect ratio", onClick = onOpenAspect)
        }
    }
}

@Composable
private fun ControlButton(
    icon: ImageVector,
    description: String,
    modifier: Modifier = Modifier,
    tint: Color = TextPrimary,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Icon(
        imageVector = icon,
        contentDescription = description,
        tint = tint,
        modifier = modifier
            .size(40.dp)
            .clip(shape)
            .background(if (focused) Color.White.copy(alpha = 0.18f) else Color.Transparent)
            .border(if (focused) 2.dp else 0.dp, if (focused) Accent else Color.Transparent, shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(8.dp),
    )
}

/** পাতলা seekbar: ট্যাপ বা টেনে সিক। টানা শেষ হলে একবারই seek হয়। */
@Composable
private fun ThinSeekBar(
    position: Long,
    duration: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val seek by rememberUpdatedState(onSeek)
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val fraction = dragFraction ?: (position.toFloat() / duration.coerceAtLeast(1L)).coerceIn(0f, 1f)

    Box(
        modifier
            .height(28.dp)
            .pointerInput(duration) {
                detectTapGestures { offset ->
                    seek(((offset.x / size.width).coerceIn(0f, 1f) * duration).toLong())
                }
            }
            .pointerInput(duration) {
                detectHorizontalDragGestures(
                    onDragStart = { offset -> dragFraction = (offset.x / size.width).coerceIn(0f, 1f) },
                    onDragCancel = { dragFraction = null },
                    onDragEnd = {
                        dragFraction?.let { seek((it * duration).toLong()) }
                        dragFraction = null
                    },
                    onHorizontalDrag = { change, _ ->
                        dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                    },
                )
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val cy = size.height / 2f
            val inset = 6.dp.toPx()
            val width = size.width - inset * 2
            val stroke = 2.dp.toPx()
            val x = inset + width * fraction
            drawLine(Color.White.copy(alpha = 0.35f), Offset(inset, cy), Offset(inset + width, cy), stroke)
            drawLine(Accent, Offset(inset, cy), Offset(x, cy), stroke)
            drawCircle(Color.White, radius = 5.dp.toPx(), center = Offset(x, cy))
        }
    }
}

@Composable
private fun InfoChip(text: String) {
    Text(
        text = text,
        color = TextPrimary,
        fontSize = 10.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color.White.copy(alpha = 0.18f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun ErrorOverlay(message: String, onRetry: (() -> Unit)?, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.75f))
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, color = TextPrimary, fontSize = 15.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        if (onRetry != null) {
            Text(
                "RETRY",
                color = Color.Black,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Accent)
                    .clickable(onClick = onRetry)
                    .padding(horizontal = 18.dp, vertical = 8.dp),
            )
            Spacer(Modifier.height(6.dp))
            Text("or press OK", color = TextMuted, fontSize = 11.sp)
        } else {
            Text("Press play to try again", color = TextMuted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun NowNextStrip(nowNext: NowNext) {
    val timeFormat = remember {
        java.text.SimpleDateFormat("h:mm", java.util.Locale.getDefault())
    }

    Column(Modifier.widthIn(max = 380.dp)) {
        nowNext.current?.let { program ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("NOW", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                Text(
                    program.title,
                    color = TextPrimary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                Text(timeFormat.format(java.util.Date(program.start)), color = TextMuted, fontSize = 10.sp)
            }
        }
        if (nowNext.current != null && nowNext.next != null) Spacer(Modifier.height(4.dp))
        nowNext.next?.let { program ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("NEXT", color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                Text(
                    program.title,
                    color = TextMuted,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                Text(timeFormat.format(java.util.Date(program.start)), color = TextMuted, fontSize = 10.sp)
            }
        }
    }
}

/** 1.777 → "16:9"। চেনা অনুপাত না হলে "2.35:1" আকারে। */
private fun aspectLabel(aspect: Float): String? {
    if (aspect <= 0f) return null
    val known = listOf(
        16f / 9f to "16:9",
        4f / 3f to "4:3",
        21f / 9f to "21:9",
        16f / 10f to "16:10",
        3f / 2f to "3:2",
        1f to "1:1",
    )
    val nearest = known.minByOrNull { abs(it.first - aspect) }
    return if (nearest != null && abs(nearest.first - aspect) < 0.06f) nearest.second
    else "%.2f:1".format(aspect)
}
