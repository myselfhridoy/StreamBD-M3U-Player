package com.streambd.iptv.di

import android.content.Context
import androidx.room.Room
import com.streambd.iptv.data.db.AppDatabase
import com.streambd.iptv.data.db.ChannelDao
import com.streambd.iptv.data.db.GroupDao
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
        Room.databaseBuilder(ctx, AppDatabase::class.java, "streambd.db").build()

    @Provides fun provideChannelDao(db: AppDatabase): ChannelDao = db.channels()
    @Provides fun provideGroupDao(db: AppDatabase): GroupDao = db.groups()

    @Provides @Singleton
    fun provideOkHttp(): OkHttpClient = OkHttpClient.Builder().build()
}
