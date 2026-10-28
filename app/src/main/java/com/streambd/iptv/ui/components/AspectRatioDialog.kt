package com.streambd.iptv.ui.components

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
import com.streambd.iptv.ui.player.RadioRow
import com.streambd.iptv.ui.theme.TextMuted
import com.streambd.iptv.ui.theme.TextPrimary

/** Settings থেকে: একটা বেছে নিলেই বন্ধ। */
@Composable
fun AspectRatioDialog(
    current: AspectMode,
    onPick: (AspectMode) -> Unit,
    onDismiss: () -> Unit,
) {
    val startFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { startFocus.requestFocus() } }

    AppDialog(onDismiss = onDismiss) {
        Text(
            "Select Aspect Ratio",
            color = TextPrimary,
            fontSize = 18.sp,
            modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 8.dp),
        )
        Column(Modifier.padding(horizontal = 8.dp)) {
            AspectMode.values().forEach { mode ->
                RadioRow(
                    label = mode.label,
                    sublabel = mode.description,
                    selected = mode == current,
                    modifier = if (mode == current) Modifier.focusRequester(startFocus) else Modifier,
                ) { onPick(mode) }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            AppDialogButton("CANCEL", TextMuted, onClick = onDismiss)
        }
    }
}
