package com.streambd.iptv.ui.screens.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.streambd.iptv.data.settings.AppSettings
import com.streambd.iptv.data.activation.ActivationState
import com.streambd.iptv.player.AspectMode
import com.streambd.iptv.ui.components.AspectRatioDialog
import com.streambd.iptv.ui.components.ConfirmDialog
import com.streambd.iptv.ui.components.PinDialog
import com.streambd.iptv.ui.theme.*
import com.streambd.iptv.util.isTelevision
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val APP_VERSION = "1.0.0"

private sealed interface Sd {
    data object None : Sd
    data object Aspect : Sd
    data object ClearCache : Sd
    data object ResetConfirm : Sd
    data object PinCurrent : Sd
    data object PinNew : Sd
    data object PinConfirm : Sd
    data object PinForReset : Sd
    data object Help : Sd
    data object Licenses : Sd
    data object OverlayPermission : Sd
}

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onResetComplete: () -> Unit,
    vm: SettingsViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val activationState by vm.activationState.collectAsStateWithLifecycle()
    val refresh by vm.refreshState.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val isTv = remember { ctx.isTelevision() }

    var dlg by remember { mutableStateOf<Sd>(Sd.None) }
    var pendingPin by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().background(BgDark)) {
        // TopBar বাদ দেওয়া হয়েছে
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding() // স্ট্যাটাস বারের নিচে যাওয়া আটকাতে
                .navigationBarsPadding() // নেভিগেশন বারের উপরে থাকতে
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            SectionHeader("General")

            if (isTv) {
                CheckRow(
                    label = "Start App automatically after reboot",
                    checked = state.startAfterReboot,
                    onCheckedChange = { enable ->
                        val needsOverlay = enable &&
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                            !Settings.canDrawOverlays(ctx)
                        if (needsOverlay) dlg = Sd.OverlayPermission else vm.setStartAfterReboot(enable)
                    },
                )
            }

            CheckRow(
                label = "Show EPG when a channel is selected",
                checked = state.showEpgOnSelect,
                onCheckedChange = vm::setShowEpg,
            )

            ValueRow(
                label = "Aspect Ratio",
                value = state.aspectMode.label,
                onClick = { dlg = Sd.Aspect },
            )

            SliderRow(
                label = "EPG Time Shift",
                valueLabel = if (state.epgTimeShiftMins % 60 == 0) {
                    "${state.epgTimeShiftMins / 60} h"
                } else {
                    "%.1f h".format(state.epgTimeShiftMins / 60f)
                },
                value = state.epgTimeShiftMins.toFloat(),
                range = -600f..600f,
                steps = 39,
                onChange = {
                    val snapped = (it / 30f).roundToInt() * 30
                    vm.setEpgShift(snapped)
                },
            )

            SliderRow(
                label = "Buffer Time",
                valueLabel = "${state.bufferTimeMs} ms",
                value = state.bufferTimeMs.toFloat(),
                range = 1_000f..10_000f,
                steps = 35,
                onChange = {
                    val snapped = (it / 250f).roundToInt() * 250
                    vm.setBuffer(snapped)
                },
            )

            val refreshing = refresh is RefreshState.Running
            ActionRow(
                label = if (refreshing) "Refreshing playlist…" else "Refresh Playlist",
                onClick = { if (!refreshing) vm.refreshPlaylist() },
            )
            ActionRow("Change PIN Code", onClick = { dlg = Sd.PinCurrent })
            ActionRow("Reset Application", danger = true, onClick = { dlg = Sd.PinForReset })
            ActionRow("Clear Disk Cache", onClick = { dlg = Sd.ClearCache })
            ActionRow("Help", onClick = { dlg = Sd.Help })

            Spacer(Modifier.height(24.dp))
            SectionHeader("About")

            val active = activationState as? ActivationState.Active
            val expiryText = active?.expiresAt?.takeIf { it > 0L }?.let {
                java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.getDefault())
                    .format(java.util.Date(it))
            } ?: "—"
            InfoRow("Device", "${Build.MANUFACTURER} ${Build.MODEL}")
            InfoRow("OS Version", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            InfoRow("Activation Code", active?.code ?: "Not activated")
            InfoRow("Expiration Date", expiryText)
            InfoRow("App", "StreamBD IPTV $APP_VERSION")
            ActionRow("Licenses", onClick = { dlg = Sd.Licenses })

            Spacer(Modifier.height(48.dp))
        }
    }

    // Refresh-এর ফলাফল
    when (val r = refresh) {
        is RefreshState.Done -> ConfirmDialog(
            title = "Playlist updated",
            body = buildString {
                append("${r.channels} channels loaded.")
                when {
                    r.epgError != null -> append("\n\nTV guide: ${r.epgError}")
                    r.programs > 0 -> append("\n${r.programs} guide entries loaded.")
                }
            },
            confirmLabel = "OK",
            confirmIsDangerous = false,
            onCancel = vm::consumeRefresh,
            onConfirm = vm::consumeRefresh,
        )

        is RefreshState.Failed -> ConfirmDialog(
            title = "Couldn't refresh",
            body = r.message,
            confirmLabel = "OK",
            confirmIsDangerous = false,
            onCancel = vm::consumeRefresh,
            onConfirm = vm::consumeRefresh,
        )

        RefreshState.Idle, RefreshState.Running -> Unit
    }

    when (dlg) {
        Sd.Aspect -> AspectRatioDialog(
            current = state.aspectMode,
            onPick = { vm.setAspect(it); dlg = Sd.None },
            onDismiss = { dlg = Sd.None },
        )

        Sd.ClearCache -> ConfirmDialog(
            title = "Clear Disk Cache",
            body = "Are you sure you want to clear the disk cache?",
            confirmLabel = "CLEAR CACHE",
            onCancel = { dlg = Sd.None },
            onConfirm = {
                dlg = Sd.None
                scope.launch {
                    val freed = vm.clearDiskCache()
                    val mb = "%.2f MB".format(freed / 1_048_576.0)
                    Toast.makeText(ctx, "Freed $mb", Toast.LENGTH_SHORT).show()
                }
            },
        )

        Sd.ResetConfirm -> ConfirmDialog(
            title = "Reset Application Confirmation",
            body = "Are you sure you want to reset the application? This will remove the activation code, favorite channels and everything!",
            confirmLabel = "RESET APPLICATION!",
            onCancel = { dlg = Sd.None },
            onConfirm = {
                dlg = Sd.None
                vm.resetApplication { onResetComplete() }
            },
        )

        Sd.PinCurrent -> PinDialog(
            title = "Enter Current PIN",
            verify = { vm.verifyPin(it) },
            onSuccess = { dlg = Sd.PinNew },
            onDismiss = { dlg = Sd.None },
        )

        Sd.PinNew -> PinDialog(
            title = "Enter New PIN",
            verify = { pendingPin = it; true },
            onSuccess = { dlg = Sd.PinConfirm },
            onDismiss = { dlg = Sd.None },
        )

        Sd.PinConfirm -> PinDialog(
            title = "Confirm New PIN",
            verify = { it == pendingPin },
            onSuccess = { vm.setPin(pendingPin); dlg = Sd.None },
            onDismiss = { dlg = Sd.None },
        )

        Sd.PinForReset -> PinDialog(
            title = "Enter PIN",
            verify = { vm.verifyPin(it) },
            onSuccess = { dlg = Sd.ResetConfirm },
            onDismiss = { dlg = Sd.None },
        )

        Sd.Help -> ConfirmDialog(
            title = "Help",
            body = "StreamBD IPTV plays playlists you own. Use Settings to change PIN, aspect ratio and buffer time. On TV, use D-pad to navigate; OK opens the player overlay; Back exits fullscreen.",
            confirmLabel = "GOT IT",
            confirmIsDangerous = false,
            onCancel = { dlg = Sd.None },
            onConfirm = { dlg = Sd.None },
        )

        Sd.Licenses -> ConfirmDialog(
            title = "Open-source licenses",
            body = "AndroidX, Jetpack Compose, Media3, Room, DataStore, Hilt, OkHttp, Coil — each under Apache-2.0. Poppins/Nunito (when bundled) under SIL OFL 1.1.",
            confirmLabel = "CLOSE",
            confirmIsDangerous = false,
            onCancel = { dlg = Sd.None },
            onConfirm = { dlg = Sd.None },
        )

        Sd.OverlayPermission -> ConfirmDialog(
            title = "Allow auto-start",
            body = "To open the app after the TV restarts, Android requires the \"Display over other apps\" permission. Tap CONTINUE, enable StreamBD IPTV, then come back.",
            confirmLabel = "CONTINUE",
            confirmIsDangerous = false,
            onCancel = { dlg = Sd.None },
            onConfirm = {
                dlg = Sd.None
                vm.setStartAfterReboot(true)
                try {
                    ctx.startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(ctx, "This device can't open that setting. Auto-start may not work.", Toast.LENGTH_LONG).show()
                }
            },
        )

        Sd.None -> Unit
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text.uppercase(), color = Accent, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) Panel else Color.Transparent)
            .border(if (focused) 2.dp else 0.dp, if (focused) Accent else Color.Transparent, RoundedCornerShape(8.dp))
            .onFocusChanged { focused = it.isFocused }
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = CheckboxDefaults.colors(checkedColor = Accent, uncheckedColor = TextMuted),
        )
        Spacer(Modifier.width(8.dp))
        Text(label, color = TextPrimary, fontSize = 15.sp)
    }
}

@Composable
private fun ValueRow(label: String, value: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) Panel else Color.Transparent)
            .border(if (focused) 2.dp else 0.dp, if (focused) Accent else Color.Transparent, RoundedCornerShape(8.dp))
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = TextPrimary, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text(value, color = Accent, fontSize = 14.sp)
    }
}

@Composable
private fun ActionRow(label: String, danger: Boolean = false, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Text(
        text = label,
        color = if (danger) Danger else TextPrimary,
        fontSize = 15.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) Panel else Color.Transparent)
            .border(if (focused) 2.dp else 0.dp, if (focused) Accent else Color.Transparent, RoundedCornerShape(8.dp))
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 14.dp),
    )
}

@Composable
private fun SliderRow(
    label: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
) {
    var local by remember(value) { mutableStateOf(value) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = TextPrimary, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text(valueLabel, color = Accent, fontSize = 13.sp)
        }
        Slider(
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { onChange(local) },
            valueRange = range,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = Accent,
                activeTrackColor = Accent,
                inactiveTrackColor = TextPrimary.copy(alpha = 0.25f),
            ),
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = TextPrimary, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text(value, color = TextMuted, fontSize = 13.sp)
    }
}
