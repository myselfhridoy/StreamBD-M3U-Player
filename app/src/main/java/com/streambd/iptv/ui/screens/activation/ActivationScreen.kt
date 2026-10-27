package com.streambd.iptv.ui.screens.activation

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.streambd.iptv.BuildConfig
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

    val serverMode = BuildConfig.ACTIVATION_ENABLED
    var showDevDialog by remember { mutableStateOf(false) }
    var playlistUrl by rememberSaveable { mutableStateOf("") }
    var epgUrl by rememberSaveable { mutableStateOf("") }
    val fieldFocus = remember { FocusRequester() }

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(listOf(Color(0xFF0B0B0B), BgDark)),
            )
            .imePadding() // কিবোর্ড ওপেন হলে স্ক্রিন রিসাইজ হবে, কন্টেন্ট ঢেকে যাবে না
            .navigationBarsPadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .widthIn(max = 600.dp) // বামে-ডানে ফাঁকা জায়গা কমাতে চওড়া করা হলো
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("STREAMBD", color = Accent, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Text("IPTV", color = TextPrimary, fontSize = 16.sp)
            Spacer(Modifier.height(24.dp))

            if (serverMode) {
                ServerActivationContent(
                    code = code,
                    busy = busy,
                    error = error,
                    devEnabled = BuildConfig.DEBUG,
                    fieldFocus = fieldFocus,
                    onCodeChange = vm::setCode,
                    onActivate = vm::activate,
                    onOpenDev = { showDevDialog = true },
                )
            } else {
                // কার্ড বাদ দেওয়া হলো এবং সরাসরি ইনপুট ফিল্ডে প্লেসহোল্ডার দেওয়া হলো
                Text(
                    "Enter your M3U playlist link below to start watching. An EPG (XMLTV) link is optional.",
                    color = TextMuted,
                    fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                )

                UrlField(
                    label = "Playlist M3U URL",
                    placeholder = "https://example.com/playlist.m3u",
                    value = playlistUrl,
                    imeAction = ImeAction.Next,
                    modifier = Modifier.focusRequester(fieldFocus),
                    onValue = { playlistUrl = it },
                )
                Spacer(Modifier.height(12.dp))
                UrlField(
                    label = "EPG URL (optional)",
                    placeholder = "https://example.com/epg.xml",
                    value = epgUrl,
                    imeAction = ImeAction.Done,
                    onValue = { epgUrl = it },
                )

                ErrorText(error)

                Spacer(Modifier.height(20.dp))
                ActionButton(
                    label = "Save & Continue",
                    enabled = playlistUrl.isNotBlank() && !busy,
                    busy = busy,
                    onClick = { vm.activateWithPlaylistUrl(playlistUrl, epgUrl) },
                )
            }
        }
    }

    if (serverMode && BuildConfig.DEBUG && showDevDialog) {
        DevUrlDialog(
            onDismiss = { showDevDialog = false },
            onApply = { playlist, epg ->
                showDevDialog = false
                vm.activateWithPlaylistUrl(playlist, epg)
            },
        )
    }

    success?.let { active ->
        if (serverMode) {
            ActivationSuccessDialog(active.code, active.expiresAt) {
                vm.consumeSuccess()
                onActivated()
            }
        } else {
            LaunchedEffect(active) {
                vm.consumeSuccess()
                onActivated()
            }
        }
    }
}

/* ------------------------- server activation mode ------------------------- */

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ServerActivationContent(
    code: String,
    busy: Boolean,
    error: String?,
    devEnabled: Boolean,
    fieldFocus: FocusRequester,
    onCodeChange: (String) -> Unit,
    onActivate: () -> Unit,
    onOpenDev: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Panel)
            .then(
                if (devEnabled) Modifier.combinedClickable(onClick = {}, onLongClick = onOpenDev)
                else Modifier,
            )
            .padding(16.dp),
    ) {
        Text(
            "Enter the activation code you received with your subscription.",
            color = TextPrimary,
            fontSize = 14.sp,
        )
        if (devEnabled) {
            Spacer(Modifier.height(4.dp))
            Text("Debug: long-press this card for advanced setup.", color = TextMuted, fontSize = 11.sp)
        }
    }

    Spacer(Modifier.height(16.dp))
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Surface1)
            .border(2.dp, Accent, RoundedCornerShape(8.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        if (code.isEmpty()) Text("Activation code", color = TextMuted, fontSize = 15.sp)
        BasicTextField(
            value = code,
            onValueChange = onCodeChange,
            singleLine = true,
            cursorBrush = SolidColor(Accent),
            textStyle = TextStyle(color = TextPrimary, fontSize = 15.sp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth().focusRequester(fieldFocus),
        )
    }

    ErrorText(error)

    Spacer(Modifier.height(20.dp))
    ActionButton("Activate", enabled = code.isNotBlank() && !busy, busy = busy, onClick = onActivate)
}

/* ------------------------------ shared bits ------------------------------ */

@Composable
private fun ErrorText(error: String?) {
    error?.let {
        Spacer(Modifier.height(8.dp))
        Text(it, color = Danger, fontSize = 13.sp)
    }
}

@Composable
private fun ActionButton(label: String, enabled: Boolean, busy: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = Accent,
            contentColor = Color.Black,
            disabledContainerColor = Accent.copy(alpha = 0.35f),
            disabledContentColor = Color.Black.copy(alpha = 0.6f),
        ),
        modifier = Modifier.fillMaxWidth().height(48.dp),
    ) {
        if (busy) CircularProgressIndicator(color = Color.Black, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
        else Text(label, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    }
}

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
            Text(
                "Skip server activation and load a playlist directly. Debug builds only.",
                color = TextMuted,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(16.dp))
            UrlField(
                label = "Playlist M3U URL",
                placeholder = "https://example.com/playlist.m3u",
                value = playlistUrl,
                onValue = { playlistUrl = it },
            )
            Spacer(Modifier.height(12.dp))
            UrlField(
                label = "EPG URL (optional)",
                placeholder = "https://example.com/epg.xml",
                value = epgUrl,
                imeAction = ImeAction.Done,
                onValue = { epgUrl = it },
            )
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
private fun UrlField(
    label: String,
    placeholder: String,
    value: String,
    imeAction: ImeAction = ImeAction.Next,
    modifier: Modifier = Modifier,
    onValue: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(label, color = TextMuted, fontSize = 12.sp)
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Surface1)
                .border(1.dp, Divider, RoundedCornerShape(6.dp))
                .padding(horizontal = 12.dp, vertical = 12.dp),
        ) {
            if (value.isEmpty()) Text(placeholder, color = TextMuted.copy(alpha = 0.6f), fontSize = 14.sp)
            BasicTextField(
                value = value,
                onValueChange = onValue,
                singleLine = true,
                cursorBrush = SolidColor(Accent),
                textStyle = TextStyle(color = TextPrimary, fontSize = 14.sp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = imeAction),
                modifier = modifier.fillMaxWidth(),
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
