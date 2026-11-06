package com.streambd.iptv.ui.screens.providers

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streambd.iptv.data.db.ProviderEntity
import com.streambd.iptv.ui.components.AppDialog
import com.streambd.iptv.ui.components.AppDialogButton
import com.streambd.iptv.ui.theme.Accent
import com.streambd.iptv.ui.theme.Danger
import com.streambd.iptv.ui.theme.Divider
import com.streambd.iptv.ui.theme.Surface1
import com.streambd.iptv.ui.theme.TextMuted
import com.streambd.iptv.ui.theme.TextPrimary
import kotlinx.coroutines.delay

/**
 * Xtream Codes provider add/edit।
 *
 * পাঁচটি field:
 *  - Display name
 *  - Server URL (host:port বা http(s)://host:port)
 *  - Username
 *  - Password
 *  - User-Agent (optional)
 *
 * password ডেটাবেসে plaintext-এ যায় (v1); ভবিষ্যতে Keystore encryption যোগ করুন।
 */
@Composable
fun XtreamProviderDialog(
    initialProvider: ProviderEntity?,
    error: String?,
    onDismiss: () -> Unit,
    onSave: (name: String, url: String, username: String, password: String, userAgent: String?) -> Unit,
) {
    var name by remember { mutableStateOf(initialProvider?.name.orEmpty()) }
    var url by remember { mutableStateOf(initialProvider?.url.orEmpty()) }
    var username by remember { mutableStateOf(initialProvider?.username.orEmpty()) }
    var password by remember { mutableStateOf(initialProvider?.password.orEmpty()) }
    var userAgent by remember { mutableStateOf(initialProvider?.userAgent.orEmpty()) }
    var localError by remember { mutableStateOf<String?>(null) }

    val nameFocus = remember { FocusRequester() }
    val urlFocus = remember { FocusRequester() }
    val userFocus = remember { FocusRequester() }
    val passFocus = remember { FocusRequester() }
    val uaFocus = remember { FocusRequester() }
    val saveFocus = remember { FocusRequester() }

    fun submit() {
        if (name.isBlank() || url.isBlank() || username.isBlank() || password.isBlank()) {
            localError = "All fields are required"
            return
        }
        localError = null
        onSave(
            name.trim(),
            url.trim(),
            username.trim(),
            password,
            userAgent.trim().takeIf { it.isNotBlank() },
        )
    }

    AppDialog(onDismiss = onDismiss, width = 420.dp) {
        Text(
            if (initialProvider == null) "Add Xtream Server" else "Edit Xtream Server",
            color = TextPrimary,
            fontSize = 18.sp,
            modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 12.dp),
        )

        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            XtreamField(
                label = "Name",
                value = name,
                onValueChange = { name = it },
                placeholder = "e.g. My Xtream",
                focusRequester = nameFocus,
                imeAction = ImeAction.Next,
                onNext = { urlFocus.requestFocus() },
            )
            Spacer(Modifier.height(10.dp))

            XtreamField(
                label = "Server URL",
                value = url,
                onValueChange = { url = it },
                placeholder = "http://example.com:8080",
                focusRequester = urlFocus,
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Next,
                onNext = { userFocus.requestFocus() },
            )
            Spacer(Modifier.height(10.dp))

            XtreamField(
                label = "Username",
                value = username,
                onValueChange = { username = it },
                placeholder = "Xtream username",
                focusRequester = userFocus,
                imeAction = ImeAction.Next,
                onNext = { passFocus.requestFocus() },
            )
            Spacer(Modifier.height(10.dp))

            XtreamField(
                label = "Password",
                value = password,
                onValueChange = { password = it },
                placeholder = "Xtream password",
                focusRequester = passFocus,
                keyboardType = KeyboardType.Password,
                isPassword = true,
                imeAction = ImeAction.Next,
                onNext = { uaFocus.requestFocus() },
            )
            Spacer(Modifier.height(10.dp))

            XtreamField(
                label = "User-Agent (optional)",
                value = userAgent,
                onValueChange = { userAgent = it },
                placeholder = "StreamBD/1.0",
                focusRequester = uaFocus,
                imeAction = ImeAction.Done,
                onDone = {
                    saveFocus.requestFocus()
                    submit()
                },
            )

            val message = localError ?: error
            if (!message.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(message, color = Danger, fontSize = 12.sp)
            }

            Spacer(Modifier.height(12.dp))
            Text(
                "This app never shares your Xtream password. It is used only to talk to your server.",
                color = TextMuted,
                fontSize = 11.sp,
            )

            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.weight(1f))
                AppDialogButton("CANCEL", TextMuted, onClick = onDismiss)
                Spacer(Modifier.width(8.dp))
                AppDialogButton(
                    label = if (initialProvider == null) "ADD" else "SAVE",
                    color = Accent,
                    modifier = Modifier.focusRequester(saveFocus),
                    onClick = { submit() },
                )
            }
        }

        Spacer(Modifier.height(12.dp))
    }

    LaunchedEffect(Unit) {
        delay(120)
        runCatching { nameFocus.requestFocus() }
    }
}

@Composable
private fun XtreamField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    focusRequester: FocusRequester,
    keyboardType: KeyboardType = KeyboardType.Text,
    isPassword: Boolean = false,
    imeAction: ImeAction = ImeAction.Done,
    onNext: (() -> Unit)? = null,
    onDone: (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(6.dp)

    Column(Modifier.fillMaxWidth()) {
        Text(label, color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
        androidx.compose.foundation.layout.Box(
            Modifier
                .fillMaxWidth()
                .height(46.dp)
                .clip(shape)
                .background(Surface1)
                .border(if (focused) 2.dp else 1.dp, if (focused) Accent else Divider, shape)
                .focusRequester(focusRequester)
                .onFocusChanged { focused = it.hasFocus }
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart,
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
                visualTransformation = if (isPassword) {
                    PasswordVisualTransformation()
                } else {
                    VisualTransformation.None
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = keyboardType,
                    imeAction = imeAction,
                ),
                keyboardActions = KeyboardActions(
                    onNext = { onNext?.invoke() },
                    onDone = { onDone?.invoke() },
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
