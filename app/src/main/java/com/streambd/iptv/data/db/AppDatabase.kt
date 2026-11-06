package com.streambd.iptv.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        GroupEntity::class,
        ChannelEntity::class,
        ProgramEntity::class,
        ProviderEntity::class,
        WatchProgressEntity::class, // ভার্সন ৫: Continue Watching
    ],
    version = 7,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun channels(): ChannelDao
    abstract fun groups(): GroupDao
    abstract fun epg(): EpgDao
    abstract fun providers(): ProviderDao
    abstract fun watchProgress(): WatchProgressDao
}

/** ৬ → ৭: Xtream credentials / EPG / custom User-Agent। সব নতুন nullable কলাম, কিছু মোছে না। */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `providers` ADD COLUMN `username` TEXT")
        db.execSQL("ALTER TABLE `providers` ADD COLUMN `password` TEXT")
        db.execSQL("ALTER TABLE `providers` ADD COLUMN `epgUrl` TEXT")
        db.execSQL("ALTER TABLE `providers` ADD COLUMN `userAgent` TEXT")
    }
}

/** ৫ → ৬: চ্যানেলে ব্যাকআপ সোর্স ও tvg-chno। দুটোই নতুন কলাম, কিছু মোছে না। */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `channels` ADD COLUMN `sources` TEXT")
        db.execSQL("ALTER TABLE `channels` ADD COLUMN `chno` INTEGER")
    }
}

/** ৪ → ৫: শুধু নতুন টেবিল যোগ হয়। চ্যানেল, প্রোভাইডার, ফেভারিট কিছুই মোছে না। */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `watch_progress` (" +
                "`id` INTEGER NOT NULL, " +
                "`title` TEXT NOT NULL, " +
                "`kind` TEXT NOT NULL, " +
                "`url` TEXT NOT NULL, " +
                "`logoUrl` TEXT, " +
                "`serverId` INTEGER NOT NULL, " +
                "`positionMs` INTEGER NOT NULL, " +
                "`durationMs` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_watch_progress_updatedAt` ON `watch_progress` (`updatedAt`)",
        )
    }
}
