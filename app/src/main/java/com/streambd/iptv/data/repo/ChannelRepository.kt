package com.streambd.iptv.data.repo

import androidx.room.withTransaction
import com.streambd.iptv.data.db.AppDatabase
import com.streambd.iptv.data.db.ChannelDao
import com.streambd.iptv.data.db.ChannelEntity
import com.streambd.iptv.data.db.GroupDao
import com.streambd.iptv.data.db.GroupEntity
import com.streambd.iptv.data.db.StreamSource
import com.streambd.iptv.data.db.StreamSources
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

/** একটা চ্যানেলে সর্বোচ্চ কতগুলো ব্যাকআপ সোর্স রাখা হবে। */

private val WHITESPACE = Regex("\\s+")

@Singleton
class ChannelRepository @Inject constructor(
    private val db: AppDatabase,
    private val channels: ChannelDao,
    private val groups: GroupDao,
) {
    fun liveChannels(): Flow<List<ChannelEntity>> = channels.observeByKind(ChannelKind.LIVE.name)
    fun liveChannelsIn(groupId: Long): Flow<List<ChannelEntity>> = channels.observeByKindAndGroup(ChannelKind.LIVE.name, groupId)
    fun liveGroups(): Flow<List<GroupEntity>> = groups.observeByKind(ChannelKind.LIVE.name)

    fun byKind(kind: ChannelKind): Flow<List<ChannelEntity>> = channels.observeByKind(kind.name)
    fun byKindIn(kind: ChannelKind, groupId: Long): Flow<List<ChannelEntity>> = channels.observeByKindAndGroup(kind.name, groupId)
    fun groupsByKind(kind: ChannelKind): Flow<List<GroupEntity>> = groups.observeByKind(kind.name)

    fun searchLive(query: String): Flow<List<ChannelEntity>> {
        val trimmed = query.trim()
        return if (trimmed.isBlank()) flowOf(emptyList()) else channels.searchByKind(ChannelKind.LIVE.name, trimmed, limit = 300)
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

    suspend fun clearProviderData(providerId: Long) {
        db.withTransaction {
            channels.clearByProvider(providerId)
            groups.clearByProvider(providerId)
        }
        // ★ নতুন: ডিলিট করার পর নম্বর রি-অ্যাসাইন করা
        renumber()
    }

    suspend fun import(parsedChannels: List<ParsedChannel>, isPremium: Boolean = false, providerId: Long = 0L): Int {
        if (parsedChannels.isEmpty()) return 0

        val rows = withContext(Dispatchers.Default) {
            buildRows(parsedChannels, isPremium, providerId)
        }

        db.withTransaction {
            val oldNumbers = HashMap<Long, Int>()
            for (r in channels.numbersByProvider(providerId)) if (r.number in 1..9999) oldNumbers[r.id] = r.number
            val favorites = channels.favoriteIds().toHashSet()
            val locked = channels.lockedIds().toHashSet()

            // পুরো ডেটাবেস ক্লিয়ার না করে, শুধু এই নির্দিষ্ট প্রোভাইডারের ডেটা ক্লিয়ার করা হচ্ছে
            channels.clearByProvider(providerId)
            groups.clearByProvider(providerId)
            
            groups.upsert(rows.groups)
            rows.channels.chunked(2_000).forEach { chunk ->
                channels.upsert(chunk.map {
                    it.copy(
                        isFavorite = it.id in favorites,
                        isLocked = it.id in locked,
                        number = oldNumbers[it.id] ?: it.number, // আগের নম্বর থাকলে সেটাই
                    )
                })
            }
        }
        
        // ★ নতুন: ইমপোর্ট শেষে নম্বর রি-অ্যাসাইন করা
        renumber()
        
        return rows.channels.size
    }

    /**
     * সব provider মিলিয়ে প্রতিটা kind এ কাজ করে:
     * ১. Live/Radio-তে একই নামের চ্যানেল অন্য প্লেলিস্টে থাকলে সেটা মূলটার ব্যাকআপ হয়ে লুকায় (প্রতিবার নতুন করে হিসাব)।
     * ২. দৃশ্যমান চ্যানেলে নম্বর বসে: tvg-chno আগে, তারপর আগের নম্বর (স্থিতিশীল), শেষে ফাঁকা নম্বরে ক্রম অনুযায়ী।
     */
    suspend fun renumber() {
        db.withTransaction {
            for (kind in ChannelKind.values()) {
                val list = channels.listByKindOrdered(kind.name)
                val hidden = BooleanArray(list.size)
                val extras = arrayOfNulls<String>(list.size)
                if (kind == ChannelKind.LIVE || kind == ChannelKind.RADIO) mergeAcrossProviders(list, hidden, extras)

                val visible = list.indices.filter { !hidden[it] }
                val numbers = assignNumbers(visible.map { list[it] })
                val newNumber = HashMap<Int, Int>()
                visible.forEachIndexed { k, i -> newNumber[i] = numbers[k] }

                val changed = list.mapIndexedNotNull { i, c ->
                    val n = newNumber[i] ?: c.number // লুকানো চ্যানেলের নম্বর অপরিবর্তিত
                    if (c.number != n || c.hidden != hidden[i] || c.extraSources != extras[i]) {
                        c.copy(number = n, hidden = hidden[i], extraSources = extras[i])
                    } else null
                }
                changed.chunked(2_000).forEach { channels.upsert(it) }
            }
        }
    }

    private fun nameKey(name: String) = name.trim().lowercase().replace(WHITESPACE, " ")

    private fun ownSources(c: ChannelEntity): List<StreamSource> =
        listOf(StreamSource(c.streamUrl, c.drmScheme, c.drmLicense)) + StreamSources.decode(c.sources)

    /** একই নামের চ্যানেল ভিন্ন প্লেলিস্টে থাকলে প্রথমটা (provider ক্রমে) মূল, বাকিগুলো লুকিয়ে তার extraSources হয়। */
    private fun mergeAcrossProviders(list: List<ChannelEntity>, hidden: BooleanArray, extras: Array<String?>) {
        val primary = HashMap<String, Int>()
        val gathered = HashMap<Int, MutableList<StreamSource>>()
        for ((i, c) in list.withIndex()) {
            val key = nameKey(c.name)
            val p = primary[key]
            if (p == null) {
                primary[key] = i
                continue
            }
            // একই প্লেলিস্টের ভিন্ন গ্রুপের একই নাম আগের মতো আলাদা চ্যানেল থাকে
            if (list[p].providerId == c.providerId) continue
            hidden[i] = true
            val have = ownSources(list[p]).mapTo(HashSet()) { it.url }
            val g = gathered.getOrPut(p) { ArrayList() }
            g.forEach { have += it.url }
            for (s in ownSources(c)) if (have.add(s.url)) g += s
        }
        for ((p, g) in gathered) extras[p] = StreamSources.encode(g)
    }

    private fun assignNumbers(list: List<ChannelEntity>): IntArray {
        val result = IntArray(list.size)
        val taken = HashSet<Int>()
        // ১. যে চ্যানেল ইতিমধ্যে নিজের tvg-chno নম্বরে আছে, সে আগে দাবি করে (বারবার জায়গা বদল ঠেকাতে)
        for ((i, c) in list.withIndex()) {
            val want = c.chno ?: continue
            if (want in 1..9999 && c.number == want && taken.add(want)) result[i] = want
        }
        // ২. বাকি tvg-chno-ওয়ালারা (provider/ক্রম অনুযায়ী, আগেরজন জেতে)
        for ((i, c) in list.withIndex()) {
            if (result[i] != 0) continue
            val want = c.chno ?: continue
            if (want in 1..9999 && taken.add(want)) result[i] = want
        }
        // ৩. আগে থেকে নম্বর পাওয়া চ্যানেল (tvg-chno ছাড়া) নিজের নম্বর রাখে, যাতে নম্বর সরে না যায়
        for ((i, c) in list.withIndex()) {
            if (result[i] != 0) continue
            if (c.number in 1..9999 && taken.add(c.number)) result[i] = c.number
        }
        // ৪. নতুনরা ফাঁকা নম্বরে ক্রম অনুযায়ী
        var next = 1
        for (i in list.indices) {
            if (result[i] != 0) continue
            while (next in taken) next++
            result[i] = next
            taken += next
            next++
        }
        return result
    }

    private class ImportRows(val groups: List<GroupEntity>, val channels: List<ChannelEntity>)

    private fun buildRows(parsed: List<ParsedChannel>, isPremium: Boolean, providerId: Long): ImportRows {
        val groupRows = LinkedHashMap<Long, GroupEntity>()
        val numbers = HashMap<ChannelKind, Int>()
        val occurrences = HashMap<String, Int>()
        val usedIds = HashSet<Long>(parsed.size * 2)
        val channelRows = ArrayList<ChannelEntity>(parsed.size)
        // ব্যাকআপ জোড়া: মূল চ্যানেলের key -> channelRows-এর ইনডেক্স, আর ইনডেক্স -> ব্যাকআপ তালিকা
        val mainIndex = HashMap<String, Int>()
        val backups = HashMap<Int, MutableList<StreamSource>>()

        for (p in parsed) {
            // providerId যুক্ত করা হলো যাতে গ্রুপ আইডি ক্ল্যাশ না করে
            val groupKey = "$providerId|${p.kind.name}|${p.groupTitle.lowercase()}"
            val gid = stableId("G|$groupKey")
            if (gid !in groupRows) {
                groupRows[gid] = GroupEntity(id = gid, name = p.groupTitle, order = groupRows.size, kind = p.kind.name, providerId = providerId)
            }

            val key = "$groupKey|${p.name.lowercase()}"

            // Live/Radio-তে একই নাম ও গ্রুপের পরের এন্ট্রি আলাদা চ্যানেল নয়, মূলটার ব্যাকআপ সোর্স
            val mergeable = (p.kind == ChannelKind.LIVE || p.kind == ChannelKind.RADIO) && !p.isNestedPlaylist
            if (mergeable) {
                val idx = mainIndex[key]
                if (idx != null) {
                    val main = channelRows[idx]
                    val list = backups.getOrPut(idx) { ArrayList() }
                    val duplicate = p.streamUrl == main.streamUrl || list.any { it.url == p.streamUrl }
                    if (!duplicate) {
                        list += StreamSource(p.streamUrl, p.drmScheme, p.drmLicense)
                    }
                    continue
                }
            }

            val seq = (numbers[p.kind] ?: 0) + 1
            numbers[p.kind] = seq
            val number = 10_000 + seq // সাময়িক (১০০০০+): renumber() এ আসল নম্বর বসবে

            val occurrence = (occurrences[key] ?: 0) + 1
            occurrences[key] = occurrence

            var id = stableId("C|$key|$occurrence")
            while (!usedIds.add(id)) id = if (id == Long.MAX_VALUE) 1L else id + 1

            channelRows += ChannelEntity(
                id = id, number = number, name = p.name, logoUrl = p.logoUrl, streamUrl = p.streamUrl,
                groupId = gid, tvgId = p.tvgId?.takeIf { it.isNotBlank() } ?: epgNameKey(p.name), kind = p.kind.name,
                catchupType = p.catchupType, catchupDays = p.catchupDays, catchupSource = p.catchupSource, catchupEnable = p.catchupEnable,
                tvgShift = p.tvgShift, parentCode = p.parentCode, adult = p.adult, audioTrack = p.audioTrack, videoTrack = p.videoTrack,
                drmScheme = p.drmScheme, drmLicense = p.drmLicense, season = p.season, episode = p.episode, serieId = p.serieId, serieTitle = p.serieTitle,
                isPremium = isPremium, providerId = providerId, chno = p.chno,
            )
            if (mergeable) mainIndex[key] = channelRows.lastIndex
        }

        val finalRows = if (backups.isEmpty()) {
            channelRows
        } else {
            channelRows.mapIndexed { i, c ->
                val list = backups[i]
                if (list.isNullOrEmpty()) c else c.copy(sources = StreamSources.encode(list))
            }
        }
        return ImportRows(groupRows.values.toList(), finalRows)
    }
}
