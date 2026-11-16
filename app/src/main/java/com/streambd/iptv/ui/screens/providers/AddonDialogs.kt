package com.streambd.iptv.ui.screens.providers

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.streambd.iptv.data.addon.AddonManifest
import com.streambd.iptv.data.addon.AddonPreview
import com.streambd.iptv.data.db.AddonEntity
import com.streambd.iptv.data.db.ProviderEntity
import com.streambd.iptv.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
private fun AddonFrame(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .imePadding()
                .navigationBarsPadding()
                .pointerInput(Unit) { detectTapGestures { onDismiss() } }
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .widthIn(max = 380.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .clip(RoundedCornerShape(4.dp))
                    .background(Panel)
                    .border(1.dp, Divider, RoundedCornerShape(4.dp))
                    .pointerInput(Unit) { detectTapGestures { } }
                    .padding(16.dp),
                content = content,
            )
        }
    }
}

/** URL বা ফাইল থেকে Add-on ইনস্টল: ধাপ ১ উৎস, ধাপ ২ পারমিশন দেখে নিশ্চিত করা। */
@Composable
fun AddonInstallDialog(
    vm: ProvidersViewModel,
    onDismiss: () -> Unit,
    onDone: (String) -> Unit,
    /** ক্যাটালগ থেকে এলে প্রিভিউ আগে থেকেই তৈরি (সরাসরি নিশ্চিতকরণ ধাপ)। */
    initialPreview: AddonPreview? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf(initialPreview) }
    var error by remember { mutableStateOf<String?>(null) }
    val urlFocus = remember { FocusRequester() }
    val installFocus = remember { FocusRequester() }

    fun runPreview(u: String?, text: String?) {
        error = null
        vm.previewAddon(u, text) { p, msg ->
            if (p != null) preview = p else error = msg
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val text = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    }.getOrNull()
                }
                if (text == null) error = "Could not read that file." else runPreview(null, text)
            }
        }
    }

    val pv = preview
    AddonFrame(onDismiss) {
        if (pv == null) {
            Text("Add Add-on", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            Text("Add-on script URL", color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
            PremiumTextField(
                value = url,
                onValueChange = { url = it },
                placeholder = "https://example.com/my-addon.js",
                focusRequester = urlFocus,
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done,
                onDone = { if (url.isNotBlank()) runPreview(url, null) },
            )
            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = Danger, fontSize = 12.sp)
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                DialogTextButton("CANCEL", TextMuted, onClick = onDismiss)
                Spacer(Modifier.width(8.dp))
                DialogTextButton("FILE", TextPrimary, onClick = { picker.launch(arrayOf("*/*")) })
                Spacer(Modifier.width(8.dp))
                DialogTextButton("NEXT", Accent, onClick = {
                    if (url.isBlank()) error = "Enter a link or choose a file." else runPreview(url, null)
                })
            }
        } else {
            val m = pv.manifest
            Text(
                if (pv.installedVersion != null) "Update add-on?" else "Install add-on?",
                color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(10.dp))
            Text("${m.name}  ·  v${m.version}", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            if (m.author.isNotBlank()) Text("by ${m.author}", color = TextMuted, fontSize = 12.sp)
            if (pv.installedVersion != null) Text("Installed version: ${pv.installedVersion}", color = TextMuted, fontSize = 12.sp)
            if (m.description.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(m.description, color = TextMuted, fontSize = 12.sp)
            }
            Spacer(Modifier.height(10.dp))
            Text("PERMISSIONS", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            Text(networkLine(m), color = if (m.anyDomain) Danger else TextPrimary, fontSize = 12.sp)
            if (m.allowLocal) Text("Can reach devices on your local network", color = Danger, fontSize = 12.sp)
            Spacer(Modifier.height(10.dp))
            if (pv.verified) {
                Text("Verified by StreamBD (signed)", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            } else {
                Text(
                    "Unverified add-on. It runs code on this device. Only install add-ons from sources you trust.",
                    color = TextMuted, fontSize = 11.sp,
                )
            }
            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = Danger, fontSize = 12.sp)
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                DialogTextButton(
                    if (initialPreview != null) "CANCEL" else "BACK", TextMuted,
                    onClick = { if (initialPreview != null) onDismiss() else { preview = null; error = null } },
                )
                Spacer(Modifier.width(8.dp))
                DialogTextButton(
                    if (pv.installedVersion != null) "UPDATE" else "INSTALL",
                    Accent,
                    modifier = Modifier.focusRequester(installFocus),
                    onClick = {
                        error = null
                        vm.installAddon(pv) { ok, msg -> if (ok) onDone(msg.orEmpty()) else error = msg }
                    },
                )
            }
        }
    }

    LaunchedEffect(pv) {
        delay(100)
        runCatching { if (pv == null) urlFocus.requestFocus() else installFocus.requestFocus() }
    }
}

