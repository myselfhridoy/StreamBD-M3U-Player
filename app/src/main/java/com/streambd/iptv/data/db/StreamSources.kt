package com.streambd.iptv.data.db

import org.json.JSONArray
import org.json.JSONObject

/** একটা চ্যানেলের একটা স্ট্রিম সোর্স (মূল বা ব্যাকআপ)। হেডার URL-এর পেছনে `|Key:Value` আকারে থাকে। */
data class StreamSource(
    val url: String,
    val drmScheme: String? = null,
    val drmLicense: String? = null,
)

/** `channels.sources` কলামে ব্যাকআপ সোর্সগুলো JSON অ্যারে হিসেবে রাখা হয়। */
object StreamSources {
    fun encode(list: List<StreamSource>): String? {
        if (list.isEmpty()) return null
        val arr = JSONArray()
        for (s in list) {
            val o = JSONObject().put("u", s.url)
            s.drmScheme?.let { o.put("s", it) }
            s.drmLicense?.let { o.put("l", it) }
            arr.put(o)
        }
        return arr.toString()
    }

    fun decode(json: String?): List<StreamSource> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val url = o.optString("u").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                StreamSource(
                    url = url,
                    drmScheme = o.optString("s").takeIf { it.isNotBlank() },
                    drmLicense = o.optString("l").takeIf { it.isNotBlank() },
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}

/** প্রথমটা মূল সোর্স, তারপর ব্যাকআপগুলো (প্লেলিস্টের ক্রমে)। */
fun ChannelEntity.allSources(): List<StreamSource> =
    listOf(StreamSource(streamUrl, drmScheme, drmLicense)) + StreamSources.decode(sources)
