package com.streambd.iptv.data.m3u

import com.streambd.iptv.data.model.ParsedPlaylist
import org.json.JSONObject
import java.net.URLEncoder

enum class ChannelKind { LIVE, MOVIE, SERIES, RADIO }

data class ParsedChannel(
    val name: String,
    val logoUrl: String?,
    val groupTitle: String,
    val tvgId: String?,
    val streamUrl: String,
    val kind: ChannelKind,
    
    // নতুন ফিল্ড
    val catchupType: String? = null,
    val catchupDays: Int = 0,
    val catchupSource: String? = null,
    val catchupTime: Int = 0,
    val catchupEnable: Boolean = false,
    val tvgShift: Int = 0,
    val parentCode: String? = null,
    val adult: Boolean = false,
    val audioTrack: String? = null,
    val videoTrack: String? = null,
    
    // DRM ফিল্ড
    val drmScheme: String? = null,
    val drmLicense: String? = null,
    
    // Series & VOD মেটাডেটা
    val season: Int? = null,
    val episode: Int? = null,
    val serieId: String? = null,
    val serieTitle: String? = null,
    val imdbId: String? = null,
    val tmdbId: String? = null,
    val seekPreview: String? = null,
)

object M3uParser {

    private val attrRegex = Regex("""([\w.:-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')""")
    private val epgAttrRegex = Regex("(?:url-tvg|x-tvg-url|tvg-url)\\s*=\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE)
    private val seriesGroupRegex = Regex("""\b(series|shows?)\b""", RegexOption.IGNORE_CASE)
    private val seriesNameRegex = Regex(
        """\bS\d{1,2}\s*[. _-]?\s*E\d{1,3}\b|\b\d{1,2}x\d{1,3}\b|\bseason\s*\d+""",
        RegexOption.IGNORE_CASE,
    )
    private val vodExtensions = setOf(
        "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v", "mpg", "mpeg", "3gp", "divx",
    )

    fun parse(text: String): ParsedPlaylist {
        val cleaned = text.removePrefix("\uFEFF")
        val lines = cleaned.lineSequence()
        
        val globalAttrs = parseHeader(lines.firstOrNull { it.startsWith("#EXTM3U") } ?: "")
        val epgUrls = parseEpgUrlsFromHeader(globalAttrs)
        val channels = parseChannels(cleaned.lineSequence(), globalAttrs)
        
        return ParsedPlaylist(channels, epgUrls)
    }

    private fun parseHeader(line: String): Map<String, String> {
        val attrs = HashMap<String, String>()
        for (m in attrRegex.findAll(line)) {
            val value = m.groups[2]?.value ?: m.groups[3]?.value ?: ""
            attrs[m.groupValues[1].lowercase()] = value.trim()
        }
        return attrs
    }

    private fun parseEpgUrlsFromHeader(attrs: Map<String, String>): List<String> {
        val raw = attrs["url-tvg"] ?: attrs["x-tvg-url"] ?: attrs["tvg-url"] ?: return emptyList()
        return raw.split(',', ';', ' ')
            .map(String::trim)
            .filter { it.startsWith("http", ignoreCase = true) }
            .distinct()
    }

    private fun parseChannels(lines: Sequence<String>, globalAttrs: Map<String, String>): List<ParsedChannel> {
        val out = ArrayList<ParsedChannel>()
        var pending: Pending? = null

        for (raw in lines) {
            val line = raw.trim()
            when {
                line.isEmpty() || line.startsWith("#EXTM3U") -> Unit
                line.startsWith("#EXTINF", ignoreCase = true) -> pending = parseExtInf(line)
                line.startsWith("#EXTGRP", ignoreCase = true) -> {
                    pending?.extGroup = line.substringAfter(':', "").trim().takeIf { it.isNotEmpty() }
                }
                line.startsWith("#EXTVLCOPT", ignoreCase = true) -> pending?.let { applyVlcOption(it, line) }
                line.startsWith("#EXTHTTP", ignoreCase = true) -> pending?.let { applyHttpJson(it, line) }
                line.startsWith("#KODIPROP", ignoreCase = true) -> pending?.let { applyKodiProp(it, line) }
                line.startsWith("#") -> Unit
                else -> {
                    val p = pending
                    pending = null
                    if (p == null || !line.contains("://")) continue

                    val group = p.groupTitle ?: p.extGroup ?: "Ungrouped"
                    val name = p.name.ifBlank { "Channel" }
                    
                    // চ্যানেল লেভেলের catchup না থাকলে গ্লোবাল catchup ব্যবহার হবে
                    val cType = p.catchupType ?: globalAttrs["catchup-type"] ?: globalAttrs["catchup"]
                    val cSource = p.catchupSource ?: globalAttrs["catchup-source"]
                    val cDays = p.catchupDays.takeIf { it > 0 } ?: globalAttrs["catchup-days"]?.toIntOrNull() ?: 0
                    val cTime = p.catchupTime.takeIf { it > 0 } ?: globalAttrs["catchup-time"]?.toIntOrNull() ?: 0
                    
                    // tvg-rec বা catchup-enable চেক
                    val isCatchupEnabled = p.catchupEnable || cType != null || p.tvgRec > 0

                    out += ParsedChannel(
                        name = name,
                        logoUrl = p.logo,
                        groupTitle = group,
                        tvgId = p.tvgId,
                        streamUrl = withHeaders(line, p.headers),
                        kind = classify(p.tvgType, group, name, line),
                        
                        catchupType = cType,
                        catchupSource = cSource,
                        catchupDays = cDays,
                        catchupTime = cTime,
                        catchupEnable = isCatchupEnabled,
                        tvgShift = p.tvgShift,
                        parentCode = p.parentCode,
                        adult = p.adult,
                        audioTrack = p.audioTrack,
                        videoTrack = p.videoTrack,
                        
                        drmScheme = p.drmScheme,
                        drmLicense = p.drmLicense,
                        
                        season = p.season,
                        episode = p.episode,
                        serieId = p.serieId,
                        serieTitle = p.serieTitle,
                        imdbId = p.imdbId,
                        tmdbId = p.tmdbId,
                        seekPreview = p.seekPreview,
                    )
                }
            }
        }
        return out
    }

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
            
            catchupType = (attrs["catchup-type"] ?: attrs["catchup"])?.takeIf { it.isNotBlank() },
            catchupSource = attrs["catchup-source"]?.takeIf { it.isNotBlank() },
            catchupDays = attrs["catchup-days"]?.toIntOrNull() ?: 0,
            catchupTime = attrs["catchup-time"]?.toIntOrNull() ?: 0,
            tvgShift = (attrs["tvg-shift"]?.toIntOrNull() ?: 0),
            parentCode = attrs["parent-code"]?.takeIf { it.isNotBlank() },
            adult = attrs["adult"] == "1",
            audioTrack = attrs["audio-track"]?.takeIf { it.isNotBlank() },
            videoTrack = attrs["video-track"]?.takeIf { it.isNotBlank() },
            
            season = attrs["tvg-season"]?.toIntOrNull(),
            episode = attrs["tvg-episode"]?.toIntOrNull(),
            serieId = attrs["tvg-serie"]?.takeIf { it.isNotBlank() },
            serieTitle = attrs["serie-title"]?.takeIf { it.isNotBlank() },
            imdbId = attrs["tvg-imdb"]?.takeIf { it.isNotBlank() },
            tmdbId = attrs["tvg-tmdb"]?.takeIf { it.isNotBlank() },
            seekPreview = attrs["seek_preview"]?.takeIf { it.isNotBlank() },
        )
    }

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

    private fun applyKodiProp(p: Pending, line: String) {
        val body = line.substringAfter(':', "").trim()
        val eq = body.indexOf('=')
        if (eq <= 0) return
        val key = body.substring(0, eq).trim().lowercase()
        val value = body.substring(eq + 1).trim()

        when (key) {
            "inputstream.adaptive.license_type" -> p.drmScheme = value
            "inputstream.adaptive.license_key" -> p.drmLicense = value
        }
    }

    private fun withHeaders(url: String, headers: Map<String, String>): String {
        if (headers.isEmpty()) return url
        val suffix = headers.entries.joinToString("&") { (k, v) ->
            "$k=${URLEncoder.encode(v, "UTF-8")}"
        }
        return if (url.contains('|')) "$url&$suffix" else "$url|$suffix"
    }

    private fun classify(tvgType: String?, group: String, name: String, url: String): ChannelKind {
        when (tvgType?.lowercase()) {
            "movie", "movies", "vod", "film" -> return ChannelKind.MOVIE
            "series", "serie", "tvshow", "show", "shows" -> return ChannelKind.SERIES
            "radio" -> return ChannelKind.RADIO
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
        val catchupType: String?,
        val catchupSource: String?,
        val catchupDays: Int,
        val catchupTime: Int,
        val tvgShift: Int,
        val parentCode: String?,
        val adult: Boolean,
        val audioTrack: String?,
        val videoTrack: String?,
        val season: Int?,
        val episode: Int?,
        val serieId: String?,
        val serieTitle: String?,
        val imdbId: String?,
        val tmdbId: String?,
        val seekPreview: String?,
    ) {
        var extGroup: String? = null
        val headers = LinkedHashMap<String, String>()
        var drmScheme: String? = null
        var drmLicense: String? = null
        var tvgRec: Int = 0
        var catchupEnable: Boolean = false
        
        // এখানে catchup-enable এবং tvg-rec চেক
        init {
            // Pending তৈরি হওয়ার সময় এগুলো সরাসরি এক্সট্রাক্ট করা যাবে না, তাই parseExtInf-এ করতে হবে
        }
    }
}
