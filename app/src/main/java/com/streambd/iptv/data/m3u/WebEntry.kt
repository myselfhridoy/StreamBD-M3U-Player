package com.streambd.iptv.data.m3u

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * প্লেলিস্টের "ওয়েব এন্ট্রি": লিংকটা সরাসরি স্ট্রিম নয়, একটা পেজ। প্লে-র সময় অ্যাপ পেজটা WebView-তে খোলে
 * (দরকারে ব্যবহারকারী Cloudflare "Verify you are human" করেন) এবং পেজ যে প্রথম `.m3u8` অনুরোধ করে সেটা প্লেয়ারে চালায়।
 *
 * M3U-তে লেখার নিয়ম (মূল [M3uParser] শুধু [fromAttrs] ও [wrap] ডাকে; বাকি সব এই ফাইলে):
 *
 * ```
 * #EXTINF:-1 group-title="Sports" x-web="1" x-web-hosts="player.example.net" x-stream-referer="https://player.example.net/",Channel
 * https://mysite.com/player.php?id=abc|Referer=https://mysite.com/&User-Agent=Mozilla/5.0
 * ```
 *
 *  - `x-web="1"` (বা true/yes): এটা ওয়েব এন্ট্রি।
 *  - লিংকের `|হেডার` এবং `x-web-<Header>`: **পেজ** খোলার অনুরোধের হেডার (Referer, User-Agent, ...)। `x-web-referrer` ও `x-web-referer` দুটোই চলে।
 *  - `x-web-hosts`: পেজ আর যেসব হোস্টে যেতে পারবে (কমা দিয়ে)। পেজের নিজের সাইট সবসময় অনুমোদিত।
 *  - `x-web-timeout`: সেকেন্ড (২০–৬০০, ডিফল্ট ৩০০)।
 *  - `x-stream-<Header>`: ধরা **স্ট্রিম** অনুরোধের হেডার (`x-stream-referer`, `x-stream-origin`, `x-stream-user-agent`,
 *    `x-stream-cookie`, `x-stream-authorization` ...)। WebView-র পাঠানো মানের ওপর এগুলো জোর করে বসে।
 *
 * হেডারের নাম `x-web-`/`x-stream-` বাদ দিয়ে হাইফেন-অংশগুলোর প্রথম অক্ষর বড় হাতের করে বানানো হয় (`user-agent` → `User-Agent`)।
 *
 * ভেতরে এন্ট্রিটা `webplay://p?u=…&h=Name:Value&s=Name:Value&hosts=…&t=…` রূপে চ্যানেলের streamUrl-এ থাকে (মান URL-এনকোড করা)।
 */
object WebEntry {
    const val SCHEME = "webplay://"
    const val DEFAULT_TIMEOUT_SEC = 300
    private const val MAX_HOSTS = 20

    /** পার্সার থেকে পাওয়া ঘোষণা। */
    class Spec(
        val pageHeaders: Map<String, String>,
        val hosts: List<String>,
        val timeoutSec: Int,
        val streamHeaders: Map<String, String>,
    )

    /** প্লে-টাইমে [decode] করা পরিকল্পনা। */
    class Plan(
        val pageUrl: String,
        val pageHeaders: Map<String, String>,
        val hosts: List<String>,
        val timeoutSec: Int,
        val streamHeaders: Map<String, String>,
    )

    fun isWeb(url: String) = url.startsWith(SCHEME, ignoreCase = true)

    /** #EXTINF-এর (ছোট হাতের কী-সহ) অ্যাট্রিবিউট থেকে। `x-web` না থাকলে বা বন্ধ হলে null। */
    fun fromAttrs(attrs: Map<String, String>): Spec? {
        if (attrs["x-web"]?.trim()?.lowercase() !in setOf("1", "true", "yes", "on")) return null
        val page = LinkedHashMap<String, String>()
        val stream = LinkedHashMap<String, String>()
        for ((k, v) in attrs) {
            if (v.isBlank()) continue
            when {
                k.startsWith("x-stream-") -> headerName(k.removePrefix("x-stream-"))?.let { stream[it] = v.trim() }
                k.startsWith("x-web-") -> {
                    val rest = k.removePrefix("x-web-")
                    if (rest != "hosts" && rest != "timeout") headerName(rest)?.let { page[it] = v.trim() }
                }
            }
        }
        val hosts = cleanHosts(attrs["x-web-hosts"].orEmpty())
        val timeout = (attrs["x-web-timeout"]?.trim()?.toIntOrNull() ?: DEFAULT_TIMEOUT_SEC).coerceIn(20, 600)
        return Spec(page, hosts, timeout, stream)
    }

