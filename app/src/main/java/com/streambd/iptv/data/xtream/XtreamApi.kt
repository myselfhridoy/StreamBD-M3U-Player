package com.streambd.iptv.data.xtream

import com.streambd.iptv.Brand
import com.streambd.iptv.data.m3u.ChannelKind
import com.streambd.iptv.data.m3u.ParsedChannel
import com.streambd.iptv.data.vod.SeriesGrouping
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/**
 * Xtream Codes / XUI panel-এর জন্য minimal client।
 *
 * সমর্থিত:
 *  - player_api.php দিয়ে auth
 *  - Live categories + streams
 *  - VOD categories + streams
 *  - xmltv.php EPG URL
 *
 * Series: sync-এ শুধু সিরিজের তালিকা (get_series) আসে, এপিসোড নয়। সিরিজ খোলার সময়
 * [seriesEpisodes] (get_series_info) একটিমাত্র অনুরোধে সেই সিরিজের এপিসোড আনে (lazy)।
 */
@Singleton
class XtreamApi @Inject constructor(baseClient: OkHttpClient) {

    private val client = baseClient.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    companion object {

        /** সিরিজের স্থানধারক সারির URL: `xtream-series://<providerId>/<seriesId>`। প্লেয়ারে কখনো যায় না। */
        const val SERIES_SCHEME = "xtream-series://"

        fun seriesRef(providerId: Long, seriesId: Int): String = "$SERIES_SCHEME$providerId/$seriesId"

        /** @return providerId to seriesId, বা null */
        fun parseSeriesRef(ref: String): Pair<Long, Int>? {
            if (!ref.startsWith(SERIES_SCHEME)) return null
            val parts = ref.removePrefix(SERIES_SCHEME).substringBefore('|').split('/')
            val p = parts.getOrNull(0)?.toLongOrNull() ?: return null
            val s = parts.getOrNull(1)?.toIntOrNull() ?: return null
            return p to s
        }

        /**
         * ইউজার যেভাবেই লিখুক (`host:8080`, `http://host:8080/player_api.php`,
         * `https://host/iptv/`) — শুধু base URL বানায়।
         */
        fun normalizeBaseUrl(input: String): String? {
            var text = input.trim()
            if (text.isEmpty()) return null
            if (!text.startsWith("http://", ignoreCase = true) &&
                !text.startsWith("https://", ignoreCase = true)
            ) {
                text = "http://$text"
            }
            val url = text.toHttpUrlOrNull() ?: return null

            var path = url.encodedPath.trimEnd('/')
            for (suffix in listOf("/player_api.php", "/get.php", "/xmltv.php")) {
                if (path.endsWith(suffix, ignoreCase = true)) {
                    path = path.dropLast(suffix.length)
                    break
                }
            }
            if (path.isBlank()) path = "/"
            if (!path.startsWith("/")) path = "/$path"

            val clean = url.newBuilder()
                .encodedPath(path)
                .query(null)
                .fragment(null)
                .build()
                .toString()
                .trimEnd('/')

            return clean.ifBlank { null }
        }

        private val CONTAINER_EXT = Regex("[A-Za-z0-9]{1,8}")
    }

    // ───────────────────────── public API ─────────────────────────

    suspend fun authenticate(credentials: XtreamCredentials): XtreamAuth {
        val body = request(apiUrl(credentials), credentials.userAgent)
        val json = try {
            JSONObject(body)
        } catch (e: Exception) {
            throw XtreamException("This server did not return valid Xtream JSON.", e)
        }

        val userObj = json.optJSONObject("user_info")
            ?: throw XtreamException("This doesn't look like an Xtream server. Check the address.")
        if (userObj.length() == 0) {
            throw XtreamException("Invalid Xtream username or password.")
        }

        val auth = userObj.intOrNull("auth") ?: 1
        if (userObj.has("auth") && auth != 1) {
            throw XtreamException("Invalid or expired Xtream account.")
        }

        return XtreamAuth(
            user = XtreamUserInfo(
                username = userObj.text("username"),
                auth = auth,
                status = userObj.text("status"),
                expDate = userObj.longOrNull("exp_date"),
                isTrial = userObj.bool("is_trial"),
                activeConnections = userObj.intOrNull("active_cons"),
                maxConnections = userObj.intOrNull("max_connections"),
            ),
            server = json.optJSONObject("server_info")?.let { s ->
                XtreamServerInfo(
                    url = s.text("url"),
                    port = s.text("port"),
                    httpsPort = s.text("https_port"),
                    protocol = s.text("server_protocol"),
                    timezone = s.text("timezone"),
                    timestampNow = s.longOrNull("timestamp_now"),
                )
            },
        )
    }

