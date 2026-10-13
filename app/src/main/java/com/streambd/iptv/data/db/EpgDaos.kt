package com.streambd.iptv.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface EpgDao {
    @Query(
        """
        SELECT * FROM programs
        WHERE channelTvgId IN (:tvgIds)
          AND end > :from
          AND start < :to
        ORDER BY start
        """,
    )
    fun observeWindow(tvgIds: List<String>, from: Long, to: Long): Flow<List<ProgramEntity>>

    @Query(
        """
        SELECT * FROM programs
        WHERE channelTvgId = :tvgId
          AND end > :from
        ORDER BY start
        LIMIT :limit
        """,
    )
    suspend fun windowFor(tvgId: String, from: Long, limit: Int): List<ProgramEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(items: List<ProgramEntity>)

    @Query("DELETE FROM programs WHERE end < :before")
    suspend fun pruneBefore(before: Long)

    @Query("DELETE FROM programs")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM programs")
    suspend fun count(): Int
}