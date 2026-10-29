package com.streambd.iptv.data.parser

import com.streambd.iptv.data.m3u.M3uParser
import com.streambd.iptv.data.model.ParsedPlaylist

/**
 * ফাইলের কন্টেন্ট দেখে অটোমেটিক ডিটেক্ট করে কোন পার্সার ব্যবহার হবে।
 */
object PlaylistParserFactory {

    fun parse(text: String): ParsedPlaylist {
        val trimmed = text.trimStart('\uFEFF', ' ', '\n', '\r')

        return when {
            // M3U / M3U8 ফরম্যাট
            trimmed.startsWith("#EXTM3U", ignoreCase = true) -> {
                M3uParser.parse(trimmed)
            }

            // JSON ফরম্যাট (OTC - OTT Navigator JSON Playlist)
            trimmed.startsWith("{") || trimmed.startsWith("[") -> {
                JsonPlaylistParser.parse(trimmed)
            }

            // ভবিষ্যতে এখানে XSPF, Tizen, WebOS পার্সার যোগ করা যাবে
            // trimmed.startsWith("<?xml") -> XmlPlaylistParser.parse(trimmed)

            // অজানা ফরম্যাট হলে খালি রিটার্ন করবে
            else -> ParsedPlaylist(emptyList(), emptyList())
        }
    }
}
