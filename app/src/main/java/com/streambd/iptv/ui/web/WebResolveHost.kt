package com.streambd.iptv.ui.web

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.ui.draw.alpha
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.streambd.iptv.data.addon.WebCapture
import com.streambd.iptv.data.addon.WebRequest
import com.streambd.iptv.data.addon.WebResolveManager
import com.streambd.iptv.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import javax.inject.Inject

@HiltViewModel
class WebResolveViewModel @Inject constructor(val manager: WebResolveManager) : ViewModel()

/**
 * add-on `{web:{...}}` চাইলে পূর্ণ-পর্দা WebView। ব্যবহারকারী নিজে "Verify you are human" সম্পন্ন করেন; ব্যবহারকারীর সেশন
 * (কুকি) শুধু এই WebView-তেই থাকে। পেজ লোডের সময় আসা প্রথম https `.m3u8` অনুরোধ নিষ্ক্রিয়ভাবে দেখে (বন্ধ/বদল না করে) URL ও
 * হেডার নিয়ে প্লেয়ারকে দেওয়া হয়। অ্যাপের কোনো JS-ব্রিজ নেই; পপআপ ও অন্য সাইটে নেভিগেশন ব্লক।
 *
 * AppNav-এর রুটে একবার বসাতে হবে: `WebResolveHost()`।
 */
@Composable
fun WebResolveHost(vm: WebResolveViewModel = hiltViewModel()) {
    val req by vm.manager.request.collectAsStateWithLifecycle()
    val r = req ?: return
    key(r.id) { WebResolveDialog(r, vm.manager) }
}

private fun isAllowedHost(host: String?, req: WebRequest): Boolean {
    val h = host?.lowercase() ?: return false
    val base = Uri.parse(req.url).host?.lowercase()
    val rootOf = { x: String -> x.split('.').takeLast(2).joinToString(".") }
    if (base != null && (h == base || rootOf(h) == rootOf(base))) return true
    return req.hosts.any { h == it || h.endsWith(".$it") }
}

