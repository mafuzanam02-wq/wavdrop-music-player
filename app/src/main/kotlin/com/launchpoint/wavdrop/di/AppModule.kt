package com.launchpoint.wavdrop.di

import android.content.Context
import androidx.room.Room
import com.launchpoint.wavdrop.data.local.WavdropDatabase
import com.launchpoint.wavdrop.data.local.dao.SongDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): WavdropDatabase =
        Room.databaseBuilder(context, WavdropDatabase::class.java, "wavdrop.db")
            .build()

    @Provides
    fun provideSongDao(db: WavdropDatabase): SongDao = db.songDao()
}
