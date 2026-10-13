package com.streambd.iptv.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "groups")
data class GroupEntity(
    @PrimaryKey val id: Long,
    val name: String,
    val order: Int,
    val kind: String,   // "LIVE" | "MOVIE" | "SERIES"
)

@Entity(
    tableName = "channels",
    indices = [Index("groupId"), Index("tvgId"), Index("kind")]
)
data class ChannelEntity(
    @PrimaryKey val id: Long,
    val number: Int,
    val name: String,
    val logoUrl: String?,
    val streamUrl: String,
    val groupId: Long,
    val tvgId: String?,
    val kind: String,
    val isFavorite: Boolean = false,
    val isLocked: Boolean = false,
)
