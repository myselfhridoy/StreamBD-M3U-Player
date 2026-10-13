package com.streambd.iptv.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "programs",
    indices = [Index("channelTvgId"), Index("start"), Index("end")],
)
data class ProgramEntity(
    @PrimaryKey val id: Long,
    val channelTvgId: String,
    val start: Long,
    val end: Long,
    val title: String,
    val desc: String,
    val poster: String?,
)