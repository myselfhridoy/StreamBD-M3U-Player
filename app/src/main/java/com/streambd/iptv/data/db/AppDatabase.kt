package com.streambd.iptv.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [GroupEntity::class, ChannelEntity::class, ProgramEntity::class, ProviderEntity::class],
    version = 4, // নতুন ProviderEntity যুক্ত করায় ভার্সন ৪ করা হলো
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun channels(): ChannelDao
    abstract fun groups(): GroupDao
    abstract fun epg(): EpgDao
    abstract fun providers(): ProviderDao // নতুন
}
