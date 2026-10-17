package com.streambd.iptv.ui.screens.guide

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.streambd.iptv.data.db.ChannelEntity
import com.streambd.iptv.data.db.ProgramEntity
import com.streambd.iptv.ui.theme.*
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private const val SLOT_MS = 30L * 60 * 1000
private const val SLOT_COUNT = 12
private val SLOT_WIDTH: Dp = 100.dp
private val CHANNEL_COL_WIDTH: Dp = 140.dp
private val ROW_HEIGHT: Dp = 68.dp

@Composable
fun GuideScreen(onBack: () -> Unit, vm: GuideViewModel = hiltViewModel()) {
    val settings by vm.settingsFlow.collectAsStateWithLifecycle()
    val groups by vm.groups.collectAsStateWithLifecycle()
    val channels by vm.channelsFiltered.collectAsStateWithLifecycle()
    val programs by vm.programsByTvg.collectAsStateWithLifecycle()
    val selected by vm.selectedGroup.collectAsStateWithLifecycle()
    val hourOffset by vm.hourOffsetFlow.collectAsStateWithLifecycle()
    var showGroupDialog by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            now = System.currentTimeMillis()
        }
    }

    val shiftMs = settings.epgTimeShiftMins * 60_000L
    val windowStart = remember(now, hourOffset) {
        val aligned = ((now - SLOT_MS) / SLOT_MS) * SLOT_MS
        aligned + hourOffset * 3_600_000L
    }
    val windowEnd = windowStart + SLOT_COUNT * SLOT_MS
    val horizontalScroll = rememberScrollState()

    Column(Modifier.fillMaxSize().background(BgDark)) {
        TopBar(now, onBack)
        InfoHeader(
            programs = channels.firstOrNull()?.tvgId?.let { programs[it] }.orEmpty(),
            now = now,
            windowStart = windowStart,
            windowEnd = windowEnd,
            shiftMs = shiftMs,
        )
        ControlsRow(
            label = vm.groupLabel(selected, groups),
            dateLabel = formatDate(windowStart),
            onOpenGroups = { showGroupDialog = true },
            onLeft = { vm.shiftWindowBy(-1) },
            onRight = { vm.shiftWindowBy(1) },
            onToday = vm::resetWindow,
        )

        Row(Modifier.fillMaxWidth().height(30.dp).background(TopBar)) {
            Spacer(Modifier.width(CHANNEL_COL_WIDTH))
            Box(Modifier.fillMaxHeight().horizontalScroll(horizontalScroll)) {
                TimelineRuler(windowStart, SLOT_COUNT, shiftMs)
            }
        }

        LazyColumn(Modifier.fillMaxSize()) {
            items(channels, key = { it.id }) { channel ->
                Row(Modifier.fillMaxWidth().height(ROW_HEIGHT)) {
                    ChannelCell(channel)
                    Box(Modifier.fillMaxHeight().weight(1f).horizontalScroll(horizontalScroll)) {
                        ProgramStrip(
                            programs = channel.tvgId?.let { programs[it] }.orEmpty(),
                            windowStart = windowStart,
                            shiftMs = shiftMs,
                            now = now,
                        )
                    }
                }
            }
            if (channels.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                        Text("No channels in this group", color = TextMuted)
                    }
                }
            }
        }
    }

    if (showGroupDialog) {
        SelectGroupDialog(
            groups = groups,
            current = selected,
            onPick = { vm.selectGroup(it); showGroupDialog = false },
            onDismiss = { showGroupDialog = false },
        )
    }
}

@Composable
private fun TopBar(now: Long, onBack: () -> Unit) {
    val timeFormat = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }
    Row(
        Modifier.fillMaxWidth().background(TopBar).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.ArrowBack,
            contentDescription = "Back",
            tint = TextPrimary,
            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onBack).padding(8.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text("TV Guide", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        Text(timeFormat.format(Date(now)), color = TextPrimary, fontSize = 15.sp)
    }
}

