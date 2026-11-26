package com.streambd.iptv.di

import android.content.Context
import androidx.room.Room
import com.streambd.iptv.data.db.AppDatabase
import com.streambd.iptv.data.db.ChannelDao
import com.streambd.iptv.data.db.EpgDao
import com.streambd.iptv.data.db.GroupDao
import com.streambd.iptv.data.db.MIGRATION_4_5
import com.streambd.iptv.data.db.MIGRATION_5_6
import com.streambd.iptv.data.db.MIGRATION_6_7
import com.streambd.iptv.data.db.MIGRATION_7_8
import com.streambd.iptv.data.db.MIGRATION_8_9
import com.streambd.iptv.data.db.MIGRATION_9_10
import com.streambd.iptv.data.db.AddonDao
import com.streambd.iptv.data.db.ProviderDao
import com.streambd.iptv.data.db.WatchProgressDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun provideDb(@ApplicationContext ctx: Context): AppDatabase =
        Room.databaseBuilder(ctx, AppDatabase::class.java, "streambd.db")
            .addMigrations(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10) // ডেটা অক্ষত থাকে
            // শুধু অতি পুরোনো (৪-এর আগের) ভার্সন মোছা হবে। ভবিষ্যতে ভার্সন বাড়িয়ে MIGRATION লিখতে ভুলে গেলে আগে যেমন
            // চুপচাপ সব ডেটা মুছে যেত, এখন তেমন হবে না: টেস্টেই ক্র্যাশ করে ধরা পড়বে।
            .fallbackToDestructiveMigrationFrom(1, 2, 3)
            .build()

    @Provides fun provideChannelDao(db: AppDatabase): ChannelDao = db.channels()
    @Provides fun provideGroupDao(db: AppDatabase): GroupDao = db.groups()
    @Provides fun provideEpgDao(db: AppDatabase): EpgDao = db.epg()
    @Provides fun provideProviderDao(db: AppDatabase): ProviderDao = db.providers() // নতুন
    @Provides fun provideWatchProgressDao(db: AppDatabase): WatchProgressDao = db.watchProgress()
    @Provides fun provideAddonDao(db: AppDatabase): AddonDao = db.addons()

    @Provides @Singleton
    fun provideOkHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()
}

