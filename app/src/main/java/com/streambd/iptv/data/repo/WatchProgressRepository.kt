package com.streambd.iptv.data.repo

import com.streambd.iptv.data.db.ChannelEntity
import com.streambd.iptv.data.db.WatchProgressDao
import com.streambd.iptv.data.db.WatchProgressEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** মুভি/এপিসোড কতদূর দেখা হয়েছে তার হিসাব। লাইভ ও রেডিও কখনো সেভ হয় না। */
@Singleton
class WatchProgressRepository @Inject constructor(
    private val dao: WatchProgressDao,
) {
    private companion object {
        const val MIN_DURATION_MS = 3L * 60 * 1000   // ৩ মিনিটের ছোট ক্লিপ সেভ হয় না
        const val MIN_POSITION_MS = 30_000L          // প্রথম ৩০ সেকেন্ড সেভ হয় না
        const val FINISHED_FRACTION = 0.95           // ৯৫% দেখলে "শেষ"
        const val FINISHED_TAIL_MS = 60_000L         // বা শেষ ১ মিনিট বাকি
        const val MAX_ITEMS = 50
        const val RESUME_BACKOFF_MS = 5_000L         // চালু করার সময় ৫ সেকেন্ড পিছন থেকে
    }

    fun observe(): Flow<List<WatchProgressEntity>> = dao.observeAll()

    /** আগে দেখা থাকলে যে জায়গা থেকে শুরু করতে হবে (মিলিসেকেন্ড), না থাকলে null। */
    suspend fun resumePositionFor(id: Long): Long? {
        val e = dao.get(id) ?: return null
        return (e.positionMs - RESUME_BACKOFF_MS).coerceAtLeast(0L)
    }

    suspend fun save(channel: ChannelEntity, positionMs: Long, durationMs: Long) {
        if (channel.kind != "MOVIE" && channel.kind != "SERIES") return
        if (durationMs < MIN_DURATION_MS) return

        val finished = positionMs >= (durationMs * FINISHED_FRACTION).toLong() ||
            durationMs - positionMs < FINISHED_TAIL_MS
        if (finished) {
            dao.delete(channel.id)
            return
        }
        if (positionMs < MIN_POSITION_MS) return

        dao.upsert(
            WatchProgressEntity(
                id = channel.id,
                title = channel.name,
                kind = channel.kind,
                url = channel.streamUrl,
                logoUrl = channel.logoUrl,
                serverId = channel.providerId,
                positionMs = positionMs,
                durationMs = durationMs,
                updatedAt = System.currentTimeMillis(),
            ),
        )
        dao.prune(MAX_ITEMS)
    }

    suspend fun remove(id: Long) = dao.delete(id)
    suspend fun clear() = dao.clear()
}
