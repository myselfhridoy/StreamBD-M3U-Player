package com.streambd.iptv.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ChannelDao {
    @Query("SELECT * FROM channels WHERE kind = :kind ORDER BY number")
    fun observeByKind(kind: String): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE kind = :kind AND groupId = :gid ORDER BY number")
    fun observeByKindAndGroup(kind: String, gid: Long): Flow<List<ChannelEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(items: List<ChannelEntity>)

    @Query("UPDATE channels SET isFavorite = :fav WHERE id = :id")
    suspend fun setFavorite(id: Long, fav: Boolean)

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
