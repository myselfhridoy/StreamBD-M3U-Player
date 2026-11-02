package com.streambd.iptv.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streambd.iptv.data.settings.SEEK_STEP_OPTIONS_MS
import com.streambd.iptv.data.settings.formatSeekStep
import com.streambd.iptv.ui.player.RadioRow
import com.streambd.iptv.ui.theme.TextMuted
import com.streambd.iptv.ui.theme.TextPrimary

@Composable
fun SeekStepDialog(
    current: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val startFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { startFocus.requestFocus() } }

    AppDialog(onDismiss = onDismiss) {
        Text(
            "Seek Step (FF / REW)",
            color = TextPrimary,
            fontSize = 18.sp,
            modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 8.dp),
        )
        Column(
            Modifier
                .heightIn(max = 280.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 8.dp),
        ) {
            SEEK_STEP_OPTIONS_MS.forEach { ms ->
                RadioRow(
                    label = formatSeekStep(ms),
                    selected = ms == current,
                    modifier = if (ms == current) Modifier.focusRequester(startFocus) else Modifier,
                ) { onPick(ms) }
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
