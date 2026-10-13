package com.streambd.iptv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.media3.common.C
import androidx.media3.common.Tracks
import com.streambd.iptv.player.TrackChoice
import com.streambd.iptv.player.TrackInfo
import com.streambd.iptv.ui.theme.*

private data class Option(val label: String, val choice: TrackChoice)

@Composable
fun TrackSelectionDialog(
    info: TrackInfo,
    onConfirm: (video: TrackChoice, audio: TrackChoice) -> Unit,
    onDismiss: () -> Unit,
) {
    var tab by remember { mutableStateOf(0) }              // 0 = VIDEO, 1 = AUDIO
    var videoChoice by remember { mutableStateOf<TrackChoice>(TrackChoice.Auto) }
    var audioChoice by remember { mutableStateOf<TrackChoice>(TrackChoice.Auto) }

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
                        fmt.label ?: fmt.language ?: "Unknown"
                    }
                    add(Option(label, TrackChoice.Specific(g, i)))
                }
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Panel)
                .padding(20.dp),
        ) {
            Row(Modifier.fillMaxWidth()) {
                TabHeader("VIDEO", tab == 0, Modifier.weight(1f)) { tab = 0 }
                TabHeader("AUDIO", tab == 1, Modifier.weight(1f)) { tab = 1 }
            }
            Spacer(Modifier.height(8.dp))
            Text("Select tracks", color = TextMuted, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.heightIn(max = 320.dp)) {
                items(options) { opt ->
                    val selected = when (tab) {
                        0 -> videoChoice == opt.choice
                        else -> audioChoice == opt.choice
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .clickable {
                                if (tab == 0) videoChoice = opt.choice else audioChoice = opt.choice
                            }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = selected,
                            onClick = {
                                if (tab == 0) videoChoice = opt.choice else audioChoice = opt.choice
                            },
                            colors = RadioButtonDefaults.colors(selectedColor = Accent, unselectedColor = TextMuted),
                        )
                        Text(opt.label, color = TextPrimary, fontSize = 15.sp)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("CANCEL", color = TextMuted) }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { onConfirm(videoChoice, audioChoice) }) {
                    Text("CONFIRM", color = Accent)
                }
            }
        }
    }
}

@Composable
private fun TabHeader(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val color = if (selected) Accent else TextMuted
    Text(
        text = label,
        color = color,
        fontSize = 13.sp,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
    )
}
