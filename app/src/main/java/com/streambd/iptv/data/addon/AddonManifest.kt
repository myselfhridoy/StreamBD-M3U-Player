package com.streambd.iptv.data.addon

import org.json.JSONArray
import org.json.JSONObject

class AddonException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** অ্যাপ এই API ভার্সন পর্যন্ত বোঝে। নতুন API-র Add-on পুরনো অ্যাপে ইনস্টল হবে না। */
const val ADDON_API_VERSION = 1

data class AddonSetting(
    val key: String,
    val label: String,
    /** "text" | "password" | "boolean" */
    val type: String,
    val default: String,
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
) {
    val anyDomain: Boolean get() = domains.any { it == "*" }

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
                val type = s.optString("type", "text").lowercase().let { if (it in setOf("text", "password", "boolean")) it else "text" }
                settings += AddonSetting(
                    key = key,
                    label = s.optString("label").ifBlank { key },
                    type = type,
                    default = if (s.has("default")) s.opt("default").toString() else "",
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
            )
        }
    }
}
