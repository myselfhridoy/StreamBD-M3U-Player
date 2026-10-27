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
 */
object M3uParser {

    private val attrRegex = Regex("""([\w.:-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')""")
    private val epgAttrRegex = Regex(
        "(?:url-tvg|x-tvg-url|tvg-url)\\s*=\\s*\"([^\"]*)\"",
        RegexOption.IGNORE_CASE,
    )

    private val seriesGroupRegex = Regex("""\b(series|shows?)\b""", RegexOption.IGNORE_CASE)
    private val seriesNameRegex = Regex(
        """\bS\d{1,2}\s*[. _-]?\s*E\d{1,3}\b|\b\d{1,2}x\d{1,3}\b|\bseason\s*\d+""",
        RegexOption.IGNORE_CASE,
    )
    private val vodExtensions = setOf(
        "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v", "mpg", "mpeg", "3gp", "divx",
    )

    fun parse(text: String): List<ParsedChannel> =
        parse(text.removePrefix("\uFEFF").lineSequence())

    /** প্রথম লাইনের `url-tvg="a.xml.gz,b.xml"` থেকে EPG লিংকের তালিকা। না থাকলে খালি। */
    fun epgUrls(text: String): List<String> {
        val header = text.take(4096).lineSequence()
            .firstOrNull { it.trimStart('\uFEFF', ' ').startsWith("#EXTM3U", ignoreCase = true) }
            ?: return emptyList()
        val raw = epgAttrRegex.find(header)?.groupValues?.get(1) ?: return emptyList()
        return raw.split(',', ';', ' ')
            .map(String::trim)
            .filter { it.startsWith("http", ignoreCase = true) }
            .distinct()
    }

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

                line.startsWith("#") -> Unit

                else -> {
                    val p = pending
                    pending = null
                    if (p == null || !line.contains("://")) continue

                    val group = p.groupTitle ?: p.extGroup ?: "Ungrouped"
                    val name = p.name.ifBlank { "Channel" }
                    out += ParsedChannel(
                        name = name,
                        logoUrl = p.logo,
                        groupTitle = group,
                        tvgId = p.tvgId,
                        streamUrl = withHeaders(line, p.headers),
                        kind = classify(p.tvgType, group, name, line),
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
            tvgType = (attrs["tvg-type"] ?: attrs["type"])?.takeIf { it.isNotBlank() },
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

    /**
     * অন্য IPTV অ্যাপের মতো: গ্রুপের নাম একা যথেষ্ট নয়।
     * ১) tvg-type  ২) Xtream URL পাথ  ৩) ভিডিও ফাইল এক্সটেনশন  ৪) বাকি সব Live।
     */
    private fun classify(tvgType: String?, group: String, name: String, url: String): ChannelKind {
        when (tvgType?.lowercase()) {
            "movie", "movies", "vod", "film" -> return ChannelKind.MOVIE
            "series", "serie", "tvshow", "show", "shows" -> return ChannelKind.SERIES
            "live", "tv" -> return ChannelKind.LIVE
        }

        val path = url.substringBefore('|').substringBefore('?').substringBefore('#').lowercase()
        when {
            "/series/" in path -> return ChannelKind.SERIES
            "/movie/" in path -> return ChannelKind.MOVIE
            "/live/" in path -> return ChannelKind.LIVE
        }

        val lastSegment = path.substringAfterLast('/')
        val extension = if ('.' in lastSegment) lastSegment.substringAfterLast('.') else ""
        if (extension in vodExtensions) {
            return if (seriesNameRegex.containsMatchIn(name) || seriesGroupRegex.containsMatchIn(group)) {
                ChannelKind.SERIES
            } else {
                ChannelKind.MOVIE
            }
        }
        return ChannelKind.LIVE
    }

    private class Pending(
        val name: String,
        val logo: String?,
        val groupTitle: String?,
        val tvgId: String?,
        val tvgType: String?,
    ) {
        var extGroup: String? = null
        val headers = LinkedHashMap<String, String>()
    }
}
