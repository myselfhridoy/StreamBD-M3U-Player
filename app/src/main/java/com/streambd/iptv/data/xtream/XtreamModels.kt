package com.streambd.iptv.data.xtream

import com.streambd.iptv.data.m3u.ParsedChannel

/**
 * Xtream Codes API-এর জন্য ছোট, immutable মডেল।
 * এগুলো org.json থেকে parse করা হয়; UI-তে দেখানোর দরকার নেই।
 */

data class XtreamCredentials(
    val baseUrl: String,
    val username: String,
    val password: String,
    val userAgent: String? = null,
)

data class XtreamUserInfo(
    val username: String? = null,
    val auth: Int = 0,
    val status: String? = null,
    val expDate: Long? = null,
    val isTrial: Boolean = false,
    val activeConnections: Int? = null,
    val maxConnections: Int? = null,
)

data class XtreamServerInfo(
    val url: String? = null,
    val port: String? = null,
    val httpsPort: String? = null,
    val protocol: String? = null,
    val timezone: String? = null,
    val timestampNow: Long? = null,
)

data class XtreamAuth(
    val user: XtreamUserInfo? = null,
    val server: XtreamServerInfo? = null,
)

data class XtreamCategory(
    val id: String,
    val name: String,
    val parentId: Int? = null,
)

data class XtreamLiveStream(
    val num: Int?,
    val name: String,
    val streamId: Int,
    val icon: String?,
    val epgChannelId: String?,
    val categoryId: String?,
    val tvArchive: Boolean,
    val tvArchiveDuration: Int,
    val isAdult: Boolean,
)

data class XtreamVodStream(
    val num: Int?,
    val name: String,
    val streamId: Int,
    val icon: String?,
    val categoryId: String?,
    val containerExtension: String?,
    val rating: String?,
    val isAdult: Boolean,
)

/**
 * এক sync-এ যা যা দরকার:
 * - চ্যানেল (Live + Movie)
 * - EPG URL
 * - কতগুলো live/movie পাওয়া গেল
 */
data class XtreamCatalog(
    val channels: List<ParsedChannel>,
    val epgUrl: String,
    val liveCount: Int,
    val movieCount: Int,
    val auth: XtreamAuth,
)

class XtreamException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
