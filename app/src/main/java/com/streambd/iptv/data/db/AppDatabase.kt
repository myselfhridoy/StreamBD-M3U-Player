package com.streambd.iptv.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [GroupEntity::class, ChannelEntity::class, ProgramEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun channels(): ChannelDao
    abstract fun groups(): GroupDao
    abstract fun epg(): EpgDao
}
