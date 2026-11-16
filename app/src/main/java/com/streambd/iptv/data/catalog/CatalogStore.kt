package com.streambd.iptv.data.catalog

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** সার্ভারের চ্যানেল ক্যাটালগ ফোনে সংরক্ষণ: অফলাইনেও ম্যাচিং চলে। */
@Singleton
class CatalogStore @Inject constructor(@ApplicationContext ctx: Context) {
    private companion object { const val TAG = "CatalogStore" }

    private val file = File(ctx.filesDir, "channel_catalog.json")
    @Volatile private var cached: CatalogMatcher? = null
    @Volatile private var loaded = false

    /** ক্যাটালগ না থাকলে বা চ্যানেল না থাকলে null (তখন প্লেলিস্ট যেমন আছে তেমনই থাকে)। */
    val matcher: CatalogMatcher?
        get() {
            if (!loaded) synchronized(this) {
                if (!loaded) {
                    cached = try { if (file.isFile) parse(file.readText()) else null } catch (e: Exception) { Log.w(TAG, "read failed: ${e.message}"); null }
                    loaded = true
                }
            }
            return cached
        }

    val version: Long get() = matcher?.data?.version ?: 0L

    /** সার্ভারের উত্তর যাচাই করে সংরক্ষণ। নতুন ভার্সন হলে true। */
    fun save(json: String): Boolean {
        val before = version
        val parsed = parse(json) // অবৈধ হলে ব্যতিক্রম: আগেরটা অক্ষত
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json)
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        cached = parsed
        loaded = true
        return (parsed?.data?.version ?: 0L) != before
    }

    private fun parse(text: String): CatalogMatcher? {
        val o = JSONObject(text)
        val chArr = o.optJSONArray("channels") ?: return null
        val channels = ArrayList<CatalogChannel>(chArr.length())
        for (i in 0 until chArr.length()) {
            val c = chArr.optJSONObject(i) ?: continue
            val name = c.optString("name").trim()
            if (name.isEmpty()) continue
            val al = c.optJSONArray("aliases")
            val aliases = ArrayList<String>()
            if (al != null) for (j in 0 until al.length()) al.optString(j).takeIf { it.isNotBlank() }?.let(aliases::add)
            channels += CatalogChannel(
                id = c.optLong("id"), name = name, number = c.optInt("number", 0),
                group = c.optString("group").trim().ifEmpty { CatalogMatcher.OTHERS },
                logo = c.optString("logo").trim(), tvgId = c.optString("tvgId").trim(),
                aliases = aliases, event = c.optBoolean("event", false),
            )
        }
        if (channels.isEmpty()) return null
        val gArr = o.optJSONArray("groups")
        val groups = ArrayList<String>()
        if (gArr != null) for (i in 0 until gArr.length()) gArr.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }?.let(groups::add)
        return CatalogMatcher(CatalogData(o.optLong("version", 0L), groups, channels))
    }
}
