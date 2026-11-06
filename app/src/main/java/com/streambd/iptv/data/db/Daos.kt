package com.streambd.iptv.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

data class IdNumber(val id: Long, val number: Int)

@Dao
interface ChannelDao {
    @Query("SELECT * FROM channels WHERE kind = :kind AND hidden = 0 ORDER BY number")
    fun observeByKind(kind: String): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE kind = :kind AND groupId = :gid AND hidden = 0 ORDER BY number")
    fun observeByKindAndGroup(kind: String, gid: Long): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE kind = :kind AND hidden = 0 AND name LIKE '%' || :q || '%' COLLATE NOCASE ORDER BY number LIMIT :limit")
    fun searchByKind(kind: String, q: String, limit: Int = 300): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE hidden = 0 AND name LIKE '%' || :q || '%' COLLATE NOCASE ORDER BY kind, number LIMIT 300")
    fun searchAll(q: String): Flow<List<ChannelEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(items: List<ChannelEntity>)

    @Query("UPDATE channels SET isFavorite = :fav WHERE id = :id")
    suspend fun setFavorite(id: Long, fav: Boolean)

    @Query("UPDATE channels SET isLocked = :locked WHERE id = :id")
    suspend fun setLocked(id: Long, locked: Boolean)

    @Query("SELECT id FROM channels WHERE isFavorite = 1")
    suspend fun favoriteIds(): List<Long>

    @Query("SELECT id FROM channels WHERE isLocked = 1")
    suspend fun lockedIds(): List<Long>

    @Query("SELECT DISTINCT tvgId FROM channels WHERE tvgId IS NOT NULL AND tvgId != ''")
    suspend fun tvgIds(): List<String>

    @Query("DELETE FROM channels") suspend fun clear()
    @Query("DELETE FROM channels WHERE providerId = :providerId") suspend fun clearByProvider(providerId: Long)
    @Query("SELECT COUNT(*) FROM channels") suspend fun count(): Int

    // নতুন: প্রোভাইডার অনুযায়ী অর্ডার করে সব চ্যানেল আনার জন্য
    @Query("SELECT * FROM channels WHERE kind = :kind ORDER BY providerId, number")
    suspend fun listByKindOrdered(kind: String): List<ChannelEntity>

    // নতুন: চ্যানেলের নম্বর আপডেট করার জন্য
    @Query("UPDATE channels SET number = :number WHERE id = :id")
    suspend fun updateNumber(id: Long, number: Int)

    // ইমপোর্টের আগে পুরনো নম্বর ধরে রাখতে (নম্বর স্থিতিশীল রাখার জন্য)
    @Query("SELECT id, number FROM channels WHERE providerId = :providerId")
    suspend fun numbersByProvider(providerId: Long): List<IdNumber>

    // Continue Watching থেকে চালানোর সময় টাটকা URL/টোকেন আনতে
    @Query("SELECT * FROM channels WHERE id = :id")
    suspend fun getById(id: Long): ChannelEntity?
}

@Dao
interface GroupDao {
    @Query("SELECT * FROM groups WHERE kind = :kind ORDER BY `order`")
    fun observeByKind(kind: String): Flow<List<GroupEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(items: List<GroupEntity>)

    @Query("DELETE FROM groups") suspend fun clear()
    @Query("DELETE FROM groups WHERE providerId = :providerId") suspend fun clearByProvider(providerId: Long)
}

@Dao
interface ProviderDao {
    @Query("SELECT * FROM providers ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ProviderEntity>>

    @Query("SELECT * FROM providers ORDER BY createdAt DESC")
    suspend fun getAll(): List<ProviderEntity>

    @Query("SELECT * FROM providers WHERE id = :id")
    suspend fun getById(id: Long): ProviderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(provider: ProviderEntity): Long

    @Update
    suspend fun update(provider: ProviderEntity)

    @Query("UPDATE providers SET epgUrl = :epgUrl WHERE id = :id")
    suspend fun setEpgUrl(id: Long, epgUrl: String)

    @Query("DELETE FROM providers WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM providers")
    suspend fun clear()
}

@Dao
interface WatchProgressDao {
    @Query("SELECT * FROM watch_progress ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<WatchProgressEntity>>

    @Query("SELECT * FROM watch_progress WHERE id = :id")
    suspend fun get(id: Long): WatchProgressEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: WatchProgressEntity)

    @Query("DELETE FROM watch_progress WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM watch_progress")
    suspend fun clear()

    /** সবচেয়ে নতুন [keep]টি রেখে বাকি মোছে। */
    @Query("DELETE FROM watch_progress WHERE id NOT IN (SELECT id FROM watch_progress ORDER BY updatedAt DESC LIMIT :keep)")
    suspend fun prune(keep: Int)
}