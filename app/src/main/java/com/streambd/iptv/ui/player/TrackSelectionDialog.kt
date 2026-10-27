package com.streambd.iptv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.Format
import androidx.media3.common.Tracks
import com.streambd.iptv.player.TrackChoice
import com.streambd.iptv.player.TrackInfo
import com.streambd.iptv.ui.theme.*
import java.util.Locale

private data class Option(val label: String, val choice: TrackChoice)

private fun keyOf(group: Tracks.Group, index: Int): String {
    val format = group.getTrackFormat(index)
    return listOf(
        format.id.orEmpty(),
        format.sampleMimeType.orEmpty(),
        format.language.orEmpty(),
        format.label.orEmpty(),
    ).joinToString("|")
}

private fun sameChoice(first: TrackChoice, second: TrackChoice): Boolean {
    if (first is TrackChoice.Specific && second is TrackChoice.Specific) {
        return first.index == second.index && first.group.mediaTrackGroup == second.group.mediaTrackGroup
    }
    return first == second
}

private fun audioLabel(format: Format, index: Int): String {
    format.label?.takeIf { it.isNotBlank() }?.let { return it }
    format.language?.takeIf { it.isNotBlank() && it != "und" }?.let { code ->
        val name = Locale.forLanguageTag(code).displayLanguage
        return name.ifBlank { code }
    }
    return "Audio ${index + 1}"
}

@Composable
fun TrackSelectionDialog(
    info: TrackInfo,
    onConfirm: (video: TrackChoice, audio: TrackChoice) -> Unit,
    onDismiss: () -> Unit,
) {
    var tab by remember { mutableStateOf(0) }

    fun initialChoice(key: String?, groups: List<Tracks.Group>): TrackChoice {
        if (key == "") return TrackChoice.None
        if (key == null) return TrackChoice.Auto
        for (group in groups)
            for (index in 0 until group.length) {
                if (keyOf(group, index) == key) return TrackChoice.Specific(group, index)
            }
        return TrackChoice.Auto
    }

    var videoChoice by remember(info.currentVideoKey, info.videoGroups) {
        mutableStateOf(initialChoice(info.currentVideoKey, info.videoGroups))
    }
    var audioChoice by remember(info.currentAudioKey, info.audioGroups) {
        mutableStateOf(initialChoice(info.currentAudioKey, info.audioGroups))
    }

    val options = remember(tab, info) {
        val groups = if (tab == 0) info.videoGroups else info.audioGroups
        buildList {
            add(Option("None", TrackChoice.None))
            add(Option("Auto", TrackChoice.Auto))
            groups.forEach { g ->
                for (i in 0 until g.length) {
                    val fmt = g.getTrackFormat(i)
                    val label = if (tab == 0) {
                        val br = if (fmt.bitrate > 0) "%.2f Mbps".format(fmt.bitrate / 1_000_000.0) else "— Mbps"
                        "${fmt.width} × ${fmt.height}, $br"
                    } else {
                        audioLabel(fmt, i)
                    }
                    add(Option(label, TrackChoice.Specific(g, i)))
                }
            }
        }
    }

    val current = if (tab == 0) videoChoice else audioChoice
    val selectedIndex = options.indexOfFirst { sameChoice(current, it.choice) }.coerceAtLeast(0)
    val listState = rememberLazyListState()
    val startFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        listState.scrollToItem(selectedIndex)
        runCatching { startFocus.requestFocus() }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // উইন্ডো পুরো স্ক্রিন, তাই "বাইরে ট্যাপ" নিজেরাই ধরতে হয়
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) { detectTapGestures { onDismiss() } }
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .width(340.dp)
                    .heightIn(min = 280.dp, max = 340.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Panel)
                    .pointerInput(Unit) { detectTapGestures { } },
            ) {
                Text(
                    "Select tracks",
                    color = TextPrimary,
                    fontSize = 18.sp,
                    modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp),
                )

                Row(Modifier.fillMaxWidth()) {
                    DialogTab("VIDEO", tab == 0, Modifier.weight(1f)) { tab = 0 }
                    DialogTab("AUDIO", tab == 1, Modifier.weight(1f)) { tab = 1 }
                }

                LazyColumn(
                    Modifier
                        .weight(1f)
                        .padding(vertical = 4.dp),
                    state = listState,
                ) {
                    itemsIndexed(options) { index, opt ->
                        RadioRow(
                            label = opt.label,
                            selected = sameChoice(current, opt.choice),
                            modifier = if (index == selectedIndex) Modifier.focusRequester(startFocus) else Modifier,
                        ) {
                            if (tab == 0) videoChoice = opt.choice else audioChoice = opt.choice
                        }
                    }
                }

                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    DialogTextButton("CANCEL", TextMuted, onDismiss)
                    Spacer(Modifier.width(8.dp))
                    DialogTextButton("CONFIRM", Accent) { onConfirm(videoChoice, audioChoice) }
                }
            }
        }
    }
}