    /** [urlWithPipe] (লিংক + ঐচ্ছিক `|হেডার`) → চ্যানেলে রাখার `webplay://` স্ট্রিং। */
    fun wrap(urlWithPipe: String, spec: Spec): String {
        val (page, pipe) = splitPipe(urlWithPipe)
        val headers = LinkedHashMap<String, String>()
        headers.putAll(pipe)
        for ((k, v) in spec.pageHeaders) putHeader(headers, k, v)   // স্পষ্ট x-web-* অ্যাট্রিবিউট `|`-এর ওপরে
        val sb = StringBuilder(SCHEME).append("p?u=").append(enc(page))
        for ((k, v) in headers) sb.append("&h=").append(enc(k)).append(':').append(enc(v))
        for ((k, v) in spec.streamHeaders) sb.append("&s=").append(enc(k)).append(':').append(enc(v))
        if (spec.hosts.isNotEmpty()) sb.append("&hosts=").append(enc(spec.hosts.joinToString(",")))
        sb.append("&t=").append(spec.timeoutSec)
        return sb.toString()
    }

    /** [wrap]-এর উল্টো। ভুল/ভাঙা হলে null। */
    fun decode(url: String): Plan? {
        if (!isWeb(url)) return null
        val query = url.substringAfter('?', "")
        if (query.isEmpty()) return null
        var page: String? = null
        val ph = LinkedHashMap<String, String>()
        val sh = LinkedHashMap<String, String>()
        var hosts: List<String> = emptyList()
        var timeout = DEFAULT_TIMEOUT_SEC
        for (part in query.split('&')) {
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            val key = part.substring(0, eq)
            val value = part.substring(eq + 1)
            when (key) {
                "u" -> page = dec(value)
                "h" -> pair(value)?.let { putHeader(ph, it.first, it.second) }
                "s" -> pair(value)?.let { putHeader(sh, it.first, it.second) }
                "hosts" -> hosts = cleanHosts(dec(value))
                "t" -> timeout = (value.toIntOrNull() ?: DEFAULT_TIMEOUT_SEC).coerceIn(20, 600)
            }
        }
        val p = page?.takeIf { it.isNotBlank() } ?: return null
        return Plan(p, ph, hosts, timeout, sh)
    }

    /** [base]-এর ওপর [over] বসায়; হেডারের নামে বড়/ছোট হাতের তফাত ধরা হয় না। */
    fun mergeHeaders(base: Map<String, String>, over: Map<String, String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        out.putAll(base)
        for ((k, v) in over) putHeader(out, k, v)
        return out
    }

    // ───────────── ভেতরের সহায়ক ─────────────

    private fun putHeader(map: MutableMap<String, String>, name: String, value: String) {
        map.keys.firstOrNull { it.equals(name, ignoreCase = true) }?.let { map.remove(it) }
        map[name] = value
    }

    /** "user-agent" → "User-Agent"; "referrer" → "Referer" (স্ট্যান্ডার্ড বানান)। ফাঁকা বা অবৈধ হলে null। */
    private fun headerName(suffix: String): String? {
        val s = suffix.trim().lowercase()
        if (s.isEmpty() || !s.all { it.isLetterOrDigit() || it == '-' }) return null
        if (s == "referrer") return "Referer"
        return s.split('-').filter { it.isNotEmpty() }.joinToString("-") { part -> part.replaceFirstChar { c -> c.uppercaseChar() } }
    }

    /** "https://a.b/x, c.d" → ["a.b", "c.d"] (শুধু হোস্টনাম, সর্বোচ্চ [MAX_HOSTS]টা)। */
    private fun cleanHosts(raw: String): List<String> =
        raw.split(',', ' ', ';', '\n')
            .map { it.trim().lowercase().substringAfter("://").substringBefore('/').substringBefore(':').removePrefix("*.") }
            .filter { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() || c == '.' || c == '-' } }
            .distinct()
            .take(MAX_HOSTS)

    private fun pair(value: String): Pair<String, String>? {
        val c = value.indexOf(':')
        if (c <= 0) return null
        val k = dec(value.substring(0, c)).trim()
        val v = dec(value.substring(c + 1))
        return if (k.isEmpty()) null else k to v
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
    private fun dec(s: String): String = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    /**
     * লিংক + `|হেডার`। PlayerManager-এর `splitHeaders`-এর মতোই: সেগমেন্টে `Key:Value` বা `k1=v1&k2=v2`।
     * (এটা ইচ্ছা করেই এখানে আলাদা: মূল প্লেয়ার-কোড না ছুঁয়ে রাখার জন্য।)
     */
    private fun splitPipe(raw: String): Pair<String, Map<String, String>> {
        val bar = raw.indexOf('|')
        if (bar < 0) return raw to emptyMap()
        val headers = LinkedHashMap<String, String>()
        for (segment in raw.substring(bar + 1).split('|')) {
            val eq = segment.indexOf('=')
            val colon = segment.indexOf(':')
            if (colon > 0 && (eq < 0 || colon < eq)) {
                val key = segment.substring(0, colon).trim()
                val value = segment.substring(colon + 1).trim()
                if (key.isNotEmpty() && key.none { it.isWhitespace() }) putHeader(headers, key, value)
            } else {
                for (p in segment.split('&')) {
                    val e = p.indexOf('=')
                    if (e <= 0) continue
                    val key = p.substring(0, e).trim()
                    val value = dec(p.substring(e + 1)).trim()
                    if (key.isNotEmpty()) putHeader(headers, key, value)
                }
            }
        }
        return raw.substring(0, bar) to headers
    }
}
