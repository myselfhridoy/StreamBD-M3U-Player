package com.streambd.iptv.data.epg

import com.streambd.iptv.data.db.EpgDao
import com.streambd.iptv.data.db.ProgramEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

data class NowNext(
    val current: ProgramEntity?,
    val next: ProgramEntity?,
)

@Singleton
class EpgRepository @Inject constructor(
    private val dao: EpgDao,
    private val http: OkHttpClient,
) {
    fun observeWindow(tvgIds: List<String>, from: Long, to: Long): Flow<List<ProgramEntity>> {
        val distinctIds = tvgIds.distinct()
        if (distinctIds.isEmpty()) return flowOf(emptyList())

        val windows = distinctIds.chunked(500).map { dao.observeWindow(it, from, to) }
        return combine(windows) { results -> results.flatMap { it } }
    }

    suspend fun hasContent(): Boolean = dao.count() > 0
    suspend fun clear() = dao.clear()

    suspend fun importFromUrl(url: String): Int = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val from = now - 6L * 3600 * 1000
        val to = now + 48L * 3600 * 1000
        val programs = ArrayList<ProgramEntity>(8192)

        val request = Request.Builder().url(url).build()
        http.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code} for EPG feed" }
            val body = checkNotNull(response.body) { "Empty EPG response" }
            body.byteStream().use { input ->
                XmltvParser.parse(input) { program ->
                    if (program.end >= from && program.start <= to) programs += program
                }
            }
        }

        programs.chunked(2000).forEach { dao.upsert(it) }
        dao.pruneBefore(from)
        programs.size
    }

    suspend fun nowNext(tvgId: String, shiftHours: Int = 0): NowNext? {
        if (tvgId.isBlank()) return null
        val now = System.currentTimeMillis()
        val shiftMs = shiftHours * 3_600_000L
        val from = now - 4L * 3_600_000L - shiftMs
        val rows = dao.windowFor(tvgId, from, limit = 6)
        if (rows.isEmpty()) return null

        val shifted = rows.map { it.copy(start = it.start + shiftMs, end = it.end + shiftMs) }
        val current = shifted.firstOrNull { now >= it.start && now < it.end }
        val next = shifted.firstOrNull { it.start > now }
        if (current == null && next == null) return null
        return NowNext(current, next)
    }
}