    /**
     * Live + Movie একসাথে নামায়। চারটা অনুরোধ একসাথে (parallel) চলে, তাই আগের চেয়ে অনেক দ্রুত।
     * কোনো একটি অংশ fail করলেও অন্যটা থাকলে catalog ফেরে; দুটোই খালি হলে প্রথম error-এর message ছোড়ে।
     *
     * @param knownAuth আগেই authenticate করা থাকলে দিন, দ্বিতীয়বার অনুরোধ যাবে না।
     */
    suspend fun loadCatalog(
        credentials: XtreamCredentials,
        knownAuth: XtreamAuth? = null,
        providerId: Long = 0L,
    ): XtreamCatalog = coroutineScope {
        val auth = knownAuth ?: authenticate(credentials)

        suspend fun <T> safe(block: suspend () -> List<T>): Pair<List<T>, Exception?> = try {
            block() to null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList<T>() to e
        }

        val liveCatsD = async { safe { categories(credentials, "get_live_categories") } }
        val liveD = async { safe { liveStreams(credentials) } }
        val vodCatsD = async { safe { categories(credentials, "get_vod_categories") } }
        val vodD = async { safe { vodStreams(credentials) } }
        // সিরিজ ঐচ্ছিক: panel সমর্থন না করলে/ব্যর্থ হলে বাকি catalog আটকাবে না
        val serCatsD = async { safe { categories(credentials, "get_series_categories") } }
        val serD = async { safe { seriesList(credentials) } }

        val (liveCats, e1) = liveCatsD.await()
        val (live, e2) = liveD.await()
        val (vodCats, e3) = vodCatsD.await()
        val (vod, e4) = vodD.await()
        val (serCats, _) = serCatsD.await()
        val (series, _) = serD.await()
        val firstError = e1 ?: e2 ?: e3 ?: e4

        if (live.isEmpty() && vod.isEmpty() && series.isEmpty()) {
            throw XtreamException(
                firstError?.message ?: "No live, movie or series items found in this Xtream account.",
                firstError,
            )
        }

        XtreamCatalog(
            channels = buildChannels(credentials, live, liveCats, vod, vodCats, series, serCats, providerId),
            epgUrl = xmltvUrl(credentials),
            liveCount = live.size,
            movieCount = vod.size,
            seriesCount = series.size,
            auth = auth,
        )
    }

    fun xmltvUrl(credentials: XtreamCredentials): String {
        val base = credentials.baseUrl.toHttpUrlOrNull()
            ?: throw XtreamException("Invalid Xtream server address.")
        return base.newBuilder()
            .addPathSegment("xmltv.php")
            .addQueryParameter("username", credentials.username)
            .addQueryParameter("password", credentials.password)
            .build()
            .toString()
    }

    fun liveUrl(
        credentials: XtreamCredentials,
        streamId: Int,
        extension: String = "m3u8",
    ): String = streamUrl(credentials, "live", streamId, extension)

    fun vodUrl(
        credentials: XtreamCredentials,
        streamId: Int,
        extension: String?,
    ): String = streamUrl(credentials, "movie", streamId, safeExtension(extension))

    fun seriesUrl(
        credentials: XtreamCredentials,
        episodeId: String,
        extension: String?,
    ): String = streamUrl(credentials, "series", episodeId, safeExtension(extension))

    // ───────────────────────── network ─────────────────────────

    private fun apiUrl(
        credentials: XtreamCredentials,
        action: String? = null,
    ): HttpUrl {
        val base = credentials.baseUrl.toHttpUrlOrNull()
            ?: throw XtreamException("Invalid Xtream server address.")
        val builder = base.newBuilder()
            .addPathSegment("player_api.php")
            .addQueryParameter("username", credentials.username)
            .addQueryParameter("password", credentials.password)
        if (action != null) builder.addQueryParameter("action", action)
        return builder.build()
    }

