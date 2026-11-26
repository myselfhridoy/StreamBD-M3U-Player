package com.streambd.iptv.data.xtream

import org.json.JSONArray
import org.json.JSONObject

/**
 * Xtream JSON → মডেল। নেটওয়ার্ক/Android ছাড়া, তাই ইউনিট টেস্টে সরাসরি চালানো যায়।
 */
object XtreamParsers {

    /** `get_series` (অ্যারে)। series_id নেই এমন সারি বাদ। ভুল JSON হলে [XtreamException]। */
    fun series(body: String): List<XtreamSeries> {
        val array = try {
            JSONArray(body)
        } catch (e: Exception) {
            throw XtreamException("Unexpected series response from the Xtream server.", e)
        }
        return buildList(array.length()) {
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val id = o.intOrNull("series_id") ?: continue
                add(
                    XtreamSeries(
                        seriesId = id,
                        name = o.text("name") ?: "Series $id",
                        cover = o.text("cover"),
                        categoryId = o.text("category_id"),
                        rating = o.text("rating"),
                        year = o.text("releaseDate")?.take(4) ?: o.text("release_date")?.take(4) ?: o.text("year"),
                    ),
                )
            }
        }
    }

    /**
     * `get_series_info`। `episodes` panel-ভেদে {"1":[...],"2":[...]} অবজেক্ট, নয়তো অ্যারের অ্যারে; দুটোই সামলানো হয়।
     * ফলাফল সিজন, তারপর এপিসোড নম্বর অনুসারে সাজানো।
     */
    fun episodes(body: String): List<XtreamEpisode> {
        val root = try {
            JSONObject(body)
        } catch (e: Exception) {
            throw XtreamException("Unexpected episode response from the Xtream server.", e)
        }
        val eps = root.opt("episodes")
        val out = ArrayList<XtreamEpisode>()

        fun addEpisode(o: JSONObject, seasonHint: Int?) {
            val id = o.text("id") ?: return
            val info = o.optJSONObject("info")
            val duration = info?.text("duration")
            val rating = info?.text("rating")
            out += XtreamEpisode(
                id = id,
                title = o.text("title") ?: "",
                season = o.intOrNull("season") ?: seasonHint,
                episode = o.intOrNull("episode_num"),
                extension = o.text("container_extension"),
                image = info?.text("movie_image"),
                subtitle = listOfNotNull(duration, rating?.let { "★ $it" }).joinToString(" · ").ifBlank { null },
            )
        }

        fun addList(arr: JSONArray, seasonHint: Int?) {
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { addEpisode(it, seasonHint) }
        }

        when (eps) {
            is JSONObject -> eps.keys().forEach { key ->
                eps.optJSONArray(key)?.let { addList(it, key.trim().toIntOrNull()) }
            }
            is JSONArray -> for (i in 0 until eps.length()) {
                val item = eps.opt(i)
                if (item is JSONArray) addList(item, i + 1) else if (item is JSONObject) addEpisode(item, null)
            }
        }
        return out.sortedWith(compareBy({ it.season ?: Int.MAX_VALUE }, { it.episode ?: Int.MAX_VALUE }))
    }
}

internal fun JSONObject.text(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return optString(key).trim().takeIf { it.isNotEmpty() && it != "null" }
}

internal fun JSONObject.intOrNull(key: String): Int? {
    if (!has(key) || isNull(key)) return null
    return when (val value = opt(key)) {
        is Number -> value.toInt()
        is String -> value.trim().toIntOrNull()
        else -> null
    }
}
