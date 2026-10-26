package com.streambd.iptv.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ChannelDao {
    @Query("SELECT * FROM channels WHERE kind = :kind ORDER BY number")
    fun observeByKind(kind: String): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE kind = :kind AND groupId = :gid ORDER BY number")
    fun observeByKindAndGroup(kind: String, gid: Long): Flow<List<ChannelEntity>>

    @Query(
        """
        SELECT * FROM channels
        WHERE kind = :kind
            AND name LIKE '%' || :q || '%' COLLATE NOCASE
        ORDER BY number
        LIMIT :limit
        """,
    )
    fun searchByKind(kind: String, q: String, limit: Int = 300): Flow<List<ChannelEntity>>

    @Query(
        """
        SELECT * FROM channels
        WHERE name LIKE '%' || :q || '%' COLLATE NOCASE
        ORDER BY kind, number
        LIMIT 300
        """,
    )
    fun searchAll(q: String): Flow<List<ChannelEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(items: List<ChannelEntity>)

    @Query("UPDATE channels SET isFavorite = :fav WHERE id = :id")
    suspend fun setFavorite(id: Long, fav: Boolean)

    @Query("UPDATE channels SET isLocked = :locked WHERE id = :id")
    suspend fun setLocked(id: Long, locked: Boolean)

    /** Re-import-এর আগে favorite/lock সংরক্ষণের জন্য। */
    @Query("SELECT id FROM channels WHERE isFavorite = 1")
    suspend fun favoriteIds(): List<Long>

    @Query("SELECT id FROM channels WHERE isLocked = 1")
    suspend fun lockedIds(): List<Long>

    /** EPG ইমপোর্টে শুধু দরকারি চ্যানেল রাখার জন্য। */
    @Query("SELECT DISTINCT tvgId FROM channels WHERE tvgId IS NOT NULL AND tvgId != ''")
    suspend fun tvgIds(): List<String>

    @Query("DELETE FROM channels") suspend fun clear()

    @Query("SELECT COUNT(*) FROM channels") suspend fun count(): Int
}

@Dao
interface GroupDao {
    @Query("SELECT * FROM groups WHERE kind = :kind ORDER BY `order`")
    fun observeByKind(kind: String): Flow<List<GroupEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(items: List<GroupEntity>)

    @Query("DELETE FROM groups") suspend fun clear()
}
