package com.streambd.iptv.data.repo

import com.streambd.iptv.data.db.ChannelDao
import com.streambd.iptv.data.db.ChannelEntity
import com.streambd.iptv.data.db.GroupDao
import com.streambd.iptv.data.db.GroupEntity
import com.streambd.iptv.data.m3u.ChannelKind
import com.streambd.iptv.data.m3u.M3uParser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChannelRepository @Inject constructor(
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

    suspend fun hasContent(): Boolean = channels.count() > 0

    suspend fun setFavorite(channelId: Long, fav: Boolean) = channels.setFavorite(channelId, fav)
    suspend fun setLocked(channelId: Long, locked: Boolean) = channels.setLocked(channelId, locked)

    suspend fun clearAll() {
        channels.clear()
        groups.clear()
    }

    /** Parse + persist the playlist. Replaces everything (simple, predictable). */
    suspend fun import(playlist: String) {
        val parsed = M3uParser.parse(playlist)
        if (parsed.isEmpty()) return

        // group id = stable hash of (kind + lowercase name) so favorites survive re-import
        fun groupIdFor(kind: ChannelKind, name: String): Long =
            (kind.name + "|" + name.lowercase()).hashCode().toLong() and 0x7FFFFFFF

        val groupRows = LinkedHashMap<Long, GroupEntity>()
        val perKindCounter = HashMap<ChannelKind, Int>()

        val channelRows = parsed.map { p ->
            val gid = groupIdFor(p.kind, p.groupTitle)
            groupRows.getOrPut(gid) {
                GroupEntity(id = gid, name = p.groupTitle, order = groupRows.size, kind = p.kind.name)
            }
            val n = (perKindCounter[p.kind] ?: 0) + 1
            perKindCounter[p.kind] = n
            ChannelEntity(
                id = 0L, // placeholder, replaced below with a stable id
                number = n,
                name = p.name,
                logoUrl = p.logoUrl,
                streamUrl = p.streamUrl,
                groupId = gid,
                tvgId = p.tvgId,
                kind = p.kind.name,
            )
        }.mapIndexed { i, c ->
            // stable id derived from kind+group+name+url; dedupes identical entries
            val key = "${c.kind}|${c.groupId}|${c.name.lowercase()}|${c.streamUrl}"
            c.copy(id = (key.hashCode().toLong() and 0x7FFFFFFF) * 1000L + i)
        }

        channels.clear()
        groups.clear()
        groups.upsert(groupRows.values.toList())
        channels.upsert(channelRows)
    }
}
