package com.streambd.iptv.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.streambd.iptv.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val PIN_LEN = 4

@Composable
fun PinDialog(
    title: String = "Enter PIN",
    verify: suspend (String) -> Boolean,
    onSuccess: () -> Unit,
    onDismiss: () -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val shake by animateFloatAsState(
        targetValue = if (wrong) 1f else 0f,
        label = "shake",
    )

    fun submit() {
        if (pin.length < PIN_LEN) return
        scope.launch {
            val ok = verify(pin)
            if (ok) onSuccess() else {
                wrong = true
                pin = ""
                delay(400)
                wrong = false
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .width(320.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Panel)
                .padding(20.dp)
                .graphicsLayer { translationX = if (wrong) (Math.sin(shake * 20.0) * 12).toFloat() else 0f },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, color = TextPrimary, fontSize = 18.sp)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                repeat(PIN_LEN) { i ->
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Surface1)
                            .border(1.dp, if (pin.length > i) Accent else Divider, RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (pin.length > i) Text("●", color = Accent, fontSize = 18.sp)
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Keypad(
                onDigit = {
                    if (pin.length < PIN_LEN) {
                        pin += it
                        if (pin.length == PIN_LEN) submit()
                    }
                },
                onBack = { if (pin.isNotEmpty()) pin = pin.dropLast(1) },
                onClear = { pin = "" },
            )
        }
    }
}

@Composable
private fun Keypad(onDigit: (Char) -> Unit, onBack: () -> Unit, onClear: () -> Unit) {
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("CLR", "0", "DEL"),
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEachIndexed { rowIndex, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEachIndexed { columnIndex, label ->
                    Key(label, requestInitialFocus = rowIndex == 0 && columnIndex == 0) {
                        when (label) {
                            "CLR" -> onClear()
                            "DEL" -> onBack()
                            else -> onDigit(label[0])
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Key(label: String, requestInitialFocus: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val requester = remember { FocusRequester() }
    LaunchedEffect(requestInitialFocus) {
        if (requestInitialFocus) runCatching { requester.requestFocus() }
    }
    Box(
        Modifier
            .size(72.dp, 56.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) Surface1 else Panel)
            .border(if (focused) 2.dp else 0.dp, if (focused) Accent else Divider, RoundedCornerShape(10.dp))
            .focusRequester(requester)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        when (label) {
            "CLR" -> Icon(Icons.Filled.DeleteForever, null, tint = TextMuted)
            "DEL" -> Icon(Icons.Filled.Backspace, null, tint = TextMuted)
            else -> Text(label, color = TextPrimary, fontSize = 20.sp)
        }
    }
}
