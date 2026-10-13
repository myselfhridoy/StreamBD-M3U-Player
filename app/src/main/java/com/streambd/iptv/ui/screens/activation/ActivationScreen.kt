package com.streambd.iptv.ui.screens.activation

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.streambd.iptv.ui.theme.*

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ActivationScreen(
    onActivated: () -> Unit,
    vm: ActivationViewModel = hiltViewModel(),
) {
    val code by vm.code.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val success by vm.success.collectAsStateWithLifecycle()
    var showDevDialog by remember { mutableStateOf(false) }
    val fieldFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) { runCatching { fieldFocus.requestFocus() } }

    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xFF0B0B0B), BgDark)),
        ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.fillMaxWidth().widthIn(max = 420.dp).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("STREAMBD", color = Accent, fontSize = 40.sp, fontWeight = FontWeight.Bold)
            Text("IPTV", color = TextPrimary, fontSize = 18.sp)
            Spacer(Modifier.height(40.dp))

            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Panel)
                    .combinedClickable(onClick = {}, onLongClick = { showDevDialog = true })
                    .padding(16.dp),
            ) {
                Text(
                    "Enter the activation code you received with your subscription.",
                    color = TextPrimary,
                    fontSize = 14.sp,
                )
                Spacer(Modifier.height(6.dp))
                Text("Long-press this card for advanced setup.", color = TextMuted, fontSize = 11.sp)
            }

            Spacer(Modifier.height(20.dp))
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Surface1)
                    .border(2.dp, Accent, RoundedCornerShape(8.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                if (code.isEmpty()) Text("Activation code", color = TextMuted, fontSize = 16.sp)
                BasicTextField(
                    value = code,
                    onValueChange = vm::setCode,
                    singleLine = true,
                    cursorBrush = SolidColor(Accent),
                    textStyle = TextStyle(color = TextPrimary, fontSize = 16.sp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth().focusRequester(fieldFocus),
                )
            }

            error?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, color = Danger, fontSize = 13.sp)
            }

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = vm::activate,
                enabled = code.isNotBlank() && !busy,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Accent,
                    contentColor = Color.Black,
                    disabledContainerColor = Accent.copy(alpha = 0.35f),
                    disabledContentColor = Color.Black.copy(alpha = 0.6f),
                ),
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                if (busy) CircularProgressIndicator(color = Color.Black, strokeWidth = 2.dp)
                else Text("Activate", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            }
        }
    }

    if (showDevDialog) {
        DevUrlDialog(
            onDismiss = { showDevDialog = false },
            onApply = { playlistUrl, epgUrl ->
                showDevDialog = false
                vm.activateWithPlaylistUrl(playlistUrl, epgUrl)
            },
        )
    }

    success?.let { active ->
        ActivationSuccessDialog(active.code, active.expiresAt) {
            vm.consumeSuccess()
            onActivated()
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DevUrlDialog(onDismiss: () -> Unit, onApply: (String, String) -> Unit) {
    var playlistUrl by remember { mutableStateOf("") }
    var epgUrl by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().widthIn(max = 460.dp).clip(RoundedCornerShape(8.dp))
                .background(Panel).padding(20.dp),
        ) {
            Text("Advanced setup", color = TextPrimary, fontSize = 18.sp)
            Spacer(Modifier.height(6.dp))
            Text("Skip server activation and load a playlist directly. For testing only.", color = TextMuted, fontSize = 13.sp)
            Spacer(Modifier.height(16.dp))
            UrlField("Playlist M3U URL", playlistUrl) { playlistUrl = it }
            Spacer(Modifier.height(10.dp))
            UrlField("EPG URL (optional)", epgUrl) { epgUrl = it }
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                DialogAction("CANCEL", TextMuted, onDismiss)
                Spacer(Modifier.width(8.dp))
                DialogAction("APPLY", Accent) { onApply(playlistUrl, epgUrl) }
            }
        }
    }
}

@Composable
private fun UrlField(label: String, value: String, onValue: (String) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(label, color = TextMuted, fontSize = 12.sp)
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Surface1)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            if (value.isEmpty()) Text("https://…", color = TextMuted, fontSize = 14.sp)
            BasicTextField(
                value = value,
                onValueChange = onValue,
                singleLine = true,
                cursorBrush = SolidColor(Accent),
                textStyle = TextStyle(color = TextPrimary, fontSize = 14.sp),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DialogAction(label: String, color: Color, onClick: () -> Unit) {
    Text(
        label,
        color = color,
        fontSize = 13.sp,
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).combinedClickable(
            onClick = onClick,
            onLongClick = onClick,
        ).padding(horizontal = 12.dp, vertical = 8.dp),
    )
}