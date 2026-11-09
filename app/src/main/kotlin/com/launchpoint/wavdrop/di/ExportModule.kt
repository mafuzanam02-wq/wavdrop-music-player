package com.launchpoint.wavdrop.di

import com.launchpoint.wavdrop.data.export.MediaPathSource
import com.launchpoint.wavdrop.data.export.MediaStorePathResolver
import com.launchpoint.wavdrop.data.export.PlaylistExportRepository
import com.launchpoint.wavdrop.data.export.PlaylistExporter
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class ExportModule {
    @Binds abstract fun bindPathSource(impl: MediaStorePathResolver): MediaPathSource
    @Binds abstract fun bindPlaylistExporter(impl: PlaylistExportRepository): PlaylistExporter
}