    private fun streamUrl(
        credentials: XtreamCredentials,
        kind: String,
        itemId: Any,
        extension: String,
    ): String {
        val base = credentials.baseUrl.toHttpUrlOrNull()
            ?: throw XtreamException("Invalid Xtream server address.")
        return base.newBuilder()
            .addPathSegment(kind)
            .addPathSegment(credentials.username)
            .addPathSegment(credentials.password)
            .addPathSegment("$itemId.$extension")
            .build()
            .toString()
    }

    private suspend fun categories(
        credentials: XtreamCredentials,
        action: String,
    ): List<XtreamCategory> {
        val body = request(apiUrl(credentials, action), credentials.userAgent)
        val array = try {
            JSONArray(body)
        } catch (e: Exception) {
            throw XtreamException("Unexpected category response from the Xtream server.", e)
        }
        return buildList(array.length()) {
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val id = o.text("category_id") ?: continue
                add(
                    XtreamCategory(
                        id = id,
                        name = o.text("category_name") ?: "Category",
                        parentId = o.intOrNull("parent_id"),
                    ),
                )
            }
        }
    }

    private suspend fun liveStreams(credentials: XtreamCredentials): List<XtreamLiveStream> {
        val body = request(apiUrl(credentials, "get_live_streams"), credentials.userAgent)
        val array = try {
            JSONArray(body)
        } catch (e: Exception) {
            throw XtreamException("Unexpected live-stream response from the Xtream server.", e)
        }
        return buildList(array.length()) {
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val id = o.intOrNull("stream_id") ?: continue
                add(
                    XtreamLiveStream(
                        num = o.intOrNull("num"),
                        name = o.text("name") ?: "Channel $id",
                        streamId = id,
                        icon = o.text("stream_icon"),
                        epgChannelId = o.text("epg_channel_id"),
                        categoryId = o.text("category_id"),
                        tvArchive = o.bool("tv_archive"),
                        tvArchiveDuration = o.intOrNull("tv_archive_duration") ?: 0,
                        isAdult = o.bool("is_adult"),
                    ),
                )
            }
        }
    }

    private suspend fun vodStreams(credentials: XtreamCredentials): List<XtreamVodStream> {
        val body = request(apiUrl(credentials, "get_vod_streams"), credentials.userAgent)
        val array = try {
            JSONArray(body)
        } catch (e: Exception) {
            throw XtreamException("Unexpected movie response from the Xtream server.", e)
        }
        return buildList(array.length()) {
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val id = o.intOrNull("stream_id") ?: continue
                add(
                    XtreamVodStream(
                        num = o.intOrNull("num"),
                        name = o.text("name") ?: "Movie $id",
                        streamId = id,
                        icon = o.text("stream_icon"),
                        categoryId = o.text("category_id"),
                        containerExtension = o.text("container_extension"),
                        rating = o.text("rating"),
                        isAdult = o.bool("is_adult"),
                    ),
                )
            }
        }
    }

    private suspend fun seriesList(credentials: XtreamCredentials): List<XtreamSeries> =
        XtreamParsers.series(request(apiUrl(credentials, "get_series"), credentials.userAgent))

    /** একটি সিরিজের সব এপিসোড (get_series_info, এক অনুরোধ)। পার্সিং: [XtreamParsers.episodes]। */
    suspend fun seriesEpisodes(credentials: XtreamCredentials, seriesId: Int): List<XtreamEpisode> {
        val url = apiUrl(credentials, "get_series_info").newBuilder()
            .addQueryParameter("series_id", seriesId.toString()).build()
        return XtreamParsers.episodes(request(url, credentials.userAgent))
    }

    private suspend fun request(url: HttpUrl, userAgent: String?): String =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .header(
                    "User-Agent",
                    userAgent?.takeIf { it.isNotBlank() } ?: Brand.USER_AGENT,
                )
                .header("Accept", "application/json")
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        throw XtreamException("Xtream server returned HTTP ${response.code}.")
                    }
                    if (body.isBlank()) {
                        throw XtreamException("Xtream server returned an empty response.")
                    }
                    body.trimStart('\uFEFF')
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: XtreamException) {
                throw e
            } catch (e: UnknownHostException) {
                throw XtreamException("Can't reach the Xtream server. Check the address.", e)
            } catch (e: SocketTimeoutException) {
                throw XtreamException("The Xtream server took too long to respond.", e)
            } catch (e: IOException) {
                throw XtreamException(
                    "Network error: ${e.message ?: "please try again"}.",
                    e,
                )
            }
        }

    // ───────────────────────── mapping ─────────────────────────

    private fun buildChannels(
        credentials: XtreamCredentials,
        live: List<XtreamLiveStream>,
        liveCats: List<XtreamCategory>,
        vod: List<XtreamVodStream>,
        vodCats: List<XtreamCategory>,
        series: List<XtreamSeries> = emptyList(),
        seriesCats: List<XtreamCategory> = emptyList(),
        providerId: Long = 0L,
    ): List<ParsedChannel> {
        val liveCategories = liveCats.associateBy { it.id }
        val vodCategories = vodCats.associateBy { it.id }
        val out = ArrayList<ParsedChannel>(live.size + vod.size + max(0, live.size / 2))

        for (stream in live) {
            val group = liveCategories[stream.categoryId]?.name?.takeIf { it.isNotBlank() } ?: "Live"
            val primary = liveUrl(credentials, stream.streamId, "m3u8")
            val backup = liveUrl(credentials, stream.streamId, "ts")

            val channel = ParsedChannel(
                name = stream.name,
                logoUrl = stream.icon,
                groupTitle = group,
                tvgId = stream.epgChannelId,
                streamUrl = primary,
                kind = ChannelKind.LIVE,
                catchupEnable = stream.tvArchive,
                catchupDays = stream.tvArchiveDuration.coerceAtLeast(0),
                adult = stream.isAdult,
                chno = stream.num?.takeIf { it in 1..9999 },
            )
            out += channel

            // কিছু panel .m3u8 দেয় না, শুধু .ts দেয়। একই নাম/গ্রুপের দ্বিতীয় সোর্স
            // ChannelRepository-তে backup source হিসেবে merge হবে।
            if (backup != primary) out += channel.copy(streamUrl = backup)
        }

        for (stream in vod) {
            val group = vodCategories[stream.categoryId]?.name?.takeIf { it.isNotBlank() } ?: "Movies"
            out += ParsedChannel(
                name = stream.name,
                logoUrl = stream.icon,
                groupTitle = group,
                tvgId = null,
                streamUrl = vodUrl(credentials, stream.streamId, stream.containerExtension),
                kind = ChannelKind.MOVIE,
                adult = stream.isAdult,
                chno = stream.num?.takeIf { it in 1..9999 },
            )
        }

        // সিরিজ: স্থানধারক সারি (serieId = "@lazy"); এপিসোড খোলার সময় get_series_info থেকে আসে
        val seriesCategories = seriesCats.associateBy { it.id }
        for (s in series) {
            val group = seriesCategories[s.categoryId]?.name?.takeIf { it.isNotBlank() } ?: "Series"
            out += ParsedChannel(
                name = s.name,
                logoUrl = s.cover,
                groupTitle = group,
                tvgId = null,
                streamUrl = seriesRef(providerId, s.seriesId),
                kind = ChannelKind.SERIES,
                serieId = SeriesGrouping.LAZY_MARK,
                serieTitle = s.name,
                subtitle = listOfNotNull(s.year, s.rating?.let { "★ $it" }).joinToString(" · ").ifBlank { null },
            )
        }

        return out
    }

    private fun safeExtension(raw: String?): String {
        val value = raw?.trim()?.trimStart('.')?.lowercase().orEmpty()
        return if (value.matches(CONTAINER_EXT)) value else "mp4"
    }
}

// ───────────────────────── org.json helpers ─────────────────────────

private fun JSONObject.longOrNull(key: String): Long? {
    if (!has(key) || isNull(key)) return null
    return when (val value = opt(key)) {
        is Number -> value.toLong()
        is String -> value.trim().toLongOrNull()
        else -> null
    }
}

private fun JSONObject.bool(key: String): Boolean {
    if (!has(key) || isNull(key)) return false
    return when (val value = opt(key)) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        is String -> value == "1" || value.equals("true", ignoreCase = true)
        else -> false
    }
}


