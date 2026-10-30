package com.streambd.iptv.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "groups")
data class GroupEntity(
    @PrimaryKey val id: Long,
    val name: String,
    val order: Int,
    val kind: String,   // "LIVE" | "MOVIE" | "SERIES" | "RADIO"
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
    
    // নতুন ডেটাবেস কলাম
    val catchupType: String? = null,
    val catchupDays: Int = 0,
    val catchupSource: String? = null,
    val catchupEnable: Boolean = false,
    val tvgShift: Int = 0,
    val parentCode: String? = null,
    val adult: Boolean = false,
    val audioTrack: String? = null,
    val videoTrack: String? = null,
    val drmScheme: String? = null,
    val drmLicense: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val serieId: String? = null,
    val serieTitle: String? = null,
    
    // নতুন: সার্ভারের কন্টেন্ট নাকি ইউজারের নিজের কন্টেন্ট তা বোঝার জন্য
    val isPremium: Boolean = false,
)

// নতুন: ইউজারের নিজের প্লেলিস্ট সেভ করার টেবিল
@Entity(tableName = "providers")
data class ProviderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val url: String,
    val type: String = "M3U", // ভবিষ্যতে "XTREAM" ইত্যাদির জন্য
    val createdAt: Long = System.currentTimeMillis()
)
