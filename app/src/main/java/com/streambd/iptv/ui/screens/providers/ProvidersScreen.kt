package com.streambd.iptv.ui.screens.providers

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit // নতুন
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.streambd.iptv.data.db.ProviderEntity
import com.streambd.iptv.ui.components.ConfirmDialog
import com.streambd.iptv.ui.theme.*
import kotlinx.coroutines.delay

@Composable
fun ProvidersScreen(
    onBack: () -> Unit,
    vm: ProvidersViewModel = hiltViewModel()
) {
    val providers by vm.providers.collectAsStateWithLifecycle()
    val isSyncing by vm.isSyncing.collectAsStateWithLifecycle()

    var showDialog by remember { mutableStateOf(false) }
    var providerToEdit by remember { mutableStateOf<ProviderEntity?>(null) } // নতুন
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
            Modifier.fillMaxWidth().padding(bottom = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "My Providers",
                color = TextPrimary,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
            
            PremiumAddButton(onClick = { 
                providerToEdit = null // নতুন অ্যাড করার জন্য null
                showDialog = true 
            })
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
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 160.dp), 
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(providers, key = { it.id }) { provider ->
                    ProviderGridCard(
                        provider = provider,
                        onEdit = { 
                            providerToEdit = provider
                            showDialog = true
                        },
                        onDelete = { providerToDelete = provider }
                    )
                }
            }
        }
    }

    if (isSyncing) {
        Dialog(onDismissRequest = { }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(8.dp)) {
                    Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Accent)
                        Spacer(Modifier.height(16.dp))
                        Text("Downloading channels...", color = TextPrimary, fontSize = 16.sp)
                    }
                }
            }
        }
    }

    if (showDialog) {
        ProviderDialog(
            initialProvider = providerToEdit, // যদি এডিট হয়, তবে ডেটা পাস হবে
            onDismiss = { showDialog = false },
            onSave = { name, url ->
                if (providerToEdit == null) {
                    vm.addProvider(name, url) { success, _ ->
                        if (success) showDialog = false
                    }
                } else {
                    vm.updateProvider(providerToEdit!!, name, url) { success, _ ->
                        if (success) showDialog = false
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
private fun PremiumAddButton(onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    
    Row(
        Modifier
            .clip(shape)
            .background(if (focused) Accent else Surface1)
            .border(1.dp, if (focused) Accent else Divider, shape)
            .onFocusChanged { focused = it.hasFocus }
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Filled.Add,
            contentDescription = "Add",
            tint = if (focused) Color.Black else Accent,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            "Add M3U",
            color = if (focused) Color.Black else Accent,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

// আপডেটেড কার্ড: Edit এবং Delete বাটন
@Composable
private fun ProviderGridCard(
    provider: ProviderEntity, 
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)

    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (focused) Surface1 else Panel)
            .border(2.dp, if (focused) Accent else Color.Transparent, shape)
            .onFocusChanged { focused = it.hasFocus }
            .focusable()
            .padding(10.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.PlaylistPlay, 
                contentDescription = null, 
                tint = if (focused) Accent else TextPrimary, 
                modifier = Modifier.size(28.dp)
            )
            
            // নতুন: এডিট এবং ডিলিট বাটনের রো
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onEdit,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        Icons.Filled.Edit, 
                        contentDescription = "Edit", 
                        tint = TextMuted, // প্রিমিয়াম লুক
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(Modifier.width(4.dp))
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        Icons.Filled.Delete, 
                        contentDescription = "Delete", 
                        tint = Danger,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
        
        Spacer(Modifier.height(10.dp))
        
        Text(
            provider.name, 
            color = TextPrimary, 
            fontSize = 14.sp, 
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(2.dp))
        Text(
            provider.url, 
            color = TextMuted, 
            fontSize = 11.sp, 
            maxLines = 2, 
            overflow = TextOverflow.Ellipsis,
            lineHeight = 14.sp
        )
    }
}

// অ্যাড এবং এডিট উভয়ের জন্য কমন ডায়ালগ
@Composable
private fun ProviderDialog(
    initialProvider: ProviderEntity?,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var name by remember { mutableStateOf(initialProvider?.name ?: "") }
    var url by remember { mutableStateOf(initialProvider?.url ?: "") }
    
    val nameFocus = remember { FocusRequester() }
    val urlFocus = remember { FocusRequester() }
    val cancelFocus = remember { FocusRequester() }
    val saveFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false 
        )
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .imePadding() 
                .navigationBarsPadding()
                .pointerInput(Unit) { detectTapGestures { onDismiss() } }
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                Modifier
                    .widthIn(max = 340.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .clip(RoundedCornerShape(4.dp))
                    .background(Panel)
                    .border(1.dp, Divider, RoundedCornerShape(4.dp))
                    .pointerInput(Unit) { detectTapGestures { } }
                    .padding(16.dp)
            ) {
                // টাইটেল পরিবর্তন হবে অ্যাড নাকি এডিট তার উপর ভিত্তি করে
                Text(
                    if (initialProvider == null) "Add M3U Provider" else "Edit M3U Provider", 
                    color = TextPrimary, 
                    fontSize = 16.sp, 
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(12.dp))
                
                Text("Provider Name", color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
                PremiumTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = "e.g. My IPTV",
                    focusRequester = nameFocus,
                    imeAction = ImeAction.Next,
                    onNext = { urlFocus.requestFocus() }
                )
                
                Spacer(Modifier.height(12.dp))
                
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
                        saveFocus.requestFocus() 
                    }
                )
                
                Spacer(Modifier.height(20.dp))
                
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DialogTextButton(
                        label = "CANCEL", 
                        color = TextMuted, 
                        modifier = Modifier.focusRequester(cancelFocus),
                        onClick = onDismiss
                    )
                    Spacer(Modifier.width(8.dp))
                    DialogTextButton(
                        label = if (initialProvider == null) "ADD" else "SAVE", // বাটনের নাম পরিবর্তন
                        color = Accent, 
                        modifier = Modifier.focusRequester(saveFocus),
                        onClick = { onSave(name, url) }
                    )
                }
            }
        }
    }
    
    LaunchedEffect(Unit) {
        delay(100) 
        runCatching { nameFocus.requestFocus() }
    }
}

@Composable
private fun DialogTextButton(
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(4.dp)
    
    Text(
        text = label,
        color = color,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .clip(shape)
            .background(if (focused) Color.White.copy(alpha = 0.12f) else Color.Transparent)
            .border(if (focused) 2.dp else 0.dp, if (focused) Accent else Color.Transparent, shape)
            .onFocusChanged { focused = it.hasFocus }
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

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
    val shape = RoundedCornerShape(4.dp)

    Box(
        Modifier
            .fillMaxWidth()
            .height(42.dp)
            .clip(shape)
            .background(Surface1)
            .border(
                width = if (isFocused) 2.dp else 1.dp,
                color = if (isFocused) Accent else Divider,
                shape = shape
            )
            .focusRequester(focusRequester)
            .onFocusChanged { isFocused = it.hasFocus }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        if (value.isEmpty()) {
            Text(placeholder, color = TextMuted.copy(alpha = 0.6f), fontSize = 14.sp)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            cursorBrush = SolidColor(Accent),
            textStyle = TextStyle(color = TextPrimary, fontSize = 14.sp),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
            keyboardActions = KeyboardActions(
                onNext = { onNext?.invoke() },
                onDone = { onDone?.invoke() }
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }
}
