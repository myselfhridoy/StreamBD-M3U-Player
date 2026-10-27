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
    
    // fieldFocus আর ব্যবহার হচ্ছে না, তাই কিবোর্ড অটো ওপেন হবে না
    val fieldFocus = remember { FocusRequester() }

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(listOf(Color(0xFF0B0B0B), BgDark)),
            )
            .windowInsetsPadding(WindowInsets.safeDrawing), // সিস্টেম বার ও নচ থেকে বাঁচতে
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .widthIn(max = 400.dp) // প্রস্থ একটু কমিয়ে আনা হলো
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp), // প্যাডিং কমানো হলো
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // ফন্ট সাইজ ও স্পেসিং কমপ্যাক্ট করা হয়েছে
            Text("STREAMBD", color = Accent, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Text("IPTV", color = TextPrimary, fontSize = 16.sp)
            Spacer(Modifier.height(24.dp)) // ৪০ থেকে কমিয়ে ২৪ করা হলো

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
                PlaylistSetupContent(
                    playlistUrl = playlistUrl,
                    epgUrl = epgUrl,
                    busy = busy,
                    error = error,
                    fieldFocus = fieldFocus,
                    onPlaylistChange = { playlistUrl = it },
                    onEpgChange = { epgUrl = it },
                    onSave = { vm.activateWithPlaylistUrl(playlistUrl, epgUrl) },
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
            // লোকাল সেটআপে আলাদা success dialog লাগে না, সরাসরি এগিয়ে যাই
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
            .padding(14.dp), // প্যাডিং কমানো
    ) {
        Text(
            "Enter the activation code you received with your subscription.",
            color = TextPrimary,
            fontSize = 13.sp,
        )
        if (devEnabled) {
            Spacer(Modifier.height(4.dp))
            Text("Debug: long-press this card for advanced setup.", color = TextMuted, fontSize = 11.sp)
        }
    }

    Spacer(Modifier.height(16.dp)) // স্পেসিং কমানো
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

    Spacer(Modifier.height(16.dp))
    ActionButton("Activate", enabled = code.isNotBlank() && !busy, busy = busy, onClick = onActivate)
}

/* ------------------------- local playlist mode ------------------------- */

@Composable
private fun PlaylistSetupContent(
    playlistUrl: String,
    epgUrl: String,
    busy: Boolean,
    error: String?,
    fieldFocus: FocusRequester,
    onPlaylistChange: (String) -> Unit,
    onEpgChange: (String) -> Unit,
    onSave: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Panel).padding(14.dp)) {
        Text("Add your playlist", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            "Enter the M3U playlist link you want to watch. An EPG (XMLTV) link is optional.",
            color = TextMuted,
            fontSize = 12.sp,
        )
    }

    Spacer(Modifier.height(16.dp))
    UrlField(
        label = "Playlist M3U URL",
        value = playlistUrl,
        imeAction = ImeAction.Next,
        modifier = Modifier.focusRequester(fieldFocus),
        onValue = onPlaylistChange,
    )
    Spacer(Modifier.height(10.dp))
    UrlField(
        label = "EPG URL (optional)",
        value = epgUrl,
        imeAction = ImeAction.Done,
        onValue = onEpgChange,
    )

    ErrorText(error)

    Spacer(Modifier.height(16.dp))
    ActionButton("Save & Continue", enabled = playlistUrl.isNotBlank() && !busy, busy = busy, onClick = onSave)
}

/* ------------------------------ shared bits ------------------------------ */

@Composable
private fun ErrorText(error: String?) {
    error?.let {
        Spacer(Modifier.height(8.dp))
        Text(it, color = Danger, fontSize = 12.sp)
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
        modifier = Modifier.fillMaxWidth().height(44.dp), // বাটনের উচ্চতা কিছুটা কমানো হলো
    ) {
        if (busy) CircularProgressIndicator(color = Color.Black, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
        else Text(label, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
}

@Composable
private fun DevUrlDialog(onDismiss: () -> Unit, onApply: (String, String) -> Unit) {
    var playlistUrl by remember { mutableStateOf("") }
    var epgUrl by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().widthIn(max = 400.dp).clip(RoundedCornerShape(8.dp))
                .background(Panel).padding(16.dp), // প্যাডিং কমানো
        ) {
            Text("Advanced setup", color = TextPrimary, fontSize = 16.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                "Skip server activation and load a playlist directly. Debug builds only.",
                color = TextMuted,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(12.dp))
            UrlField("Playlist M3U URL", playlistUrl) { playlistUrl = it }
            Spacer(Modifier.height(8.dp))
            UrlField("EPG URL (optional)", epgUrl, imeAction = ImeAction.Done) { epgUrl = it }
            Spacer(Modifier.height(16.dp))
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
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            if (value.isEmpty()) Text("https://…", color = TextMuted, fontSize = 14.sp)
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
