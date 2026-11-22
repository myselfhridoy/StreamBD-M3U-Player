package com.streambd.iptv.data.addon

import com.streambd.iptv.data.db.AddonDao
import com.streambd.iptv.data.db.AddonEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/** TMDB টাইটেল, যার সোর্স খোঁজা হচ্ছে। সিরিজের ক্ষেত্রে [season]/[episode] দেওয়া থাকে। */
data class TitleQuery(
    val tmdbId: Long,
    val imdbId: String,
    /** "movie" বা "tv" */
    val type: String,
    val title: String,
    val originalTitle: String,
    val year: String,
    val season: Int? = null,
    val episode: Int? = null,
)

/** একটা সোর্স: [streamUrl] সরাসরি প্লেয়ারকে দেওয়া যায় (resolve:true হলে `addon-resolve://...`)। */
data class TitleSource(
    val addonName: String,
    val label: String,
    val quality: String?,
    val subtitle: String?,
    val streamUrl: String,
)

/** এক add-on-এর ফলাফল (সব সফল বা [error])। */
data class SourceBatch(val addonName: String, val sources: List<TitleSource>, val error: String? = null)

/**
 * চালু থাকা সব `sources: true` add-on-এর `findSources(info)` একসাথে চালিয়ে ফলাফল একটা একটা করে পাঠায়
 * (দ্রুতটা আগে দেখা যায়)। Add-on TMDB চেনে না; শুধু [TitleQuery]-র তথ্য পায়, কোনো টোকেন নয়।
 */
@Singleton
class SourceFinder @Inject constructor(
    private val addons: AddonDao,
    private val engine: AddonEngine,
) {
    /** কয়টা add-on খুঁজতে পারে (UI-তে "Searching N add-ons…")। */
    suspend fun capableAddons(): List<AddonEntity> = addons.getEnabled().filter {
        runCatching { AddonManifest.parse(it.manifest).sources }.getOrDefault(false) ||
            // পুরোনো ইনস্টলে ম্যানিফেস্ট ইনস্টলের সময়ের; স্ক্রিপ্টে findSources থাকলেই চলবে
            it.script.contains("findSources")
    }

    fun find(query: TitleQuery, list: List<AddonEntity>): Flow<SourceBatch> = channelFlow {
        // এক-এক করে চলে (একসাথে চালালে কিছু add-on "returned nothing" দিচ্ছিল); প্রতিটার ফল শেষ হওয়া মাত্র পাঠানো হয়
        launch {
            for (a in list) {
                var batch = attempt(a, query)
                // JS ইঞ্জিন কখনো ফল না দিয়ে শেষ হয়ে যায়: একবার আবার চেষ্টা
                if (batch.error?.contains("returned nothing") == true) batch = attempt(a, query)
                send(batch)
            }
        }
    }

    private suspend fun attempt(a: AddonEntity, query: TitleQuery): SourceBatch = try {
        SourceBatch(a.name, runOne(a, query))
    } catch (e: CancellationException) {
        throw e
    } catch (e: AddonException) {
        SourceBatch(a.name, emptyList(), e.message)
    } catch (e: Exception) {
        SourceBatch(a.name, emptyList(), e.message ?: "failed")
    } catch (e: OutOfMemoryError) {
        SourceBatch(a.name, emptyList(), "not enough memory")
    }

    private suspend fun runOne(a: AddonEntity, q: TitleQuery): List<TitleSource> {
        val manifest = AddonManifest.parse(a.manifest)
        val info = JSONObject()
            .put("mode", "sources")
            .put("tmdbId", q.tmdbId).put("imdbId", q.imdbId)
            .put("type", q.type).put("title", q.title).put("originalTitle", q.originalTitle).put("year", q.year)
            .apply {
                q.season?.let { put("season", it) }
                q.episode?.let { put("episode", it) }
            }.toString()
        val storageIn = toMap(a.storage)
        val r = engine.resolve(a.script, manifest, toMap(a.settings), storageIn, info)
        if (r.storage != storageIn) {
            val o = JSONObject()
            for ((k, v) in r.storage) o.put(k, v)
            runCatching { addons.setStorage(a.id, o.toString()) }
        }
        val body = r.m3u
        if (r.error != null || body == null) throw AddonException(r.error ?: "no answer")
        val arr = JSONObject(body).optJSONArray("sources") ?: return emptyList()
        val out = ArrayList<TitleSource>(arr.length())
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val url = AddonResolver.streamUrlFor(a.addonId, e) ?: continue
            out += TitleSource(
                addonName = a.name,
                label = e.optString("name").ifBlank { "Source ${i + 1}" },
                quality = e.optString("quality").takeIf { it.isNotBlank() },
                subtitle = e.optString("subtitle").takeIf { it.isNotBlank() },
                streamUrl = url,
            )
        }
        return out
    }

    private fun toMap(json: String): Map<String, String> = try {
        val o = JSONObject(json.ifBlank { "{}" })
        buildMap { for (k in o.keys()) put(k, o.optString(k)) }
    } catch (e: Exception) { emptyMap() }
}
