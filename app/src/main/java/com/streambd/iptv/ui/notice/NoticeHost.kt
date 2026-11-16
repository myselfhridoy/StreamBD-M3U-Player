package com.streambd.iptv.ui.notice

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import com.streambd.iptv.data.notice.Announcement
import com.streambd.iptv.data.notice.AppUpdater
import com.streambd.iptv.data.notice.UpdateInfo
import com.streambd.iptv.ui.screens.providers.DialogTextButton
import com.streambd.iptv.ui.theme.*
import kotlinx.coroutines.delay

/**
 * অ্যাপের রুট কম্পোজেবলে একবার বসান (সব স্ক্রিনের ওপরে দেখাবে):
 *     NoticeHost()
 * বাধ্যতামূলক আপডেট > ঐচ্ছিক আপডেট > ঘোষণা: একসাথে একটাই দেখায়।
 * আপডেট লিংক সরাসরি .apk হলে অ্যাপের ভেতরে নামিয়ে ইনস্টল করে; নইলে ব্রাউজারে খোলে।
 */
@Composable
fun NoticeHost(vm: NoticeViewModel = hiltViewModel()) {
    val state by vm.state.collectAsState()
    val checking by vm.checking.collectAsState()
    val download by vm.download.collectAsState()
    val uriHandler = LocalUriHandler.current

    val onUpdate: (UpdateInfo) -> Unit = { u ->
        if (vm.canUpdateInApp(u.url)) vm.startUpdate(u.url)
        else runCatching { uriHandler.openUri(u.url) }
    }

    val update = state.update
    val ann = state.announcement
    when {
        update != null && update.force ->
            ForceUpdateDialog(update, checking, download, onCheckAgain = vm::recheck, onUpdate = { onUpdate(update) })
        update != null ->
            SoftUpdateDialog(update, download, onLater = vm::dismissUpdate, onUpdate = { onUpdate(update) })
        ann != null -> AnnouncementDialog(ann, onDismiss = { vm.dismissAnnouncement(ann.id) })
    }
}

@Composable
private fun NoticeFrame(dismissible: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = dismissible,
            dismissOnClickOutside = dismissible,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
            Column(
                Modifier
                    .widthIn(max = 380.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .clip(RoundedCornerShape(4.dp))
                    .background(Panel)
                    .border(1.dp, Divider, RoundedCornerShape(4.dp))
                    .padding(16.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun UpdateBody(u: UpdateInfo) {
    if (u.versionName.isNotBlank()) {
        Text("Version ${u.versionName}", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
    if (u.notes.isNotBlank()) {
        Spacer(Modifier.height(6.dp))
        Text(u.notes, color = TextMuted, fontSize = 12.sp)
    }
}

/** ডাউনলোডের অবস্থা/প্রগ্রেস/ত্রুটি */
@Composable
private fun DownloadStatus(d: AppUpdater.State) {
    when (d) {
        is AppUpdater.State.Downloading -> {
            Spacer(Modifier.height(10.dp))
            Text(if (d.percent >= 0) "Downloading… ${d.percent}%" else "Downloading…", color = TextMuted, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            if (d.percent >= 0) {
                LinearProgressIndicator(progress = { d.percent / 100f }, modifier = Modifier.fillMaxWidth().height(3.dp), color = Accent, trackColor = Divider)
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(3.dp), color = Accent, trackColor = Divider)
            }
        }
        is AppUpdater.State.NeedPermission -> {
            Spacer(Modifier.height(10.dp))
            Text("Allow \"Install unknown apps\" for StreamBD in the settings that opened, go back, then press UPDATE again.", color = Accent, fontSize = 12.sp)
        }
        is AppUpdater.State.Failed -> {
            Spacer(Modifier.height(10.dp))
            Text(d.message, color = Danger, fontSize = 12.sp)
        }
        AppUpdater.State.Idle -> Unit
    }
}

@Composable
private fun ForceUpdateDialog(
    u: UpdateInfo, checking: Boolean, d: AppUpdater.State,
    onCheckAgain: () -> Unit, onUpdate: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    val busy = d is AppUpdater.State.Downloading
    NoticeFrame(dismissible = false, onDismiss = {}) {
        Text("Update required", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text("This version is no longer supported. Please update to continue.", color = TextMuted, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        UpdateBody(u)
        DownloadStatus(d)
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            DialogTextButton(if (checking) "CHECKING..." else "CHECK AGAIN", TextMuted, onClick = { if (!checking && !busy) onCheckAgain() })
            if (u.url.isNotBlank()) {
                Spacer(Modifier.width(8.dp))
                DialogTextButton(if (busy) "DOWNLOADING..." else "UPDATE", Accent, modifier = Modifier.focusRequester(focus), onClick = { if (!busy) onUpdate() })
            }
        }
    }
    LaunchedEffect(Unit) { delay(150); runCatching { focus.requestFocus() } }
}

@Composable
private fun SoftUpdateDialog(u: UpdateInfo, d: AppUpdater.State, onLater: () -> Unit, onUpdate: () -> Unit) {
    val focus = remember { FocusRequester() }
    val busy = d is AppUpdater.State.Downloading
    NoticeFrame(dismissible = true, onDismiss = onLater) {
        Text("Update available", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        UpdateBody(u)
        DownloadStatus(d)
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            DialogTextButton("LATER", TextMuted, onClick = onLater)
            if (u.url.isNotBlank()) {
                Spacer(Modifier.width(8.dp))
                DialogTextButton(if (busy) "DOWNLOADING..." else "UPDATE", Accent, modifier = Modifier.focusRequester(focus), onClick = { if (!busy) onUpdate() })
            }
        }
    }
    LaunchedEffect(Unit) { delay(150); runCatching { focus.requestFocus() } }
}

@Composable
private fun AnnouncementDialog(a: Announcement, onDismiss: () -> Unit) {
    val focus = remember { FocusRequester() }
    val titleColor = when (a.level) {
        "critical" -> Danger
        "warning" -> Accent
        else -> TextPrimary
    }
    NoticeFrame(dismissible = a.dismissible, onDismiss = { if (a.dismissible) onDismiss() }) {
        Text(a.title, color = titleColor, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(a.message, color = TextMuted, fontSize = 13.sp)
        if (a.dismissible) {
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                DialogTextButton("OK", Accent, modifier = Modifier.focusRequester(focus), onClick = onDismiss)
            }
        }
    }
    LaunchedEffect(a.id) { delay(150); runCatching { focus.requestFocus() } }
}
