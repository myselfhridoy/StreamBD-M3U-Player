package com.streambd.iptv.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streambd.iptv.ui.theme.*
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
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val shake = remember { Animatable(0f) }
    // ল্যান্ডস্কেপ ফোনে ৪ সারির কিপ্যাড লম্বা হয়ে কাটা পড়ত, তাই কম উচ্চতায় পাশাপাশি লেআউট
    val compact = LocalConfiguration.current.screenHeightDp < 460

    fun submit(entered: String) {
        checking = true
        scope.launch {
            if (verify(entered)) {
                onSuccess()
            } else {
                wrong = true
                pin = ""
                repeat(3) {
                    shake.animateTo(10f, tween(45))
                    shake.animateTo(-10f, tween(45))
                }
                shake.animateTo(0f, tween(45))
                wrong = false
            }
            checking = false
        }
    }

    fun addDigit(d: Char) {
        if (checking || pin.length >= PIN_LEN) return
        pin += d
        if (pin.length == PIN_LEN) submit(pin)
    }

    fun backspace() {
        if (!checking && pin.isNotEmpty()) pin = pin.dropLast(1)
    }

    AppDialog(
        onDismiss = onDismiss,
        width = if (compact) 460.dp else 320.dp,
        modifier = Modifier
            .graphicsLayer { translationX = shake.value * density }
            .onPreviewKeyEvent { event ->
                // TV রিমোট/কিবোর্ডের ০-৯ ও Backspace
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val digit = digitOf(event.key)
                when {
                    digit != null -> { addDigit(digit); true }
                    event.key == Key.Backspace || event.key == Key.Delete -> { backspace(); true }
                    else -> false
                }
            },
    ) {
        if (compact) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(title, color = TextPrimary, fontSize = 18.sp)
                    Spacer(Modifier.height(16.dp))
                    PinDots(pin, wrong)
                }
                Spacer(Modifier.width(16.dp))
                Keypad(
                    keyWidth = 64.dp, keyHeight = 42.dp, gap = 6.dp,
                    onDigit = ::addDigit, onBack = ::backspace, onClear = { if (!checking) pin = "" },
                )
            }
        } else {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(title, color = TextPrimary, fontSize = 18.sp)
                Spacer(Modifier.height(16.dp))
                PinDots(pin, wrong)
                Spacer(Modifier.height(20.dp))
                Keypad(
                    keyWidth = 72.dp, keyHeight = 56.dp, gap = 8.dp,
                    onDigit = ::addDigit, onBack = ::backspace, onClear = { if (!checking) pin = "" },
                )
            }
        }
    }
}

private fun digitOf(key: Key): Char? = when (key) {
    Key.Zero, Key.NumPad0 -> '0'
    Key.One, Key.NumPad1 -> '1'
    Key.Two, Key.NumPad2 -> '2'
    Key.Three, Key.NumPad3 -> '3'
    Key.Four, Key.NumPad4 -> '4'
    Key.Five, Key.NumPad5 -> '5'
    Key.Six, Key.NumPad6 -> '6'
    Key.Seven, Key.NumPad7 -> '7'
    Key.Eight, Key.NumPad8 -> '8'
    Key.Nine, Key.NumPad9 -> '9'
    else -> null
}

@Composable
private fun PinDots(pin: String, wrong: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(PIN_LEN) { i ->
            val filled = pin.length > i
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Surface1)
                    .border(
                        1.dp,
                        when {
                            wrong -> Danger
                            filled -> Accent
                            else -> Divider
                        },
                        RoundedCornerShape(4.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (filled) Text("●", color = Accent, fontSize = 18.sp)
            }
        }
    }
}

@Composable
private fun Keypad(
    keyWidth: Dp,
    keyHeight: Dp,
    gap: Dp,
    onDigit: (Char) -> Unit,
    onBack: () -> Unit,
    onClear: () -> Unit,
) {
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("CLR", "0", "DEL"),
    )
    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
        rows.forEachIndexed { rowIndex, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                row.forEachIndexed { columnIndex, label ->
                    PinKey(
                        label = label,
                        width = keyWidth,
                        height = keyHeight,
                        requestInitialFocus = rowIndex == 0 && columnIndex == 0,
                    ) {
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
private fun PinKey(
    label: String,
    width: Dp,
    height: Dp,
    requestInitialFocus: Boolean,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val requester = remember { FocusRequester() }
    LaunchedEffect(requestInitialFocus) {
        if (requestInitialFocus) runCatching { requester.requestFocus() }
    }
    val shape = RoundedCornerShape(4.dp)
    Box(
        Modifier
            .size(width, height)
            .clip(shape)
            .background(if (focused) Surface1 else Panel)
            .border(if (focused) 2.dp else 1.dp, if (focused) Accent else Divider, shape)
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
