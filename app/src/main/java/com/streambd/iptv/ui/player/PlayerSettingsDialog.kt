package com.streambd.iptv.ui.player

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streambd.iptv.player.AspectMode
import com.streambd.iptv.ui.components.AppDialog
import com.streambd.iptv.ui.components.AppDialogButton
import com.streambd.iptv.ui.theme.Accent
import com.streambd.iptv.ui.theme.TextPrimary

@Composable
fun PlayerSettingsDialog(
    currentAspect: AspectMode,
    onAspectChange: (AspectMode) -> Unit,
    onDismiss: () -> Unit,
) {
    val startFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { startFocus.requestFocus() } }

    AppDialog(onDismiss = onDismiss) {
        Text(
            "Aspect ratio",
            color = TextPrimary,
            fontSize = 18.sp,
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
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            AppDialogButton("DONE", Accent, onClick = onDismiss)
        }
    }
}