// Chromium-ই, তবে WebView-এর "; wv" ও "Version/4.0" চিহ্ন বাদ — Turnstile এসব দেখে প্রায়ই লোড হতে চায় না
private fun cleanUserAgent(ua: String): String = ua.replace("; wv)", ")").replace(" Version/4.0", "")

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebResolveDialog(req: WebRequest, manager: WebResolveManager) {
    var status by remember { mutableStateOf("Loading…") }
    var webRef by remember { mutableStateOf<WebView?>(null) }
    val main = remember { Handler(Looper.getMainLooper()) }
    val done = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    // shouldInterceptRequest ব্যাকগ্রাউন্ড থ্রেডে চলে; WebView.getUrl() সেখানে ডাকা যাবে না, তাই শেষ পেজ URL আলাদা রাখা
    val lastPage = remember { java.util.concurrent.atomic.AtomicReference(req.url) }

    // পেজ লুকানো থাকে; শুধু যাচাই-চ্যালেঞ্জ ধরা পড়লে (বা ১২ সেকেন্ডেও স্ট্রিম না মিললে, ভরসা হিসেবে) দেখানো হয়
    var showPage by remember { mutableStateOf(false) }
    LaunchedEffect(req.id) {
        delay(12_000L)
        showPage = true
    }
    LaunchedEffect(req.id) {
        delay(req.timeoutSec * 1000L)
        manager.cancel(req.id, "Verification timed out.")
    }
    DisposableEffect(req.id) {
        onDispose {
            main.removeCallbacksAndMessages(null)
            webRef?.let { w ->
                runCatching { w.stopLoading(); w.loadUrl("about:blank"); w.destroy() }
            }
            webRef = null
        }
    }

    Dialog(
        onDismissRequest = { manager.cancel(req.id, "Cancelled.") },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnClickOutside = false),
    ) {
        Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(req.title, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (showPage) (req.hint ?: "If asked, tap “Verify you are human”. Playback starts automatically.") else "Getting the stream…",
                        color = TextMuted, fontSize = 11.sp, maxLines = 2,
                    )
                    Text(status, color = Accent, fontSize = 11.sp, maxLines = 1)
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    "CLOSE", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.14f))
                        .clickable { manager.cancel(req.id, "Cancelled.") }.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
            Box(Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize().alpha(if (showPage) 1f else 0f),
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.setSupportMultipleWindows(false)
                        settings.javaScriptCanOpenWindowsAutomatically = false
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        settings.userAgentString = req.userAgent ?: cleanUserAgent(settings.userAgentString)
                        val cm = CookieManager.getInstance()
                        cm.setAcceptCookie(true)
                        cm.setAcceptThirdPartyCookies(this, true)   // Turnstile-এর iframe-এর জন্য
                        webChromeClient = object : WebChromeClient() {
                            // পপআপ উইন্ডো খোলা যাবে না
                            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?) = false
                        }
                        val ua = settings.userAgentString
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val u = request.url
                                // শুধু https, শুধু অনুমোদিত হোস্টে নেভিগেশন; বিজ্ঞাপনের রিডাইরেক্ট ব্লক
                                return !(u.scheme == "https" && isAllowedHost(u.host, req))
                            }

                            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                                url?.let { lastPage.set(it) }
                                status = "Loading ${Uri.parse(url ?: "").host.orEmpty()}…"
                            }

                            override fun onPageFinished(view: WebView, url: String?) {
                                if (!done.get()) status = "Waiting for the stream… (complete verification if shown)"
                            }

                            // নিষ্ক্রিয় পর্যবেক্ষণ: সবসময় null ফেরত (অনুরোধ বন্ধ/বদল নয়)
                            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                                val u = request.url
                                // Cloudflare-এর যাচাই-চ্যালেঞ্জ লোড হচ্ছে → এখন পেজ দেখাতে হবে
                                if (u.host.equals("challenges.cloudflare.com", true) || (u.path ?: "").contains("/cdn-cgi/challenge-platform/")) {
                                    main.post { showPage = true }
                                }
                                if (u.scheme == "https" && (u.path ?: "").endsWith(".m3u8", ignoreCase = true) && done.compareAndSet(false, true)) {
                                    val h = request.requestHeaders.orEmpty()
                                    fun hv(name: String) = h.entries.firstOrNull { it.key.equals(name, true) }?.value?.takeIf { it.isNotBlank() }
                                    val pageUrl = Uri.parse(lastPage.get())
                                    val pageOrigin = "${pageUrl.scheme}://${pageUrl.host}"
                                    val referer = hv("Referer") ?: "$pageOrigin/"
                                    val origin = hv("Origin") ?: referer.let { r -> Uri.parse(r).let { p -> "${p.scheme}://${p.host}" } }
                                    val cap = WebCapture(
                                        url = u.toString(),
                                        headers = linkedMapOf("User-Agent" to ua, "Referer" to referer, "Origin" to origin),
                                    )
                                    main.post {
                                        status = "Stream found"
                                        manager.complete(req.id, cap)
                                    }
                                }
                                return null
                            }
                        }
                        webRef = this
                        val extra = req.referrer?.let { mapOf("Referer" to it) } ?: emptyMap()
                        loadUrl(req.url, extra)
                    }
                },
            )
            if (!showPage) {
                // পেজ লুকানো: ট্যাপ ধরে ফেলে (নিচের WebView-তে যাবে না) + অপেক্ষার চিহ্ন
                Box(
                    Modifier.fillMaxSize().background(Color.Black)
                        .clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) {},
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Accent)
                        Spacer(Modifier.height(12.dp))
                        Text(req.title, color = TextPrimary, fontSize = 14.sp)
                    }
                }
            }
            }
        }
    }
}
