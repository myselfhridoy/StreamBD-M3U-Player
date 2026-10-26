package com.streambd.iptv.data.m3u

import org.json.JSONObject
import java.net.URLEncoder

enum class ChannelKind { LIVE, MOVIE, SERIES }

data class ParsedChannel(
    val name: String,
    val logoUrl: String?,
    val groupTitle: String,
    val tvgId: String?,
    val streamUrl: String,
    val kind: ChannelKind,
)

/**
 * M3U/M3U8 পার্সার: #EXTINF অ্যাট্রিবিউট, #EXTGRP, #EXTVLCOPT, #EXTHTTP ও URL লাইন পড়ে।
 *
 * Header থাকলে streamUrl-এর শেষে Kodi-স্টাইলে যোগ হয়:
 *   https://host/live.m3u8|User-Agent=...&Referer=...   (মান URL-encoded)
 * PlayerManager এটা আলাদা করে request header বানাবে (ধাপ ৪)।
 */
object M3uParser {

    private val attrRegex = Regex("""([\w.:-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')""")

    private val movieRegex = Regex("""\b(movies?|vod|films?)\b""", RegexOption.IGNORE_CASE)
    private val seriesRegex = Regex("""\b(series|shows?)\b""", RegexOption.IGNORE_CASE)

    fun parse(text: String): List<ParsedChannel> =
        parse(text.removePrefix("\uFEFF").lineSequence())

    fun parse(lines: Sequence<String>): List<ParsedChannel> {
        val out = ArrayList<ParsedChannel>()
        var pending: Pending? = null

        for (raw in lines) {
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit

                line.startsWith("#EXTINF", ignoreCase = true) -> pending = parseExtInf(line)

                line.startsWith("#EXTGRP", ignoreCase = true) -> {
                    pending?.extGroup = line.substringAfter(':', "").trim().takeIf { it.isNotEmpty() }
                }

                line.startsWith("#EXTVLCOPT", ignoreCase = true) -> {
                    pending?.let { applyVlcOption(it, line) }
                }

                line.startsWith("#EXTHTTP", ignoreCase = true) -> {
                    pending?.let { applyHttpJson(it, line) }
                }

                line.startsWith("#") -> Unit // অন্য directive: আপাতত উপেক্ষা

                else -> {
                    val p = pending
                    pending = null
                    if (p == null || !line.contains("://")) continue

                    val group = p.groupTitle ?: p.extGroup ?: "Ungrouped"
                    out += ParsedChannel(
                        name = p.name.ifBlank { "Channel" },
                        logoUrl = p.logo,
                        groupTitle = group,
                        tvgId = p.tvgId,
                        streamUrl = withHeaders(line, p.headers),
                        kind = classify(group, line),
                    )
                }
            }
        }
        return out
    }

    /* ------------------------------- #EXTINF ------------------------------- */

    private fun parseExtInf(line: String): Pending {
        val sep = separatorIndex(line)
        val header = if (sep >= 0) line.substring(0, sep) else line
        val display = if (sep >= 0) line.substring(sep + 1).trim() else ""

        val attrs = HashMap<String, String>()
        for (m in attrRegex.findAll(header)) {
            val value = m.groups[2]?.value ?: m.groups[3]?.value ?: ""
            attrs[m.groupValues[1].lowercase()] = value.trim()
        }

        return Pending(
            name = display.ifBlank { attrs["tvg-name"].orEmpty() },
            logo = attrs["tvg-logo"]?.takeIf { it.isNotBlank() },
            groupTitle = attrs["group-title"]?.takeIf { it.isNotBlank() },
            tvgId = attrs["tvg-id"]?.takeIf { it.isNotBlank() },
        )
    }

    /** নাম আলাদা করা কমা: "..." কোটের বাইরের প্রথম কমা। */
    private fun separatorIndex(line: String): Int {
        var inQuote = false
        for (i in line.indices) {
            val c = line[i]
            when {
                c == '"' -> inQuote = !inQuote
                c == ',' && !inQuote -> return i
            }
        }
        return -1
    }

    /* ------------------------------- headers ------------------------------- */

    private fun applyVlcOption(p: Pending, line: String) {
        val body = line.substringAfter(':', "")
        val key = body.substringBefore('=').trim().lowercase()
        val value = body.substringAfter('=', "").trim().trim('"', '\'')
        if (value.isEmpty()) return
        when (key) {
            "http-user-agent" -> p.headers["User-Agent"] = value
            "http-referrer", "http-referer" -> p.headers["Referer"] = value
            "http-origin" -> p.headers["Origin"] = value
        }
    }

    private fun applyHttpJson(p: Pending, line: String) {
        val start = line.indexOf('{')
        if (start < 0) return
        val json = runCatching { JSONObject(line.substring(start)) }.getOrNull() ?: return
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = json.optString(key, "").trim()
            if (key.isNotBlank() && value.isNotEmpty()) p.headers[key] = value
        }
    }

    private fun withHeaders(url: String, headers: Map<String, String>): String {
        if (headers.isEmpty()) return url
        val suffix = headers.entries.joinToString("&") { (k, v) ->
            "$k=${URLEncoder.encode(v, "UTF-8")}"
        }
        return if (url.contains('|')) "$url&$suffix" else "$url|$suffix"
    }

    /* ------------------------------ ক্লাসিফাই ------------------------------ */

    private fun classify(group: String, url: String): ChannelKind {
        val u = url.lowercase()
        return when {
            movieRegex.containsMatchIn(group) -> ChannelKind.MOVIE
            seriesRegex.containsMatchIn(group) -> ChannelKind.SERIES
            u.contains("/movie/") || u.contains("/vod/") -> ChannelKind.MOVIE
            u.contains("/series/") -> ChannelKind.SERIES
            else -> ChannelKind.LIVE
        }
    }

    private class Pending(
        val name: String,
        val logo: String?,
        val groupTitle: String?,
        val tvgId: String?,
    ) {
        var extGroup: String? = null
        val headers = LinkedHashMap<String, String>()
    }
}
