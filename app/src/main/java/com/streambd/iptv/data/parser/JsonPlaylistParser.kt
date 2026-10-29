package com.streambd.iptv.data.parser

import com.streambd.iptv.data.m3u.ChannelKind
import com.streambd.iptv.data.m3u.ParsedChannel
import com.streambd.iptv.data.model.ParsedPlaylist
import org.json.JSONArray
import org.json.JSONObject

/**
 * OTC (OTT Navigator JSON Playlist) ফরম্যাট পার্সার।
 * মূল স্ট্রাকচার: { provider: {...}, categories: {...}, channels: [...] }
 */
object JsonPlaylistParser {

    fun parse(text: String): ParsedPlaylist {
        val channels = ArrayList<ParsedChannel>()
        val epgUrls = ArrayList<String>()

        try {
            val root = JSONObject(text)

            // EPG URL বের করা (প্রোভাইডার সেটিংস থেকে)
            root.optJSONObject("provider")?.let { provider ->
                val epg = provider.optString("epg", "")
                if (epg.isNotBlank()) epgUrls.add(epg)
            }

            // ক্যাটাগরি ম্যাপ বের করা (id -> name)
            val catMap = HashMap<String, String>()
            root.optJSONObject("categories")?.let { cats ->
                val keys = cats.keys()
                while (keys.hasNext()) {
                    val id = keys.next()
                    val cat = cats.optJSONObject(id)
                    val name = cat?.optString("name", id) ?: id
                    catMap[id] = name
                }
            }

            // চ্যানেল লিস্ট বের করা
            val channelsArray = root.optJSONArray("channels") ?: JSONArray()
            for (i in 0 until channelsArray.length()) {
                val item = channelsArray.getJSONObject(i)

                val name = item.optString("name", "Unknown")
                val url = item.optString("url", "")
                if (url.isBlank()) continue

                val logo = item.optString("icon", null)?.takeIf { it.isNotBlank() }
                val catId = item.optString("category", "")
                val group = catMap[catId] ?: "Ungrouped"
                val tvgId = item.optString("epg_id", null)?.takeIf { it.isNotBlank() }
                val type = item.optString("type", "live")

                val kind = when (type.lowercase()) {
                    "movie" -> ChannelKind.MOVIE
                    "series" -> ChannelKind.SERIES
                    else -> ChannelKind.LIVE
                }

                // হেডার্স থাকলে URL-এর সাথে যুক্ত করা
                val headers = item.optString("headers", "")
                val finalUrl = if (headers.isNotBlank()) {
                    if (url.contains('|')) "$url&$headers" else "$url|$headers"
                } else url

                channels.add(
                    ParsedChannel(
                        name = name,
                        logoUrl = logo,
                        groupTitle = group,
                        tvgId = tvgId,
                        streamUrl = finalUrl,
                        kind = kind,
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return ParsedPlaylist(channels, epgUrls)
    }
}
