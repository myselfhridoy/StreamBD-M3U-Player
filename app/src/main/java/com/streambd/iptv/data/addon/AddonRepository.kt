package com.streambd.iptv.data.addon

import android.content.Context
import android.util.Log
import com.streambd.iptv.data.db.AddonDao
import com.streambd.iptv.data.db.AddonEntity
import com.streambd.iptv.data.db.ProviderDao
import com.streambd.iptv.data.db.ProviderEntity
import com.streambd.iptv.data.parser.PlaylistParserFactory
import com.streambd.iptv.data.repo.ChannelRepository
import com.streambd.iptv.data.repo.PlaylistSync
import com.streambd.iptv.data.repo.SyncException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/** ইনস্টলের আগে ইউজারকে দেখানোর তথ্য। */
data class AddonPreview(
    val manifest: AddonManifest,
    val script: String,
    val sourceUrl: String?,
    /** একই id-র Add-on আগে থেকে ইনস্টল থাকলে তার ভার্সন (আপডেট বোঝাতে)। */
    val installedVersion: String?,
    /** "CUSTOM" (ইউজারের নিজের) বা "CATALOG" (অফিশিয়াল, সাইন করা)। */
    val origin: String = "CUSTOM",
    /** শুধু সিগনেচার যাচাই পাস করলেই true। */
    val verified: Boolean = false,
)

const val KILL_STATUS = "Disabled by StreamBD"
const val USER_DISABLED = "Disabled by you"

