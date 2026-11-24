package com.streambd.iptv.data.addon

import com.streambd.iptv.data.m3u.WebEntry
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * প্লেলিস্টের ওয়েব এন্ট্রি ([WebEntry]) প্লে-র সময় রিজলভ করে: পেজ WebView-তে খোলে ([WebResolveManager]), ধরা `.m3u8` ফেরত দেয়।
 * add-on-এর মতো `domains` পারমিশন এখানে নেই, তাই নিয়ম কঠোর: শুধু https, আর WebView শুধু পেজের নিজের সাইট ও `x-web-hosts`-এ যেতে পারে।
 */
@Singleton
class WebPlayResolver @Inject constructor(private val web: WebResolveManager) {

    suspend fun resolve(ref: String, name: String): ResolvedStream {
        val plan = WebEntry.decode(ref) ?: throw AddonException("This web entry is not valid.")
        val uri = runCatching { java.net.URI(plan.pageUrl) }.getOrNull()
        if (uri?.scheme?.lowercase() != "https" || uri.host.isNullOrBlank()) {
            throw AddonException("Web entries must use an https page link.")
        }

        // পেজ-অনুরোধের হেডার: Referer ও User-Agent আলাদা ফিল্ডে, বাকিগুলো extra হেডার
        fun pick(n: String) = plan.pageHeaders.entries.firstOrNull { it.key.equals(n, ignoreCase = true) }?.value
        val extra = plan.pageHeaders.filterKeys { !it.equals("Referer", true) && !it.equals("User-Agent", true) }

        val req = WebRequest(
            id = 0L, addonName = "Playlist", url = plan.pageUrl,
            referrer = pick("Referer"), userAgent = pick("User-Agent"),
            title = name, hint = null,
            hosts = plan.hosts, timeoutSec = plan.timeoutSec, headers = extra,
        )
        val cap = withTimeoutOrNull((plan.timeoutSec + 10) * 1000L) { web.capture(req) }
            ?: throw AddonException("Verification timed out.")

        // WebView-র পাঠানো হেডারের ওপর x-stream-* জোর করে বসে (পেজ আর ভিডিও-অনুরোধের Referer/Origin আলাদা হলে এটাই ঠিক করে)
        return ResolvedStream(
            url = cap.url,
            headers = WebEntry.mergeHeaders(cap.headers, plan.streamHeaders),
            drmScheme = null,
            drmLicense = null,
        )
    }
}
