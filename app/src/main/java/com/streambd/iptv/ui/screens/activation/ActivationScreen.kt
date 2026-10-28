package com.streambd.iptv.ui.screens.activation

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.streambd.iptv.BuildConfig
import com.streambd.iptv.ui.components.AppDialog
import com.streambd.iptv.ui.components.AppDialogButton
import com.streambd.iptv.ui.theme.*

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
    val fieldFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    
    // ল্যান্ডস্কেপ ফোনে (কিবোর্ড খোলা অবস্থায় আরও কম) উল্লম্ব লেআউট ধরে না, তাই পাশাপাশি
    val compact = LocalConfiguration.current.screenHeightDp < 460

    // মোবাইলে কিবোর্ড অটো ওপেন এড়াতে চাইলে নিচের লাইনটি কমেন্ট আউট করতে পারেন
    // LaunchedEffect(Unit) { runCatching { fieldFocus.requestFocus() } }

    fun submit() {
        if (busy) return
        focusManager.clearFocus()
        if (serverMode) {
            if (code.isNotBlank()) vm.activate()
        } else if (playlistUrl.isNotBlank()) {
            vm.activateWithPlaylistUrl(playlistUrl, "")
        }
    }

    val form: @Composable ColumnScope.() -> Unit = {
        ActivationForm(
            serverMode = serverMode,
            code = code,
            playlistUrl = playlistUrl,
            busy = busy,
            error = error,
            compact = compact,
            devEnabled = BuildConfig.DEBUG,
            fieldFocus = fieldFocus,
            onCodeChange = vm::setCode,
            onPlaylistChange = { playlistUrl = it },
            onSubmit = ::submit,
            onOpenDev = { showDevDialog = true },
        )
    }

    // safeDrawing কিবোর্ডের জায়গাও ধরে, তাই কিবোর্ড খুললে Box ছোট হয়
    Box(
        Modifier
            .fillMaxSize()
            .background(BgDark)
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.Center,
    ) {
        if (compact) {
            Row(
                Modifier.fillMaxWidth().widthIn(max = 760.dp).padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(0.8f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Logo(titleSize = 30.sp, subSize = 14.sp)
                }
                Spacer(Modifier.width(24.dp))
                Column(
                    Modifier.weight(1.2f).verticalScroll(rememberScrollState()),
                    content = form,
                )
            }
        } else {
            Column(
                Modifier
                    .fillMaxWidth()
                    .widthIn(max = 550.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Logo(titleSize = 34.sp, subSize = 15.sp)
                Spacer(Modifier.height(32.dp))
                form()
            }
        }
    }

    if (serverMode && BuildConfig.DEBUG && showDevDialog) {
        DevUrlDialog(
            onDismiss = { showDevDialog = false },
            onApply = { playlist ->
                showDevDialog = false
                vm.activateWithPlaylistUrl(playlist, "")
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

@Composable
private fun Logo(titleSize: TextUnit, subSize: TextUnit) {
    Text("STREAMBD", color = Accent, fontSize = titleSize, fontWeight = FontWeight.Bold)
    Text("IPTV", color = TextPrimary, fontSize = subSize)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ActivationForm(
    serverMode: Boolean,
    code: String,
    playlistUrl: String,
    busy: Boolean,
    error: String?,
    compact: Boolean,
    devEnabled: Boolean,
    fieldFocus: FocusRequester,
    onCodeChange: (String) -> Unit,
    onPlaylistChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onOpenDev: () -> Unit,
) {
    val fieldHeight = if (compact) 46.dp else 52.dp
    val gap = if (compact) 10.dp else 16.dp

    if (serverMode) {
        Text(
            "Enter the activation code you received with your subscription.",
            color = TextPrimary,
            fontSize = if (compact) 13.sp else 14.sp,
            modifier = if (devEnabled) {
                Modifier.combinedClickable(onClick = {}, onLongClick = onOpenDev)
            } else {
                Modifier
            },
        )
        if (devEnabled) {
            Spacer(Modifier.height(2.dp))
            Text("Debug: long-press the text above for advanced setup.", color = TextMuted, fontSize = 11.sp)
        }
        Spacer(Modifier.height(gap))
        InputField(
            label = null,
            placeholder = "Activation code",
            value = code,
            keyboardType = KeyboardType.Text,
            height = fieldHeight,
            highlight = true,
            modifier = Modifier.focusRequester(fieldFocus),
            onSubmit = onSubmit,
            onValue = onCodeChange,
        )
    } else {
        InputField(
            label = "Playlist M3U URL",
            placeholder = "Enter your M3U playlist link...",
            value = playlistUrl,
            keyboardType = KeyboardType.Uri,
            height = fieldHeight,
            highlight = false,
            modifier = Modifier.focusRequester(fieldFocus),
            onSubmit = onSubmit,
            onValue = onPlaylistChange,
        )
    }

    error?.let {
        Spacer(Modifier.height(6.dp))
        Text(it, color = Danger, fontSize = 12.sp)
    }

    Spacer(Modifier.height(gap))
    ActionButton(
        label = if (serverMode) "Activate" else "Save & Continue",
        enabled = (if (serverMode) code.isNotBlank() else playlistUrl.isNotBlank()) && !busy,
        busy = busy,
        height = if (compact) 46.dp else 50.dp,
        onClick = onSubmit,
    )
}

@Composable
private fun InputField(
    label: String?,
    placeholder: String,
    value: String,
    keyboardType: KeyboardType,
    height: Dp,
    highlight: Boolean,
    modifier: Modifier = Modifier,
    onSubmit: () -> Unit,
    onValue: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        if (label != null) {
            Text(label, color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(height)
                .clip(RoundedCornerShape(8.dp))
                .background(Surface1)
                .border(if (highlight) 2.dp else 1.dp, if (highlight) Accent else Divider, RoundedCornerShape(8.dp))
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (value.isEmpty()) Text(placeholder, color = TextMuted.copy(alpha = 0.5f), fontSize = 15.sp)
            BasicTextField(
                value = value,
                onValueChange = onValue,
                singleLine = true,
                cursorBrush = SolidColor(Accent),
                textStyle = TextStyle(color = TextPrimary, fontSize = 15.sp),
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSubmit() }),
                modifier = modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ActionButton(label: String, enabled: Boolean, busy: Boolean, height: Dp, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = Accent,
            contentColor = Color.Black,
            disabledContainerColor = Accent.copy(alpha = 0.35f),
            disabledContentColor = Color.Black.copy(alpha = 0.6f),
        ),
        modifier = Modifier.fillMaxWidth().height(height),
        shape = RoundedCornerShape(8.dp),
    ) {
        if (busy) CircularProgressIndicator(color = Color.Black, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
        else Text(label, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    }
}

@Composable
private fun DevUrlDialog(onDismiss: () -> Unit, onApply: (String) -> Unit) {
    var playlistUrl by remember { mutableStateOf("") }

    AppDialog(onDismiss = onDismiss, width = 460.dp) {
        Column(Modifier.padding(20.dp)) {
            Text("Advanced setup", color = TextPrimary, fontSize = 18.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                "Skip server activation and load a playlist directly. Debug builds only.",
                color = TextMuted,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(12.dp))
            InputField(
                label = "Playlist M3U URL",
                placeholder = "Enter M3U Playlist URL...",
                value = playlistUrl,
                keyboardType = KeyboardType.Uri,
                height = 46.dp,
                highlight = false,
                onSubmit = { onApply(playlistUrl) },
                onValue = { playlistUrl = it },
            )
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                AppDialogButton("CANCEL", TextMuted, onClick = onDismiss)
                Spacer(Modifier.width(8.dp))
                AppDialogButton("APPLY", Accent) { onApply(playlistUrl) }
            }
        }
    }
}
