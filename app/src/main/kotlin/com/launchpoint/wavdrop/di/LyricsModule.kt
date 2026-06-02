package com.launchpoint.wavdrop.di

import com.launchpoint.wavdrop.data.lyrics.LyricsExtractor
import com.launchpoint.wavdrop.data.lyrics.MediaMetadataLyricsExtractor
import com.launchpoint.wavdrop.data.lyrics.MediaStoreSidecarLyricsExtractor
import com.launchpoint.wavdrop.data.lyrics.SidecarLyricsExtractor
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class LyricsModule {

    @Binds
    @Singleton
    abstract fun bindLyricsExtractor(impl: MediaMetadataLyricsExtractor): LyricsExtractor

    @Binds
    @Singleton
    abstract fun bindSidecarLyricsExtractor(
        impl: MediaStoreSidecarLyricsExtractor,
    ): SidecarLyricsExtractor
}
