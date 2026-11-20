package com.streambd.iptv.data.tmdb

import android.util.Base64
import android.util.LruCache
import com.streambd.iptv.BuildConfig
import com.streambd.iptv.data.activation.ActivationRepository
import com.streambd.iptv.data.activation.ActivationState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

class TmdbException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** সার্চ/ট্রেন্ডিং-এর একটা সারি। [type] = "movie" | "tv"। */
data class TmdbItem(
    val id: Long,
    val type: String,
    val title: String,
    val originalTitle: String,
    val year: String,
    val poster: String,
    val overview: String,
    val rating: Double,
)

data class TmdbSeasonInfo(val n: Int, val name: String, val episodes: Int, val year: String)

data class TmdbDetail(
    val item: TmdbItem,
    val backdrop: String,
    val tagline: String,
    val runtime: Int,
    val genres: List<String>,
    val imdbId: String,
    val seasons: List<TmdbSeasonInfo>,
)

data class TmdbEpisode(val n: Int, val name: String, val overview: String, val still: String, val airDate: String, val runtime: Int)

object TmdbImages {
    private const val BASE = "https://image.tmdb.org/t/p/"
    fun poster(path: String): String? = path.takeIf { it.isNotBlank() }?.let { BASE + "w342" + it }
    fun backdrop(path: String): String? = path.takeIf { it.isNotBlank() }?.let { BASE + "w780" + it }
    fun still(path: String): String? = path.takeIf { it.isNotBlank() }?.let { BASE + "w300" + it }
}

/**
 * TMDB শুধু আমাদের Worker-এর `/tmdb` দিয়ে (টোকেন Worker-এ; অ্যাপে কোনো TMDB কী নেই)।
 * অ্যাক্টিভেশন কোড + ডিভাইস আইডি লাগে, তাই সক্রিয় ব্যবহারকারী ছাড়া কাজ করে না। উত্তর মেমরিতে ক্যাশ হয়।
 * ছবি image.tmdb.org থেকে সরাসরি (কী লাগে না)।
 */
@Singleton
class TmdbRepository @Inject constructor(
    private val http: OkHttpClient,
    private val activation: ActivationRepository,
) {
    private val json = "application/json; charset=utf-8".toMediaType()
    private val cache = LruCache<String, Pair<Long, Any>>(80)

    private fun baseUrl(): String = try {
        String(Base64.decode(BuildConfig.ENCODED_API, Base64.DEFAULT), Charsets.UTF_8).trimEnd('/')
    } catch (e: Exception) { "" }

    private suspend fun call(body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val active = activation.current() as? ActivationState.Active
            ?: throw TmdbException("Activate the app to search movies and series.")
        if (active.code == ActivationRepository.LOCAL_CODE || active.code == "DEV") {
            throw TmdbException("Movie search is not available in local mode.")
        }
        val base = baseUrl()
        if (base.isBlank()) throw TmdbException("Server is not configured.")
        body.put("code", active.code).put("deviceId", activation.deviceId()).put("lang", "en-US")
        try {
            val req = Request.Builder().url("$base/tmdb").post(body.toString().toRequestBody(json)).build()
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val o = try { JSONObject(text) } catch (e: Exception) { JSONObject() }
                if (!resp.isSuccessful || !o.optBoolean("ok", false)) {
                    throw TmdbException(o.optString("error").ifBlank { "Search error (HTTP ${resp.code})" })
                }
                o
            }
        } catch (e: IOException) {
            throw TmdbException("Could not reach the server: ${e.message ?: "network error"}", e)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T : Any> cached(key: String, ttlMs: Long, load: suspend () -> T): T {
        val now = System.currentTimeMillis()
        cache.get(key)?.let { (at, v) -> if (now - at < ttlMs) return v as T }
        return load().also { cache.put(key, now to it) }
    }

    private fun item(o: JSONObject) = TmdbItem(
        id = o.optLong("id"), type = o.optString("type"), title = o.optString("title"),
        originalTitle = o.optString("originalTitle"), year = o.optString("year"), poster = o.optString("poster"),
        overview = o.optString("overview"), rating = o.optDouble("rating", 0.0),
    )

    private fun items(a: JSONArray?): List<TmdbItem> =
        buildList { if (a != null) for (i in 0 until a.length()) a.optJSONObject(i)?.let { add(item(it)) } }

    suspend fun search(query: String): List<TmdbItem> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        return cached("s:${q.lowercase()}", 10 * 60_000L) {
            items(call(JSONObject().put("action", "search").put("q", q)).optJSONArray("results"))
        }
    }

    suspend fun trending(): List<TmdbItem> = cached("trending", 30 * 60_000L) {
        items(call(JSONObject().put("action", "trending")).optJSONArray("results"))
    }

    suspend fun detail(type: String, id: Long): TmdbDetail = cached("d:$type:$id", 60 * 60_000L) {
        val o = call(JSONObject().put("action", if (type == "tv") "tv" else "movie").put("id", id))
        val seasons = o.optJSONArray("seasons")
        TmdbDetail(
            item = item(o),
            backdrop = o.optString("backdrop"),
            tagline = o.optString("tagline"),
            runtime = o.optInt("runtime"),
            genres = buildList { o.optJSONArray("genres")?.let { g -> for (i in 0 until g.length()) add(g.optString(i)) } },
            imdbId = o.optString("imdbId"),
            seasons = buildList {
                if (seasons != null) for (i in 0 until seasons.length()) seasons.optJSONObject(i)?.let {
                    add(TmdbSeasonInfo(it.optInt("n"), it.optString("name"), it.optInt("episodes"), it.optString("year")))
                }
            },
        )
    }

    suspend fun season(tvId: Long, season: Int): List<TmdbEpisode> = cached("e:$tvId:$season", 60 * 60_000L) {
        val o = call(JSONObject().put("action", "season").put("id", tvId).put("season", season))
        val a = o.optJSONArray("episodes")
        buildList {
            if (a != null) for (i in 0 until a.length()) a.optJSONObject(i)?.let {
                add(TmdbEpisode(it.optInt("n"), it.optString("name"), it.optString("overview"), it.optString("still"), it.optString("airDate"), it.optInt("runtime")))
            }
        }
    }
}