@Composable
private fun InfoHeader(
    programs: List<ProgramEntity>,
    now: Long,
    windowStart: Long,
    windowEnd: Long,
    shiftMs: Long,
) {
    val timeFormat = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }
    val current = programs.firstOrNull { now >= it.start + shiftMs && now < it.end + shiftMs }
    Row(
        Modifier.fillMaxWidth().background(Panel).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(56.dp).clip(RoundedCornerShape(6.dp)).background(Surface1)
                .border(1.dp, Divider, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text("?", color = TextMuted, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                current?.title ?: "No information available",
                color = TextPrimary,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${timeFormat.format(Date(windowStart))} – ${timeFormat.format(Date(windowEnd))}",
                color = TextMuted,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun ControlsRow(
    label: String,
    dateLabel: String,
    onOpenGroups: () -> Unit,
    onLeft: () -> Unit,
    onRight: () -> Unit,
    onToday: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().background(TopBar).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PillButton(onClick = onOpenGroups) {
            Icon(Icons.Filled.FilterList, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, color = TextPrimary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.weight(1f))
        IconChip(Icons.Filled.ChevronLeft, "Previous hour", onLeft)
        Spacer(Modifier.width(6.dp))
        IconChip(Icons.Filled.ChevronRight, "Next hour", onRight)
        Spacer(Modifier.width(6.dp))
        PillButton(onClick = onToday) { Text(dateLabel, color = TextPrimary, fontSize = 13.sp) }
    }
}

@Composable
private fun PillButton(onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(8.dp)).background(Surface1).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun IconChip(icon: ImageVector, description: String, onClick: () -> Unit) {
    Icon(
        imageVector = icon,
        contentDescription = description,
        tint = TextPrimary,
        modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(Surface1)
            .clickable(onClick = onClick).padding(6.dp),
    )
}

@Composable
private fun TimelineRuler(windowStart: Long, slots: Int, shiftMs: Long) {
    val timeFormat = remember { SimpleDateFormat("h:mm", Locale.getDefault()) }
    Row(Modifier.width(SLOT_WIDTH * slots).fillMaxHeight()) {
        for (index in 0 until slots) {
            val time = windowStart + index * SLOT_MS - shiftMs
            Box(Modifier.width(SLOT_WIDTH).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(1.dp).height(8.dp).background(Divider))
                    Spacer(Modifier.width(4.dp))
                    Text(timeFormat.format(Date(time)), color = TextMuted, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun ChannelCell(channel: ChannelEntity) {
    Row(
        Modifier.width(CHANNEL_COL_WIDTH).fillMaxHeight().background(Panel).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = channel.logoUrl,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(4.dp)),
        )
        Spacer(Modifier.width(8.dp))
        Text(channel.name, color = TextPrimary, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ProgramStrip(programs: List<ProgramEntity>, windowStart: Long, shiftMs: Long, now: Long) {
    val density = LocalDensity.current
    val slotWidthPx = with(density) { SLOT_WIDTH.toPx() }
    val totalWidthPx = slotWidthPx * SLOT_COUNT

    Box(Modifier.width(SLOT_WIDTH * SLOT_COUNT).fillMaxHeight().background(BgDark)) {
        programs.forEach { program ->
            val adjustedStart = program.start + shiftMs
            val adjustedEnd = program.end + shiftMs
            val left = ((adjustedStart - windowStart).toFloat() / SLOT_MS) * slotWidthPx
            val width = ((adjustedEnd - adjustedStart).toFloat() / SLOT_MS) * slotWidthPx
            val clippedLeft = left.coerceAtLeast(0f)
            val clippedRight = (left + width).coerceAtMost(totalWidthPx)
            val clippedWidth = clippedRight - clippedLeft
            if (clippedWidth <= 1f) return@forEach

            val isCurrent = now >= adjustedStart && now < adjustedEnd
            Box(
                Modifier.offset(x = with(density) { clippedLeft.toDp() })
                    .width(with(density) { clippedWidth.toDp() }).fillMaxHeight()
                    .padding(vertical = 6.dp, horizontal = 2.dp).clip(RoundedCornerShape(4.dp))
                    .background(if (isCurrent) Accent.copy(alpha = 0.18f) else Surface1)
                    .border(1.dp, if (isCurrent) Accent else Divider, RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(program.title, color = TextPrimary, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }

        val nowPosition = ((now - windowStart).toFloat() / SLOT_MS) * slotWidthPx
        if (nowPosition in 0f..totalWidthPx) {
            Box(
                Modifier.offset(x = with(density) { nowPosition.toDp() }).width(2.dp)
                    .fillMaxHeight().background(Accent),
            )
            Box(
                Modifier.offset(x = with(density) { (nowPosition - 3f).toDp() })
                    .size(8.dp).clip(RoundedCornerShape(4.dp)).background(Accent),
            )
        }
    }
}

private fun formatDate(time: Long): String {
    val date = Calendar.getInstance().apply { timeInMillis = time }
    val today = Calendar.getInstance()
    val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    val tomorrow = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }
    val dateFormat = SimpleDateFormat("MMM d", Locale.getDefault())
    val prefix = when {
        sameDay(date, today) -> "Today"
        sameDay(date, yesterday) -> "Yesterday"
        sameDay(date, tomorrow) -> "Tomorrow"
        else -> ""
    }
    return if (prefix.isEmpty()) dateFormat.format(Date(time)) else "$prefix, ${dateFormat.format(Date(time))}"
}

private fun sameDay(first: Calendar, second: Calendar): Boolean =
    first.get(Calendar.YEAR) == second.get(Calendar.YEAR) &&
        first.get(Calendar.DAY_OF_YEAR) == second.get(Calendar.DAY_OF_YEAR)