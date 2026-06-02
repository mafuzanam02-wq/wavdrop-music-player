package com.launchpoint.wavdrop.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.launchpoint.wavdrop.data.local.MIGRATION_1_2
import com.launchpoint.wavdrop.data.local.MIGRATION_2_3
import com.launchpoint.wavdrop.data.local.MIGRATION_3_4
import com.launchpoint.wavdrop.data.local.MIGRATION_4_5
import com.launchpoint.wavdrop.data.local.MIGRATION_5_6
import com.launchpoint.wavdrop.data.local.MIGRATION_6_7
import com.launchpoint.wavdrop.data.local.WavdropDatabase
import com.launchpoint.wavdrop.data.local.dao.ImportBaselineDao
import com.launchpoint.wavdrop.data.local.dao.LyricsOverrideDao
import com.launchpoint.wavdrop.data.local.dao.PlaylistDao
import com.launchpoint.wavdrop.data.local.dao.SongDao
import com.launchpoint.wavdrop.data.local.dao.TrackListenEventDao
import com.launchpoint.wavdrop.data.local.dao.TrackStatsDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

private val Context.wavdropPreferencesDataStore by preferencesDataStore(
    name = "wavdrop_preferences",
)

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): WavdropDatabase =
        Room.databaseBuilder(context, WavdropDatabase::class.java, "wavdrop.db")
            .addMigrations(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7,
            )
            .build()

    @Provides
    @Singleton
    fun providePreferencesDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> = context.wavdropPreferencesDataStore

    @Provides
    fun provideSongDao(db: WavdropDatabase): SongDao = db.songDao()

    @Provides
    fun provideTrackStatsDao(db: WavdropDatabase): TrackStatsDao = db.trackStatsDao()

    @Provides
    fun provideImportBaselineDao(db: WavdropDatabase): ImportBaselineDao = db.importBaselineDao()

    @Provides
    fun providePlaylistDao(db: WavdropDatabase): PlaylistDao = db.playlistDao()

    @Provides
    fun provideTrackListenEventDao(db: WavdropDatabase): TrackListenEventDao = db.trackListenEventDao()

    @Provides
    fun provideLyricsOverrideDao(db: WavdropDatabase): LyricsOverrideDao = db.lyricsOverrideDao()
}
