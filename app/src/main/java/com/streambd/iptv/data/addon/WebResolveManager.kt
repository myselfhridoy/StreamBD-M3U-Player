package com.streambd.iptv.data.addon

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** add-on-এর `resolveStream()` যা চেয়েছে: অ্যাপের WebView-তে [url] খুলে ব্যবহারকারীকে যাচাই করতে দাও, তারপর .m3u8 ধরো। */
data class WebRequest(
    val id: Long,
    val addonName: String,
    val url: String,
    val referrer: String?,
    val userAgent: String?,
    val title: String,
    val hint: String?,
    /** WebView এই হোস্টগুলোতে (এবং [url]-এর হোস্টে) যেতে পারবে; অন্য কোথাও নেভিগেশন ব্লক। */
    val hosts: List<String>,
    val timeoutSec: Int,
)

/** WebView-এ ধরা স্ট্রিম: প্লেয়ারকে এই URL ও হেডার দিয়ে চালাতে হবে। */
data class WebCapture(val url: String, val headers: Map<String, String>)

/**
 * add-on ↔ WebView-UI সেতু। [AddonResolver] সাসপেন্ড করে অপেক্ষা করে ([capture]); UI ([request] দেখে) WebView দেখায় এবং
 * ফল [complete]/[cancel]-এ জানায়। ডিজাইন: কুকি/সেশন কখনো add-on স্ক্রিপ্টে যায় না, শুধু ধরা URL ও হেডার প্লেয়ারে যায়।
 * একসাথে একটাই অনুরোধ; নতুন এলে পুরনোটা বাতিল।
 */
@Singleton
class WebResolveManager @Inject constructor() {
    private val _request = MutableStateFlow<WebRequest?>(null)
    val request: StateFlow<WebRequest?> = _request.asStateFlow()

    private var pending: CompletableDeferred<WebCapture>? = null
    private var nextId = 1L
    private val lock = Any()

    suspend fun capture(template: WebRequest): WebCapture {
        val d = CompletableDeferred<WebCapture>()
        val req: WebRequest
        synchronized(lock) {
            pending?.completeExceptionally(AddonException("Replaced by another request."))
            req = template.copy(id = nextId++)
            pending = d
            _request.value = req
        }
        try {
            return d.await()
        } finally {
            // সফল, ব্যর্থ বা কোরুটিন বাতিল (চ্যানেল বদল) — সবক্ষেত্রে WebView বন্ধ
            synchronized(lock) {
                if (pending === d) {
                    pending = null
                    _request.value = null
                }
            }
        }
    }

    fun complete(id: Long, capture: WebCapture) {
        synchronized(lock) { if (_request.value?.id == id) pending?.complete(capture) }
    }

    fun cancel(id: Long, reason: String) {
        synchronized(lock) { if (_request.value?.id == id) pending?.completeExceptionally(AddonException(reason)) }
    }
}
