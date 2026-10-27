package com.streambd.iptv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.streambd.iptv.player.AspectMode
import com.streambd.iptv.ui.theme.Accent
import com.streambd.iptv.ui.theme.Panel
import com.streambd.iptv.ui.theme.TextPrimary

@Composable
fun PlayerSettingsDialog(
    currentAspect: AspectMode,
    onAspectChange: (AspectMode) -> Unit,
    onDismiss: () -> Unit,
) {
    val startFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { startFocus.requestFocus() } }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .width(340.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Panel),
        ) {
            Text(
                "Aspect ratio",
                color = TextPrimary,
                fontSize = 16.sp,
                modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 8.dp),
            )
            Column(Modifier.padding(horizontal = 8.dp)) {
                AspectMode.values().forEach { mode ->
                    RadioRow(
                        label = mode.label,
                        sublabel = mode.description,
                        selected = mode == currentAspect,
                        modifier = if (mode == currentAspect) Modifier.focusRequester(startFocus) else Modifier,
                    ) { onAspectChange(mode) }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                DialogTextButton("DONE", Accent, onDismiss)
            }
        }
    }
}
