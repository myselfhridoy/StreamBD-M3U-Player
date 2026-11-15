package com.streambd.iptv.data.addon

import com.streambd.iptv.data.db.AddonDao
import com.streambd.iptv.data.db.AddonEntity
import com.streambd.iptv.data.db.ProviderDao
import com.streambd.iptv.data.db.ProviderEntity
import com.streambd.iptv.data.parser.PlaylistParserFactory
import com.streambd.iptv.data.repo.ChannelRepository
import com.streambd.iptv.data.repo.PlaylistSync
import com.streambd.iptv.data.repo.SyncException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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

@Singleton
class AddonRepository @Inject constructor(
    private val addons: AddonDao,
    private val providers: ProviderDao,
    private val engine: AddonEngine,
    private val channels: ChannelRepository,
    private val sync: PlaylistSync,
    private val http: OkHttpClient,
) {
    private companion object {
        const val MAX_DOWNLOAD_CHARS = 1_000_000
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
            addons.update(a.copy(enabled = false, lastStatus = "Disabled by StreamBD"))
            n++
        }
        return n
    }

    suspend fun saveSettings(addon: AddonEntity, values: Map<String, String>) {
        val o = JSONObject()
        for ((k, v) in values) o.put(k, v)
        addons.setSettings(addon.id, o.toString())
    }

    /** স্ক্রিপ্ট চালিয়ে চ্যানেল ইমপোর্ট। সফল হলে সারাংশ ফেরত, ব্যর্থ হলে [AddonException]। */
    suspend fun refresh(providerId: Long): String {
        val addon = addons.getByProviderId(providerId) ?: throw AddonException("Add-on not found.")
        if (!addon.enabled) throw AddonException("This add-on is turned off.")
        val manifest = AddonManifest.parse(addon.manifest)

        val result = engine.run(addon.script, manifest, jsonToMap(addon.settings), jsonToMap(addon.storage))
        val logText = result.log.joinToString("\n").take(8_000)
        val storageJson = JSONObject().also { o -> for ((k, v) in result.storage) o.put(k, v) }.toString()
        val now = System.currentTimeMillis()

        val body = result.m3u
        if (result.error != null || body == null) {
            addons.setRunResult(addon.id, storageJson, now, "Error: ${result.error}", logText)
            throw AddonException(result.error ?: "The add-on failed.")
        }

        val parsed = PlaylistParserFactory.parse(body)
        val list = parsed.channels.filter { !it.isNestedPlaylist }
        if (list.isEmpty()) {
            addons.setRunResult(addon.id, storageJson, now, "Error: no channels returned", logText)
            throw AddonException("The add-on returned no channels.")
        }

        val count = try {
            channels.import(list, isPremium = false, providerId = providerId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            addons.setRunResult(addon.id, storageJson, now, "Error: could not save channels", logText)
            throw AddonException("Could not save the channels on this device.", e)
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
        addons.setRunResult(addon.id, storageJson, now, "OK: $count channels", logText)
        return summary
    }

    /**
     * অটো-রিফ্রেশের জন্য: manifest.refreshMinutes পার হলে (ব্যর্থ হলে ৩০ মিনিট পর আবার) রিফ্রেশ করে।
     * @return রিফ্রেশ চালানো হয়ে থাকলে true; ব্যর্থ হলে [AddonException] ছোড়ে।
     */
    suspend fun refreshIfDue(providerId: Long): Boolean {
        val a = addons.getByProviderId(providerId) ?: return false
        if (!a.enabled) return false
        val m = runCatching { AddonManifest.parse(a.manifest) }.getOrNull() ?: return false
        if (m.refreshMinutes <= 0) return false
        val failed = a.lastStatus?.startsWith("Error") == true
        val minutes = if (failed) minOf(m.refreshMinutes, 30) else m.refreshMinutes
        if (System.currentTimeMillis() - a.lastRunAt < minutes * 60_000L) return false
        refresh(providerId)
        return true
    }

    /** Add-on, তার provider সারি ও চ্যানেল মুছে ফেলে। */
    suspend fun remove(providerId: Long) {
        addons.getByProviderId(providerId)?.let { addons.deleteById(it.id) }
        providers.deleteById(providerId)
        channels.clearProviderData(providerId)
    }

    private fun manifestToJson(m: AddonManifest): String = JSONObject()
        .put("id", m.id).put("name", m.name).put("version", m.version)
        .put("description", m.description).put("author", m.author)
        .put("domains", org.json.JSONArray(m.domains))
        .put("allowLocal", m.allowLocal).put("apiVersion", m.apiVersion)
        .put("refreshMinutes", m.refreshMinutes)
        .put(
            "settings",
            org.json.JSONArray().also { arr ->
                for (s in m.settings) {
                    arr.put(JSONObject().put("key", s.key).put("label", s.label).put("type", s.type).put("default", s.default))
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
