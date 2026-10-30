package com.streambd.iptv.ui.screens.providers

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.streambd.iptv.data.db.ProviderEntity
import com.streambd.iptv.ui.components.AppDialog
import com.streambd.iptv.ui.components.AppDialogButton
import com.streambd.iptv.ui.components.ConfirmDialog
import com.streambd.iptv.ui.theme.*

@Composable
fun ProvidersScreen(
    onBack: () -> Unit,
    vm: ProvidersViewModel = hiltViewModel()
) {
    val providers by vm.providers.collectAsStateWithLifecycle()
    val isSyncing by vm.isSyncing.collectAsStateWithLifecycle()

    var showAddDialog by remember { mutableStateOf(false) }
    var providerToDelete by remember { mutableStateOf<ProviderEntity?>(null) }
    
    Column(
        Modifier
            .fillMaxSize()
            .background(BgDark)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        // Header
        Row(
            Modifier.fillMaxWidth().padding(bottom = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "My Providers",
                color = TextPrimary,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold
            )
            
            Button(
                onClick = { showAddDialog = true },
                colors = ButtonDefaults.buttonColors(containerColor = Accent),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Add", tint = Color.Black)
                Spacer(Modifier.width(8.dp))
                Text("Add M3U", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }

        if (providers.isEmpty() && !isSyncing) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.PlaylistPlay, contentDescription = null, tint = TextMuted, modifier = Modifier.size(64.dp))
                    Spacer(Modifier.height(16.dp))
                    Text("No providers added yet.", color = TextPrimary, fontSize = 18.sp)
                    Text("Add your own M3U link to start watching.", color = TextMuted, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(providers, key = { it.id }) { provider ->
                    ProviderCard(provider, onDelete = { providerToDelete = provider })
                }
            }
        }
    }

    // নতুন প্রিমিয়াম লোডিং ডায়ালগ
    if (isSyncing) {
        AppDialog(onDismiss = { }, width = 320.dp) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Accent)
                Spacer(Modifier.height(16.dp))
                Text("Downloading channels...", color = TextPrimary, fontSize = 16.sp)
            }
        }
    }

    if (showAddDialog) {
        AddProviderDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { name, url ->
                vm.addProvider(name, url) { success, _ ->
                    if (success) {
                        showAddDialog = false
                    }
                }
            }
        )
    }

    providerToDelete?.let { provider ->
        ConfirmDialog(
            title = "Delete Provider?",
            body = "Are you sure you want to remove '${provider.name}'? All its channels will be deleted from this device.",
            confirmLabel = "DELETE",
            confirmIsDangerous = true,
            onCancel = { providerToDelete = null },
            onConfirm = {
                vm.deleteProvider(provider)
                providerToDelete = null
            }
        )
    }
}

@Composable
private fun ProviderCard(provider: ProviderEntity, onDelete: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)

    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (focused) Surface1 else Panel)
            .border(2.dp, if (focused) Accent else Color.Transparent, shape)
            .onFocusChanged { focused = it.isFocused }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.PlaylistPlay, contentDescription = null, tint = Accent, modifier = Modifier.size(32.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(provider.name, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(provider.url, color = TextMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(
            onClick = onDelete,
            modifier = Modifier.focusable()
        ) {
            Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = Danger)
        }
    }
}

// নতুন প্রিমিয়াম এবং থিম-কনসিস্টেন্ট ডায়ালগ
@Composable
private fun AddProviderDialog(
    onDismiss: () -> Unit,
    onAdd: (String, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    
    val nameFocus = remember { FocusRequester() }
    val urlFocus = remember { FocusRequester() }
    val cancelFocus = remember { FocusRequester() }
    val addFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    AppDialog(onDismiss = onDismiss, width = 420.dp) {
        Column(Modifier.padding(24.dp)) {
            Text("Add M3U Provider", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(16.dp))
            
            // Provider Name ফিল্ড
            Text("Provider Name", color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
            PremiumTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = "e.g. My IPTV",
                focusRequester = nameFocus,
                imeAction = ImeAction.Next,
                onNext = { urlFocus.requestFocus() }
            )
            
            Spacer(Modifier.height(16.dp))
            
            // URL ফিল্ড
            Text("M3U URL", color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
            PremiumTextField(
                value = url,
                onValueChange = { url = it },
                placeholder = "http://example.com/list.m3u",
                focusRequester = urlFocus,
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done,
                onDone = { 
                    focusManager.clearFocus()
                    addFocus.requestFocus() 
                }
            )
            
            Spacer(Modifier.height(24.dp))
            
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                AppDialogButton(
                    label = "CANCEL", 
                    color = TextMuted, 
                    modifier = Modifier.focusRequester(cancelFocus),
                    onClick = onDismiss
                )
                Spacer(Modifier.width(16.dp))
                AppDialogButton(
                    label = "ADD", 
                    color = Accent, 
                    modifier = Modifier.focusRequester(addFocus),
                    onClick = { onAdd(name, url) }
                )
            }
        }
    }
    
    // টিভি রিমোট দিয়ে ফোকাস হ্যান্ডেল করা
    LaunchedEffect(Unit) {
        runCatching { nameFocus.requestFocus() }
    }
}

// কাস্টম ইনপুট ফিল্ড যা অ্যাপের থিমের সাথে মিশে যায়
@Composable
private fun PremiumTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    focusRequester: FocusRequester,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Done,
    onNext: (() -> Unit)? = null,
    onDone: (() -> Unit)? = null
) {
    var isFocused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)

    Box(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(shape)
            .background(Surface1)
            .border(
                width = if (isFocused) 2.dp else 1.dp,
                color = if (isFocused) Accent else Divider,
                shape = shape
            )
            .focusRequester(focusRequester)
            .onFocusChanged { isFocused = it.isFocused }
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        if (value.isEmpty()) {
            Text(placeholder, color = TextMuted.copy(alpha = 0.6f), fontSize = 15.sp)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            cursorBrush = SolidColor(Accent),
            textStyle = TextStyle(color = TextPrimary, fontSize = 15.sp),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
            keyboardActions = KeyboardActions(
                onNext = { onNext?.invoke() },
                onDone = { onDone?.invoke() }
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }
}