@Singleton
class AddonRepository @Inject constructor(
    private val addons: AddonDao,
    private val providers: ProviderDao,
    private val engine: AddonEngine,
    private val channels: ChannelRepository,
    private val sync: PlaylistSync,
    private val http: OkHttpClient,
    @ApplicationContext private val context: Context,
) {
    private companion object {
        const val MORE_KEY = "__sb_more"
        const val MAX_DOWNLOAD_CHARS = 1_000_000
        const val PREFS = "streambd_addons"
        const val KEY_OPT_OUT = "auto_install_opt_out"
    }

    private val prefs get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** ইউজার যেসব অফিশিয়াল Add-on নিজে মুছেছে: অটো-ইনস্টল এগুলো আর জোর করে বসায় না। */
    fun autoInstallOptOut(): Set<String> = prefs.getStringSet(KEY_OPT_OUT, emptySet()).orEmpty().toSet()

    private fun setOptOut(id: String, out: Boolean) {
        val cur = autoInstallOptOut().toMutableSet()
        if (if (out) cur.add(id) else cur.remove(id)) prefs.edit().putStringSet(KEY_OPT_OUT, cur).apply()
    }

    /** ইউজারের নিজের বন্ধ করা: "Disabled by you" (কিল-সুইচ থেকে আলাদা, তাই সার্ভার এটা আবার চালু করে না)। */
    suspend fun setEnabled(providerId: Long, on: Boolean): String {
        val a = addons.getByProviderId(providerId) ?: throw AddonException("Add-on not found.")
        if (!on) {
            addons.update(a.copy(enabled = false, lastStatus = USER_DISABLED))
            channels.clearProviderData(providerId) // চ্যানেলগুলো তালিকা থেকে সরে যায়; Enable করলে আবার আসে
            return "${a.name} is turned off."
        }
        addons.update(a.copy(enabled = true, lastStatus = null))
        return try {
            refresh(providerId)
        } catch (e: AddonException) {
            "Turned on, but the first run failed: ${e.message}"
        }
    }

    fun observeAll() = addons.observeAll()

    suspend fun forProvider(providerId: Long): AddonEntity? = addons.getByProviderId(providerId)

    /** URL থেকে স্ক্রিপ্ট নামায়। */
    suspend fun download(url: String): String = withContext(Dispatchers.IO) {
        val u = url.trim()
        if (!u.startsWith("http://", true) && !u.startsWith("https://", true)) {
            throw AddonException("Enter a valid http(s) link.")
        }
        val req = try { Request.Builder().url(u).build() } catch (e: IllegalArgumentException) {
            throw AddonException("That link doesn't look valid.", e)
        }
        try {
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw AddonException("Server returned ${r.code}.")
                val text = r.body?.string().orEmpty()
                if (text.length > MAX_DOWNLOAD_CHARS) throw AddonException("Script is too large.")
                text
            }
        } catch (e: AddonException) {
            throw e
        } catch (e: java.io.IOException) {
            throw AddonException("Could not download the add-on: ${e.message ?: "network error"}", e)
        }
    }

    suspend fun preview(script: String, sourceUrl: String?): AddonPreview {
        if (script.isBlank()) throw AddonException("The add-on file is empty.")
        val manifest = engine.describe(script)
        val existing = addons.getByAddonId(manifest.id)
        return AddonPreview(manifest, script, sourceUrl, existing?.version)
    }

    /** ইনস্টল (বা একই id থাকলে আপডেট)। ইউজারের সেটিংস ও স্টোরেজ থাকে। providerId ফেরত দেয়। */
    suspend fun install(p: AddonPreview): Long {
        val m = p.manifest
        val now = System.currentTimeMillis()
        val manifestJson = manifestToJson(m)
        val existing = addons.getByAddonId(m.id)
        if (p.origin == "CATALOG") setOptOut(m.id, false)

        if (existing != null) {
            addons.update(
                existing.copy(
                    name = m.name, version = m.version, script = p.script, manifest = manifestJson,
                    sourceUrl = p.sourceUrl ?: existing.sourceUrl, updatedAt = now,
                    // সাইন না করা কপি দিয়ে আপডেট করলে "verified" থাকে না
                    origin = p.origin, verified = p.verified,
                ),
            )
            providers.getById(existing.providerId)?.let { providers.update(it.copy(name = m.name)) }
            return existing.providerId
        }

        val providerId = providers.insert(ProviderEntity(name = m.name, url = "addon:${m.id}", type = "ADDON"))
        addons.insert(
            AddonEntity(
                addonId = m.id, name = m.name, version = m.version, script = p.script, manifest = manifestJson,
                settings = "{}", storage = "{}", enabled = true, origin = p.origin, sourceUrl = p.sourceUrl,
                verified = p.verified, providerId = providerId, installedAt = now, updatedAt = now,
                lastRunAt = 0L, lastStatus = null, lastLog = null,
            ),
        )
        return providerId
    }

    /**
     * ক্যাটালগ থেকে ইনস্টলের প্রিভিউ। সিগনেচার, সংস্করণ ও ম্যানিফেস্ট মিল যাচাই করে;
     * কোনোটা ব্যর্থ হলে [AddonException]।
     */
    suspend fun previewCatalog(item: CatalogAddon): AddonPreview {
        val e = item.entry
        if (e.minAppVersion > com.streambd.iptv.BuildConfig.VERSION_CODE) {
            throw AddonException("This add-on needs a newer version of the app.")
        }
        if (!AddonSignature.verify(e.id, e.version, item.script, e.signature)) {
            throw AddonException("Signature check failed. This add-on was not installed.")
        }
        val manifest = engine.describe(item.script)
        if (manifest.id != e.id || manifest.version != e.version) {
            throw AddonException("The add-on does not match its catalog entry.")
        }
        val existing = addons.getByAddonId(manifest.id)
        return AddonPreview(manifest, item.script, null, existing?.version, origin = "CATALOG", verified = true)
    }

    /** কিল-সুইচ: সার্ভার যেসব অফিশিয়াল Add-on বন্ধ করেছে, ইনস্টল থাকলে বন্ধ করে। কয়টা বন্ধ হলো ফেরত দেয়। */
    suspend fun applyKillSwitch(blocked: Collection<String>): Int {
        var n = 0
        for (id in blocked) {
            val a = addons.getByAddonId(id) ?: continue
            if (a.origin != "CATALOG" || !a.enabled) continue
            addons.update(a.copy(enabled = false, lastStatus = KILL_STATUS))
            n++
        }
        return n
    }

    /**
     * কিল-সুইচ তুলে নেওয়া হলে (ক্যাটালগে আবার চালু, `blocked` এ নেই) শুধু কিল-সুইচে বন্ধ হওয়া Add-on আবার চালু করে।
     * @return কয়টা চালু হলো
     */
    suspend fun restoreUnblocked(available: Collection<String>, blocked: Collection<String>): Int {
        var n = 0
        for (a in addons.observeAll().first()) {
            if (a.enabled || a.origin != "CATALOG" || a.lastStatus != KILL_STATUS) continue
            if (a.addonId in available && a.addonId !in blocked) {
                addons.update(a.copy(enabled = true, lastStatus = null))
                n++
            }
        }
        return n
    }

    suspend fun saveSettings(addon: AddonEntity, values: Map<String, String>) {
        val o = JSONObject()
        for ((k, v) in values) o.put(k, v)
        addons.setSettings(addon.id, o.toString())
    }

    /** চ্যানেল ক্যাটালগ বদলালে: Catalog থেকে নেওয়া চালু add-on-গুলো নতুন ক্যাটালগ দিয়ে আবার ইমপোর্ট। */
    suspend fun refreshOfficial() {
        for (a in addons.observeAll().first()) {
            if (a.origin != "CATALOG" || !a.enabled) continue
            try {
                refresh(a.providerId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("AddonRepository", "official refresh failed for ${a.providerId}: ${e.message}")
            }
        }
    }

    /** স্ক্রিপ্ট চালিয়ে চ্যানেল ইমপোর্ট। সফল হলে সারাংশ ফেরত, ব্যর্থ হলে [AddonException]। */
    suspend fun refresh(providerId: Long, maxRounds: Int = 1): String {
        var out: Pair<String, Boolean>
        var round = 0
        while (true) {
            round += 1
            out = refreshOnce(providerId)
            if (!out.second || round >= maxRounds) break
        }
        return out.first
    }

    /** ম্যানিফেস্টে catalog:false, অথবা ইউজারের "catalog" সেটিং off/no/false/0 হলে add-on শুধু সার্চে ব্যবহার হয়। */
    private fun isSearchOnly(m: AddonManifest, settings: Map<String, String>): Boolean {
        if (!m.catalog) return true
        // ইউজারের মান, না থাকলে ম্যানিফেস্টের ডিফল্ট
        val v = (settings["catalog"] ?: m.settings.firstOrNull { it.key == "catalog" }?.default)?.trim()?.lowercase()
        return v in setOf("off", "no", "false", "0")
    }

    /** (সারাংশ, আরও স্ক্যান বাকি?) */
    private suspend fun refreshOnce(providerId: Long): Pair<String, Boolean> {
        val addon = addons.getByProviderId(providerId) ?: throw AddonException("Add-on not found.")
        if (!addon.enabled) throw AddonException("This add-on is turned off.")
        val manifest = AddonManifest.parse(addon.manifest)

        // শুধু-সার্চ add-on: স্ক্যান নেই, তালিকায় কিছু যোগ হয় না
        if (isSearchOnly(manifest, jsonToMap(addon.settings))) {
            // আগের স্ক্যানের তালিকা ও কার্সর মুছে দেওয়া হয় (পরে আবার on করলে নতুন করে শুরু)
            channels.clearProviderData(addon.providerId)
            addons.update(addon.copy(storage = "{}", lastRunAt = System.currentTimeMillis(), lastStatus = null, lastLog = "Search-only add-on: no catalog to scan."))
            return "Search-only add-on: nothing to scan. It is used from Search." to false
        }

        val result = engine.run(addon.script, manifest, jsonToMap(addon.settings), jsonToMap(addon.storage))
        val logText = result.log.joinToString("\n").take(8_000)
        val now = System.currentTimeMillis()

        // ধাপে ধাপে স্ক্যান: #EXTM3U লাইনে x-sb-more / x-sb-append
        val head = result.m3u?.lineSequence()?.firstOrNull { it.isNotBlank() }.orEmpty()
        val more = head.startsWith("#EXTM3U") && head.contains("x-sb-more=\"1\"")
        val append = head.startsWith("#EXTM3U") && head.contains("x-sb-append=\"1\"")
        val storageMap = HashMap(result.storage)
        if (result.error == null) storageMap[MORE_KEY] = if (more) "1" else "0"
        val storageJson = JSONObject().also { o -> for ((k, v) in storageMap) o.put(k, v) }.toString()

        val body = result.m3u
        if (result.error != null || body == null) {
            addons.setRunResult(addon.id, storageJson, now, "Error: ${result.error}", logText)
            throw AddonException(result.error ?: "The add-on failed.")
        }

        val parsed = try {
            PlaylistParserFactory.parse(body)
        } catch (e: OutOfMemoryError) {
            addons.setRunResult(addon.id, storageJson, now, "Error: out of memory", logText)
            throw AddonException("Not enough memory to read this add-on's list. Return fewer items per run.")
        }
        val list = parsed.channels.filter { !it.isNestedPlaylist }
        if (list.isEmpty() && append) {
            // শেষ ধাপে নতুন কিছু না থাকলেও ঠিক আছে
            addons.setRunResult(addon.id, storageJson, now, if (more) "OK: scanning continues" else "OK", logText)
            return "No new items." to more
        }
        if (list.isEmpty()) {
            addons.setRunResult(addon.id, storageJson, now, "Error: no channels returned", logText)
            throw AddonException("The add-on returned no channels.")
        }

        val count = try {
            // Catalog (সাইন করা) add-on অফিসিয়াল: প্রিমিয়াম আইকন পায়, ক্যাটালগ দিয়ে সাজে
            val official = addon.origin == "CATALOG"
            channels.import(list, isPremium = official, providerId = providerId, official = official, keepGroups = manifest.keepGroups, append = append)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            addons.setRunResult(addon.id, storageJson, now, "Error: could not save channels", logText)
            throw AddonException("Could not save the channels on this device.", e)
        } catch (e: OutOfMemoryError) {
            addons.setRunResult(addon.id, storageJson, now, "Error: out of memory while saving", logText)
            throw AddonException("Not enough memory to save the channels. Return fewer items per run.")
        }

        var summary = "Imported $count channels."
        if (parsed.epgUrls.isNotEmpty()) {
            summary += try {
                " ${sync.syncEpg(parsed.epgUrls.joinToString("\n"))} guide entries."
            } catch (e: CancellationException) {
                throw e
            } catch (e: SyncException) {
                " TV guide: ${e.message}"
            } catch (e: Exception) {
                ""
            }
        }
        addons.setRunResult(addon.id, storageJson, now, "OK: $count channels" + (if (more) " (more coming)" else ""), logText)
        return (summary + (if (more) " More will load in the background." else "")) to more
    }

    /**
     * অটো-রিফ্রেশের জন্য: manifest.refreshMinutes পার হলে (ব্যর্থ হলে ৩০ মিনিট পর আবার) রিফ্রেশ করে।
     * @return রিফ্রেশ চালানো হয়ে থাকলে true; ব্যর্থ হলে [AddonException] ছোড়ে।
     */
    suspend fun refreshIfDue(providerId: Long): Boolean {
        val a = addons.getByProviderId(providerId) ?: return false
        if (!a.enabled) return false
        val m = runCatching { AddonManifest.parse(a.manifest) }.getOrNull() ?: return false
        if (m.refreshMinutes <= 0 || isSearchOnly(m, jsonToMap(a.settings))) return false
        // স্ক্যান বাকি থাকলে (x-sb-more) ১ মিনিট পরই আবার; এক চেকে কয়েক রাউন্ড
        val moreLeft = jsonToMap(a.storage)[MORE_KEY] == "1"
        if (moreLeft && System.currentTimeMillis() - a.lastRunAt >= 60_000L) {
            refresh(providerId, maxRounds = 8)
            return true
        }
        val failed = a.lastStatus?.startsWith("Error") == true
        val minutes = if (failed) minOf(m.refreshMinutes, 30) else m.refreshMinutes
        if (System.currentTimeMillis() - a.lastRunAt < minutes * 60_000L) return false
        refresh(providerId)
        return true
    }

    /** Add-on, তার provider সারি ও চ্যানেল মুছে ফেলে। */
    suspend fun remove(providerId: Long) {
        addons.getByProviderId(providerId)?.let {
            if (it.origin == "CATALOG") setOptOut(it.addonId, true) // ইউজার মুছলে অটো-ইনস্টল আর ফেরত আনবে না
            addons.deleteById(it.id)
        }
        providers.deleteById(providerId)
        channels.clearProviderData(providerId)
    }

    private fun manifestToJson(m: AddonManifest): String = JSONObject()
        .put("id", m.id).put("name", m.name).put("version", m.version)
        .put("description", m.description).put("author", m.author)
        .put("domains", org.json.JSONArray(m.domains))
        .put("allowLocal", m.allowLocal).put("apiVersion", m.apiVersion)
        .put("refreshMinutes", m.refreshMinutes).put("keepGroups", m.keepGroups)
        .put("sources", m.sources).put("catalog", m.catalog).put("web", m.web).put("domainSettings", org.json.JSONArray(m.domainSettings))
        .put(
            "settings",
            org.json.JSONArray().also { arr ->
                for (s in m.settings) {
                    arr.put(
                        JSONObject().put("key", s.key).put("label", s.label).put("type", s.type).put("default", s.default)
                            .put(
                                "options",
                                org.json.JSONArray().also { oa ->
                                    for (o in s.options) oa.put(JSONObject().put("value", o.value).put("label", o.label))
                                },
                            ),
                    )
                }
            },
        )
        .toString()

    fun jsonToMap(json: String): Map<String, String> = try {
        val o = JSONObject(json)
        val out = HashMap<String, String>()
        for (k in o.keys()) out[k] = o.optString(k)
        out
    } catch (e: Exception) {
        emptyMap()
    }
}
