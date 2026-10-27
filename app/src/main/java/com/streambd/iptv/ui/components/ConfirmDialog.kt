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
import com.streambd.iptv.ui.theme.*

@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    cancelLabel: String = "CANCEL",
    confirmLabel: String,
    confirmIsDangerous: Boolean = true,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    val initialFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { initialFocus.requestFocus() } }

    AppDialog(onDismiss = onCancel, width = 380.dp) {
        Text(
            title,
            color = TextPrimary,
            fontSize = 18.sp,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp),
        )
        Text(
            body,
            color = TextMuted,
            fontSize = 14.sp,
            modifier = Modifier
                .padding(horizontal = 20.dp, vertical = 10.dp)
                .heightIn(max = 140.dp)
                .verticalScroll(rememberScrollState()),
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            AppDialogButton(
                label = cancelLabel,
                color = TextMuted,
                modifier = if (confirmIsDangerous) Modifier.focusRequester(initialFocus) else Modifier,
                onClick = onCancel,
            )
            Spacer(Modifier.width(8.dp))
            AppDialogButton(
                label = confirmLabel,
                color = if (confirmIsDangerous) Danger else Accent,
                modifier = if (!confirmIsDangerous) Modifier.focusRequester(initialFocus) else Modifier,
                onClick = onConfirm,
            )
        }
    }
}
