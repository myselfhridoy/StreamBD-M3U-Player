package com.streambd.iptv.data.repo

import androidx.room.withTransaction
import com.streambd.iptv.data.db.AppDatabase
import com.streambd.iptv.data.db.ChannelDao
import com.streambd.iptv.data.db.ChannelEntity
import com.streambd.iptv.data.db.GroupDao
import com.streambd.iptv.data.db.GroupEntity
import com.streambd.iptv.data.m3u.ChannelKind
import com.streambd.iptv.data.m3u.ParsedChannel
import com.streambd.iptv.data.util.stableId
import com.streambd.iptv.data.util.epgNameKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChannelRepository @Inject constructor(
    private val db: AppDatabase,
    private val channels: ChannelDao,
    private val groups: GroupDao,
) {
    fun liveChannels(): Flow<List<ChannelEntity>> = channels.observeByKind(ChannelKind.LIVE.name)
    fun liveChannelsIn(groupId: Long): Flow<List<ChannelEntity>> =
        channels.observeByKindAndGroup(ChannelKind.LIVE.name, groupId)
    fun liveGroups(): Flow<List<GroupEntity>> = groups.observeByKind(ChannelKind.LIVE.name)

    fun byKind(kind: ChannelKind): Flow<List<ChannelEntity>> =
        channels.observeByKind(kind.name)

    fun byKindIn(kind: ChannelKind, groupId: Long): Flow<List<ChannelEntity>> =
        channels.observeByKindAndGroup(kind.name, groupId)

    fun groupsByKind(kind: ChannelKind): Flow<List<GroupEntity>> =
        groups.observeByKind(kind.name)

    fun searchLive(query: String): Flow<List<ChannelEntity>> {
        val trimmed = query.trim()
        return if (trimmed.isBlank()) flowOf(emptyList())
        else channels.searchByKind(ChannelKind.LIVE.name, trimmed, limit = 300)
    }

    fun searchAll(query: String): Flow<List<ChannelEntity>> {
        val trimmed = query.trim()
        return if (trimmed.isBlank()) flowOf(emptyList()) else channels.searchAll(trimmed)
    }

    suspend fun hasContent(): Boolean = channels.count() > 0

    suspend fun tvgIds(): Set<String> = channels.tvgIds().toHashSet()

    suspend fun setFavorite(channelId: Long, fav: Boolean) = channels.setFavorite(channelId, fav)
    suspend fun setLocked(channelId: Long, locked: Boolean) = channels.setLocked(channelId, locked)

    suspend fun clearAll() {
        db.withTransaction {
            channels.clear()
            groups.clear()
        }
    }

    // নতুন: isPremium প্যারামিটার যুক্ত করা হলো
    suspend fun import(parsedChannels: List<ParsedChannel>, isPremium: Boolean = false): Int {
        if (parsedChannels.isEmpty()) return 0

        val rows = withContext(Dispatchers.Default) {
            buildRows(parsedChannels, isPremium)
        }

        db.withTransaction {
            val favorites = channels.favoriteIds().toHashSet()
            val locked = channels.lockedIds().toHashSet()

            channels.clear()
            groups.clear()
            groups.upsert(rows.groups)
            rows.channels.chunked(2_000).forEach { chunk ->
                channels.upsert(
                    chunk.map { it.copy(isFavorite = it.id in favorites, isLocked = it.id in locked) },
                )
            }
        }
        return rows.channels.size
    }

    private class ImportRows(val groups: List<GroupEntity>, val channels: List<ChannelEntity>)

    // নতুন: isPremium প্যারামিটার
    private fun buildRows(parsed: List<ParsedChannel>, isPremium: Boolean): ImportRows {
        val groupRows = LinkedHashMap<Long, GroupEntity>()
        val numbers = HashMap<ChannelKind, Int>()
        val occurrences = HashMap<String, Int>()
        val usedIds = HashSet<Long>(parsed.size * 2)
        val channelRows = ArrayList<ChannelEntity>(parsed.size)

        for (p in parsed) {
            val groupKey = "${p.kind.name}|${p.groupTitle.lowercase()}"
            val gid = stableId("G|$groupKey")
            if (gid !in groupRows) {
                groupRows[gid] = GroupEntity(
                    id = gid,
                    name = p.groupTitle,
                    order = groupRows.size,
                    kind = p.kind.name,
                )
            }

            val number = (numbers[p.kind] ?: 0) + 1
            numbers[p.kind] = number

            val key = "$groupKey|${p.name.lowercase()}"
            val occurrence = (occurrences[key] ?: 0) + 1
            occurrences[key] = occurrence

            var id = stableId("C|$key|$occurrence")
            while (!usedIds.add(id)) id = if (id == Long.MAX_VALUE) 1L else id + 1

            channelRows += ChannelEntity(
                id = id,
                number = number,
                name = p.name,
                logoUrl = p.logoUrl,
                streamUrl = p.streamUrl,
                groupId = gid,
                tvgId = p.tvgId?.takeIf { it.isNotBlank() } ?: epgNameKey(p.name),
                kind = p.kind.name,

                catchupType = p.catchupType,
                catchupDays = p.catchupDays,
                catchupSource = p.catchupSource,
                catchupEnable = p.catchupEnable,
                tvgShift = p.tvgShift,
                parentCode = p.parentCode,
                adult = p.adult,
                audioTrack = p.audioTrack,
                videoTrack = p.videoTrack,
                drmScheme = p.drmScheme,
                drmLicense = p.drmLicense,
                season = p.season,
                episode = p.episode,
                serieId = p.serieId,
                serieTitle = p.serieTitle,
                isPremium = isPremium, // নতুন: ডেটাবেসে সেভ হচ্ছে
            )
        }
        return ImportRows(groupRows.values.toList(), channelRows)
    }
}
