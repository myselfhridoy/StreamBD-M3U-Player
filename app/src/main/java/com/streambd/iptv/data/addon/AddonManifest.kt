package com.streambd.iptv.data.addon

import org.json.JSONArray
import org.json.JSONObject

class AddonException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** অ্যাপ এই API ভার্সন পর্যন্ত বোঝে। নতুন API-র Add-on পুরনো অ্যাপে ইনস্টল হবে না। */
const val ADDON_API_VERSION = 1

/** "select" সেটিংয়ের একটা বিকল্প: [value] সেভ হয়, [label] দেখায়। */
data class SettingOption(val value: String, val label: String)

data class AddonSetting(
    val key: String,
    val label: String,
    /** "text" | "password" | "boolean" | "select" */
    val type: String,
    val default: String,
    /** শুধু type="select"-এর জন্য। add-on-এ `options: ['on','off']` বা `[{value:'on',label:'Show'}]`। */
    val options: List<SettingOption> = emptyList(),
)

data class AddonManifest(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val author: String,
    /** নেটওয়ার্ক পারমিশন: "example.com" (সাবডোমেইনসহ), "*" = যেকোনো সাইট। খালি = নেটওয়ার্ক বন্ধ। */
    val domains: List<String>,
    /** লোকাল নেটওয়ার্ক (192.168.x.x ইত্যাদি) অনুমতি। */
    val allowLocal: Boolean,
    val apiVersion: Int,
    val settings: List<AddonSetting>,
    /** অটো-আপডেটের ব্যবধান (মিনিট)। 0 = অটো নয়। ডিফল্ট ৩৬০; ৩০–১৪৪০-এর মধ্যে সীমাবদ্ধ। */
    val refreshMinutes: Int = DEFAULT_REFRESH_MINUTES,
    /** true = অফিসিয়াল (Catalog) add-on হলেও ক্যাটালগে না মেলা চ্যানেল নিজের group/subgroup রাখে, "Others" এ যায় না। */
    val keepGroups: Boolean = false,
    /** true = এই add-on `findSources(info)` দিয়ে TMDB টাইটেলের জন্য মুভি/এপিসোডের সোর্স খুঁজে দিতে পারে। */
    val sources: Boolean = false,
    /** false = "শুধু সার্চ" add-on: generatePlaylist চালানো হয় না, Movies/Series/Live-এ কিছু যোগ হয় না (শুধু TMDB সার্চের সোর্স)। */
    val catalog: Boolean = true,
    /** true = add-on প्লে-টাইমে `{web:{url,...}}` দিয়ে অ্যাপের WebView (ব্যবহারকারীর মানব-যাচাইসহ) চাইতে পারে। ইনস্টলের সময় আলাদা অনুমতি দেখায়। */
    val web: Boolean = false,
) {
    val anyDomain: Boolean get() = domains.any { it == "*" }

    /** [host] ঘোষিত `domains`-এর মধ্যে (বা তার সাবডোমেইন) কিনা। */
    fun allowsHost(host: String): Boolean {
        if (anyDomain) return true
        val h = host.lowercase()
        return domains.any { h == it || h.endsWith(".$it") }
    }

    companion object {
        const val DEFAULT_REFRESH_MINUTES = 360
        private val ID_RE = Regex("^[a-z0-9][a-z0-9._-]{2,63}$")

        /** স্ক্রিপ্টের `addon` অবজেক্টের JSON থেকে। ভুল হলে [AddonException]। */
        fun parse(json: String): AddonManifest {
            if (json.isBlank()) throw AddonException("The script must define an `addon` object with id, name and version.")
            val o = try { JSONObject(json) } catch (e: Exception) { throw AddonException("Invalid add-on manifest.", e) }
            val id = o.optString("id").trim()
            if (!ID_RE.matches(id)) throw AddonException("Add-on id must be 3-64 characters: a-z, 0-9, dot, dash, underscore.")
            val name = o.optString("name").trim()
            if (name.isEmpty()) throw AddonException("Add-on name is missing.")
            val version = o.optString("version").trim().ifEmpty { "1.0.0" }
            val api = o.optInt("apiVersion", 1)
            if (api > ADDON_API_VERSION) throw AddonException("This add-on needs a newer version of the app (API $api).")

            val domains = ArrayList<String>()
            val dArr = o.optJSONArray("domains") ?: JSONArray()
            for (i in 0 until dArr.length()) {
                val d = dArr.optString(i).trim().lowercase().removePrefix("*.")
                if (d.isNotEmpty()) domains += d
            }

            val settings = ArrayList<AddonSetting>()
            val sArr = o.optJSONArray("settings") ?: JSONArray()
            for (i in 0 until sArr.length()) {
                val s = sArr.optJSONObject(i) ?: continue
                val key = s.optString("key").trim()
                if (key.isEmpty()) continue
                val type = s.optString("type", "text").lowercase().let { if (it in setOf("text", "password", "boolean", "select")) it else "text" }
                val opts = ArrayList<SettingOption>()
                s.optJSONArray("options")?.let { oa ->
                    for (j in 0 until oa.length()) {
                        val x = oa.opt(j)
                        if (x is JSONObject) {
                            val v = x.optString("value").trim()
                            if (v.isNotEmpty()) opts += SettingOption(v, x.optString("label").ifBlank { v })
                        } else if (x != null) {
                            val v = x.toString().trim()
                            if (v.isNotEmpty()) opts += SettingOption(v, v)
                        }
                    }
                }
                // বিকল্প না থাকলে select সাধারণ টেক্সট হয়ে যায়
                val finalType = if (type == "select" && opts.isEmpty()) "text" else type
                settings += AddonSetting(
                    key = key,
                    label = s.optString("label").ifBlank { key },
                    type = finalType,
                    default = if (s.has("default")) s.opt("default").toString() else "",
                    options = opts,
                )
            }

            return AddonManifest(
                id = id,
                name = name,
                version = version,
                description = o.optString("description").trim(),
                author = o.optString("author").trim(),
                domains = domains,
                allowLocal = o.optBoolean("allowLocal", false),
                apiVersion = api,
                settings = settings,
                refreshMinutes = o.optInt("refreshMinutes", DEFAULT_REFRESH_MINUTES).let { if (it <= 0) 0 else it.coerceIn(30, 1440) },
                keepGroups = o.optBoolean("keepGroups", false),
                sources = o.optBoolean("sources", false),
                catalog = o.optBoolean("catalog", true),
                web = o.optBoolean("web", false),
            )
        }
    }
}
