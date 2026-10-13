package com.streambd.iptv.data.m3u

enum class ChannelKind { LIVE, MOVIE, SERIES }

data class ParsedChannel(
    val name: String,
    val logoUrl: String?,
    val groupTitle: String,
    val tvgId: String?,
    val streamUrl: String,
    val kind: ChannelKind,
)

/** Minimal M3U/M3U8 parser — reads #EXTINF attrs, #EXTGRP, and the URL line. */
object M3uParser {

    private val attrRegex = Regex("""([\w-]+)="([^"]*)""")

    fun parse(text: String): List<ParsedChannel> {
        val out = ArrayList<ParsedChannel>()
        var pending: Pending? = null

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit

                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    val attrs = attrRegex.findAll(line)
                        .associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    val display = line.substringAfter(',', "").trim()
                    pending = Pending(
                        name = attrs["tvg-name"]?.takeIf { it.isNotBlank() } ?: display,
                        logo = attrs["tvg-logo"]?.takeIf { it.isNotBlank() },
                        group = attrs["group-title"]?.takeIf { it.isNotBlank() } ?: "Ungrouped",
                        tvgId = attrs["tvg-id"]?.takeIf { it.isNotBlank() },
                    )
                }

                line.startsWith("#EXTGRP", ignoreCase = true) -> {
                    pending = pending?.copy(group = line.substringAfter(':', "").trim().ifBlank { "Ungrouped" })
                }

                line.startsWith("#") -> Unit // other directives: ignored for now

                else -> {
                    val p = pending ?: continue
                    out += ParsedChannel(
                        name = p.name.ifBlank { "Channel" },
                        logoUrl = p.logo,
                        groupTitle = p.group,
                        tvgId = p.tvgId,
                        streamUrl = line,
                        kind = classify(p.group, line),
                    )
                    pending = null
                }
            }
        }
        return out
    }

    private fun classify(group: String, url: String): ChannelKind {
        val g = group.lowercase()
        val u = url.lowercase()
        return when {
            g.contains("movie") || g.contains("vod") || g.contains("film") -> ChannelKind.MOVIE
            g.contains("series") || g.contains("show") -> ChannelKind.SERIES
            u.contains("/movie/") || u.contains("/vod/") -> ChannelKind.MOVIE
            u.contains("/series/") -> ChannelKind.SERIES
            else -> ChannelKind.LIVE
        }
    }

    private data class Pending(
        val name: String,
        val logo: String?,
        val group: String,
        val tvgId: String?,
    )
}