private fun networkLine(m: AddonManifest): String = when {
    m.anyDomain -> "Network: ANY website"
    m.domains.isEmpty() -> "Network: none"
    else -> "Network: " + m.domains.joinToString(", ")
}

/** ইনস্টল করা Add-on-এর তথ্য, সেটিংস ফর্ম, Refresh/Update। */
@Composable
fun AddonDetailsDialog(
    vm: ProvidersViewModel,
    provider: ProviderEntity,
    onDismiss: () -> Unit,
    onMessage: (String) -> Unit,
) {
    var addon by remember { mutableStateOf<AddonEntity?>(null) }
    var manifest by remember { mutableStateOf<AddonManifest?>(null) }
    val values = remember { mutableStateMapOf<String, String>() }
    var error by remember { mutableStateOf<String?>(null) }
    val closeFocus = remember { FocusRequester() }

    fun load() {
        vm.loadAddon(provider) { a ->
            addon = a
            val m = a?.let { runCatching { AddonManifest.parse(it.manifest) }.getOrNull() }
            manifest = m
            if (a != null && m != null && values.isEmpty()) values.putAll(vm.addonSettingValues(a, m))
        }
    }
    LaunchedEffect(provider.id) { load() }

    val a = addon
    val m = manifest
    AddonFrame(onDismiss) {
        if (a == null || m == null) {
            Text("Add-on", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text("Loading...", color = TextMuted, fontSize = 13.sp)
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                DialogTextButton("CLOSE", TextMuted, modifier = Modifier.focusRequester(closeFocus), onClick = onDismiss)
            }
        } else {
            Text("${m.name}  ·  v${m.version}", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            if (m.author.isNotBlank()) Text("by ${m.author}", color = TextMuted, fontSize = 12.sp)
            if (m.description.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(m.description, color = TextMuted, fontSize = 12.sp)
            }
            Spacer(Modifier.height(8.dp))
            Text(networkLine(m), color = if (m.anyDomain) Danger else TextMuted, fontSize = 11.sp)
            Text(
                if (a.verified) "Verified by StreamBD" else "Unverified (custom)",
                color = if (a.verified) Accent else TextMuted, fontSize = 11.sp,
            )
            a.lastStatus?.let { Text("Last run: $it", color = TextMuted, fontSize = 11.sp) }

            if (m.settings.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("SETTINGS", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                for (s in m.settings) {
                    Spacer(Modifier.height(8.dp))
                    Text(s.label, color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
                    if (s.type == "boolean") {
                        val on = values[s.key] == "true"
                        DialogTextButton(
                            if (on) "ON" else "OFF",
                            if (on) Accent else TextMuted,
                            onClick = { values[s.key] = if (on) "false" else "true" },
                        )
                    } else {
                        PremiumTextField(
                            value = values[s.key].orEmpty(),
                            onValueChange = { values[s.key] = it },
                            placeholder = s.label,
                            focusRequester = remember(s.key) { FocusRequester() },
                            imeAction = ImeAction.Done,
                            visualTransformation = if (s.type == "password") PasswordVisualTransformation() else VisualTransformation.None,
                        )
                    }
                }
            }

            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = Danger, fontSize = 12.sp)
            }

            val logTail = a.lastLog?.lines()?.takeLast(6)?.filter { it.isNotBlank() }.orEmpty()
            if (logTail.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text("LOG", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                for (line in logTail) Text(line, color = TextMuted, fontSize = 9.sp, lineHeight = 11.sp)
            }

            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                DialogTextButton("CLOSE", TextMuted, modifier = Modifier.focusRequester(closeFocus), onClick = onDismiss)
                if (a.sourceUrl != null || a.origin == "CATALOG") {
                    Spacer(Modifier.width(6.dp))
                    DialogTextButton("UPDATE", TextPrimary, onClick = {
                        error = null
                        vm.updateAddon(provider) { ok, msg ->
                            if (ok) { onMessage(msg.orEmpty()); onDismiss() } else { error = msg; load() }
                        }
                    })
                }
                Spacer(Modifier.width(6.dp))
                DialogTextButton("REFRESH", TextPrimary, onClick = {
                    error = null
                    vm.refreshAddon(provider, values.toMap()) { ok, msg ->
                        if (ok) { onMessage(msg.orEmpty()); onDismiss() } else { error = msg; load() }
                    }
                })
                Spacer(Modifier.width(6.dp))
                DialogTextButton("SAVE", Accent, onClick = {
                    vm.saveAddonSettings(a, values.toMap()) { onMessage("Settings saved."); onDismiss() }
                })
            }
        }
    }

    LaunchedEffect(a?.id) {
        delay(100)
        runCatching { closeFocus.requestFocus() }
    }
}

/** অফিশিয়াল ক্যাটালগ: তালিকা দেখে ইনস্টল/আপডেট বেছে নেওয়া। সিগনেচার যাচাই হয়ে প্রিভিউ আসে [onPreview]-এ। */
@Composable
fun AddonCatalogDialog(
    vm: ProvidersViewModel,
    onDismiss: () -> Unit,
    onPreview: (AddonPreview) -> Unit,
) {
    var rows by remember { mutableStateOf<List<com.streambd.iptv.data.addon.CatalogRow>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val closeFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        vm.loadCatalog { r, msg ->
            rows = r
            error = msg
        }
    }

    AddonFrame(onDismiss) {
        Text("Add-on Catalog", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text("Official, signed by StreamBD", color = TextMuted, fontSize = 11.sp)
        Spacer(Modifier.height(10.dp))

        val list = rows
        when {
            error != null -> Text(error.orEmpty(), color = Danger, fontSize = 12.sp)
            list == null -> Text("Loading...", color = TextMuted, fontSize = 13.sp)
            list.isEmpty() -> Text("No add-ons available yet.", color = TextMuted, fontSize = 13.sp)
            else -> for (row in list) {
                val e = row.entry
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Text("${e.name}  ·  v${e.version}", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    if (e.description.isNotBlank()) Text(e.description, color = TextMuted, fontSize = 12.sp)
                    if (e.author.isNotBlank()) Text("by ${e.author}", color = TextMuted, fontSize = 11.sp)
                    Spacer(Modifier.height(4.dp))
                    when {
                        row.needsNewerApp -> Text("Needs a newer app version", color = Danger, fontSize = 11.sp)
                        row.installedVersion != null && !row.updateAvailable ->
                            Text("Installed (v${row.installedVersion})", color = Accent, fontSize = 12.sp)
                        else -> DialogTextButton(
                            if (row.updateAvailable) "UPDATE to v${e.version}" else if (row.installedVersion != null) "REPLACE" else "INSTALL",
                            Accent,
                            onClick = {
                                error = null
                                vm.previewCatalogAddon(e.id) { p, msg ->
                                    if (p != null) onPreview(p) else error = msg
                                }
                            },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            DialogTextButton("CLOSE", TextMuted, modifier = Modifier.focusRequester(closeFocus), onClick = onDismiss)
        }
    }

    LaunchedEffect(rows) {
        delay(100)
        runCatching { closeFocus.requestFocus() }
    }
}
