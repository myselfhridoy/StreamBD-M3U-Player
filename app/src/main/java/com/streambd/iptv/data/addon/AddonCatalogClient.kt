package com.streambd.iptv.data.addon

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

data class CatalogEntry(
    val id: String,
    val name: String,
    val description: String,
    val author: String,
    val version: String,
    val apiVersion: Int,
    val minAppVersion: Int,
    val sha256: String,
    val signature: String,
    val size: Int,
    val changelog: String,
    val updatedAt: Long,
)

/** `blocked` = কিল-সুইচে বন্ধ করা আইডি। */
data class CatalogList(val entries: List<CatalogEntry>, val blocked: Set<String>)

data class CatalogAddon(val entry: CatalogEntry, val script: String)

/** Worker-এর `/addons` ও `/addons/get`। অ্যাক্টিভেশন কোড + ডিভাইস আইডি লাগে (সক্রিয় ব্যবহারকারী ছাড়া ক্যাটালগ খোলে না)। */
@Singleton
class AddonCatalogClient @Inject constructor(private val http: OkHttpClient) {
    private val json = "application/json; charset=utf-8".toMediaType()

    suspend fun list(baseUrl: String, code: String, deviceId: String): CatalogList {
        val o = post("$baseUrl/addons", JSONObject().put("code", code).put("deviceId", deviceId))
        val arr = o.optJSONArray("addons")
        val list = ArrayList<CatalogEntry>()
        if (arr != null) for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { list += entry(it) }
        val blocked = HashSet<String>()
        o.optJSONArray("blocked")?.let { b -> for (i in 0 until b.length()) blocked += b.optString(i) }
        return CatalogList(list, blocked)
    }

    suspend fun get(baseUrl: String, code: String, deviceId: String, id: String): CatalogAddon {
        val o = post("$baseUrl/addons/get", JSONObject().put("code", code).put("deviceId", deviceId).put("id", id))
        val script = o.optString("script")
        if (script.isBlank()) throw AddonException("The server returned an empty add-on.")
        return CatalogAddon(entry(o), script)
    }

    private fun entry(o: JSONObject) = CatalogEntry(
        id = o.optString("id"), name = o.optString("name"), description = o.optString("description"),
        author = o.optString("author"), version = o.optString("version"), apiVersion = o.optInt("apiVersion", 1),
        minAppVersion = o.optInt("minAppVersion", 0), sha256 = o.optString("sha256"),
        signature = o.optString("signature"), size = o.optInt("size", 0),
        changelog = o.optString("changelog"), updatedAt = o.optLong("updatedAt", 0L),
    )

    private suspend fun post(url: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(url).post(body.toString().toRequestBody(json)).build()
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val o = try { JSONObject(text) } catch (e: Exception) { JSONObject() }
                if (!resp.isSuccessful || !o.optBoolean("ok", false)) {
                    throw AddonException(o.optString("error").ifBlank { "Catalog error (HTTP ${resp.code})" })
                }
                o
            }
        } catch (e: IOException) {
            throw AddonException("Could not reach the catalog: ${e.message ?: "network error"}", e)
        }
    }
}
