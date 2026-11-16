package com.streambd.iptv.ui.notice

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.streambd.iptv.data.notice.UpdateInfo
import com.streambd.iptv.ui.screens.providers.DialogTextButton
import com.streambd.iptv.ui.theme.*
import kotlinx.coroutines.delay

/**
 * অ্যাপের রুট কম্পোজেবলে একবার বসান (সব স্ক্রিনের ওপরে দেখাবে):
 *     NoticeHost()
 * বাধ্যতামূলক আপডেট > ঐচ্ছিক আপডেট > ঘোষণা: একসাথে একটাই দেখায়।
 */
@Composable
fun NoticeHost(vm: NoticeViewModel = hiltViewModel()) {
    val state by vm.state.collectAsState()
    val checking by vm.checking.collectAsState()

    val update = state.update
    val ann = state.announcement
    when {
        update != null && update.force -> ForceUpdateDialog(update, checking, onCheckAgain = vm::recheck)
        update != null -> SoftUpdateDialog(update, onLater = vm::dismissUpdate)
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

@Composable
private fun openLink(url: String): () -> Unit {
    val handler = LocalUriHandler.current
    return { runCatching { handler.openUri(url) } }
}

@Composable
private fun ForceUpdateDialog(u: UpdateInfo, checking: Boolean, onCheckAgain: () -> Unit) {
    val open = openLink(u.url)
    val focus = remember { FocusRequester() }
    NoticeFrame(dismissible = false, onDismiss = {}) {
        Text("Update required", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text("This version is no longer supported. Please update to continue.", color = TextMuted, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        UpdateBody(u)
        if (u.url.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(u.url, color = TextMuted, fontSize = 11.sp)
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            DialogTextButton(if (checking) "CHECKING..." else "CHECK AGAIN", TextMuted, onClick = { if (!checking) onCheckAgain() })
            if (u.url.isNotBlank()) {
                Spacer(Modifier.width(8.dp))
                DialogTextButton("UPDATE", Accent, modifier = Modifier.focusRequester(focus), onClick = open)
            }
        }
    }
    LaunchedEffect(Unit) { delay(150); runCatching { focus.requestFocus() } }
}

@Composable
private fun SoftUpdateDialog(u: UpdateInfo, onLater: () -> Unit) {
    val open = openLink(u.url)
    val focus = remember { FocusRequester() }
    NoticeFrame(dismissible = true, onDismiss = onLater) {
        Text("Update available", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        UpdateBody(u)
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            DialogTextButton("LATER", TextMuted, onClick = onLater)
            if (u.url.isNotBlank()) {
                Spacer(Modifier.width(8.dp))
                DialogTextButton("UPDATE", Accent, modifier = Modifier.focusRequester(focus), onClick = { open(); onLater() })
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
