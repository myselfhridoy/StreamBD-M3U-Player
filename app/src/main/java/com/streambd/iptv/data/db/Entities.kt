package com.streambd.iptv.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "groups")
data class GroupEntity(
    @PrimaryKey val id: Long,
    val name: String,
    val order: Int,
    val kind: String,   // "LIVE" | "MOVIE" | "SERIES" | "RADIO"
    val providerId: Long = 0L // নতুন: 0 = সার্ভার, 1+ = ইউজারের নিজের প্লেলিস্ট
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
    
    val isPremium: Boolean = false,
    val providerId: Long = 0L, // নতুন: সার্ভার নাকি ইউজারের প্লেলিস্ট

    /** ব্যাকআপ সোর্সের JSON তালিকা (StreamSources)। শুধু Live/Radio-তে। */
    val sources: String? = null,
    /** প্লেলিস্টের tvg-chno: থাকলে চ্যানেল সবসময় এই নম্বর পায়। */
    val chno: Int? = null,

    /** Movie/Series সারির নিচের ছোট লেখা (add-on-এর `subtitle`)। */
    val subtitle: String? = null,

    /** অন্য প্লেলিস্টে একই চ্যানেলের সোর্স (renumber() প্রতিবার নতুন করে হিসাব করে)। */
    val extraSources: String? = null,
    /** true হলে অন্য প্লেলিস্টের মূল চ্যানেলের ব্যাকআপ হয়ে লুকানো আছে; তালিকায় দেখায় না। */
    @ColumnInfo(defaultValue = "0") val hidden: Boolean = false,
)

@Entity(tableName = "providers")
data class ProviderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val url: String,
    val type: String = "M3U",
    val createdAt: Long = System.currentTimeMillis(),

    // Xtream Codes (এবং ভবিষ্যতের plugin) fields
    val username: String? = null,
    val password: String? = null,
    val epgUrl: String? = null,
    val userAgent: String? = null,
)

/**
 * কোন মুভি/এপিসোড কতটুকু দেখা হয়েছে। [id] = ChannelEntity.id (FTP ফাইলের ক্ষেত্রে stableId("ftp:URL"))।
 * প্লেলিস্ট রিফ্রেশে চ্যানেল আইডি একই থাকে, তাই অগ্রগতি হারায় না।
 */
@Entity(tableName = "watch_progress", indices = [Index("updatedAt")])
data class WatchProgressEntity(
    @PrimaryKey val id: Long,
    val title: String,
    val kind: String,
    val url: String,
    val logoUrl: String?,
    val serverId: Long,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
)

/**
 * ইনস্টল করা Add-on (JS স্ক্রিপ্ট)। প্রতিটা Add-on-এর একটা providers সারি থাকে (type = "ADDON",
 * url = "addon:<addonId>"); চ্যানেলগুলো ওই [providerId]-র অধীনে ইমপোর্ট হয়।
 */
@Entity(tableName = "addons", indices = [Index(value = ["addonId"], unique = true)])
data class AddonEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val addonId: String,
    val name: String,
    val version: String,
    val script: String,
    /** ইনস্টলের সময় স্ক্রিপ্টের `addon` অবজেক্ট থেকে নেওয়া JSON। */
    val manifest: String,
    /** ইউজারের সেটিংস মান (JSON object, key -> string)। */
    val settings: String,
    /** Add-on-এর নিজের key-value স্টোরেজ (JSON object)। */
    val storage: String,
    val enabled: Boolean,
    /** "CUSTOM" (ইউজার নিজে URL/ফাইল থেকে) | "CATALOG" (অফিসিয়াল ক্যাটালগ, ভবিষ্যতের জন্য)। */
    val origin: String,
    val sourceUrl: String?,
    /** স্বাক্ষর যাচাই হয়েছে কিনা (ক্যাটালগের জন্য, এখন সবসময় false)। */
    val verified: Boolean,
    val providerId: Long,
    val installedAt: Long,
    val updatedAt: Long,
    val lastRunAt: Long,
    val lastStatus: String?,
    val lastLog: String?,
)
