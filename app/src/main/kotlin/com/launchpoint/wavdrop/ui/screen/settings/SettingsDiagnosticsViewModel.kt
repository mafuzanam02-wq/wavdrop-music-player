package com.launchpoint.wavdrop.ui.screen.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.launchpoint.wavdrop.data.grouping.AlbumGrouper
import com.launchpoint.wavdrop.data.grouping.ArtistGrouper
import com.launchpoint.wavdrop.data.repository.PlaylistRepository
import com.launchpoint.wavdrop.data.repository.SongRepository
import com.launchpoint.wavdrop.data.repository.StatsRepository
import com.launchpoint.wavdrop.data.settings.AccentColor
import com.launchpoint.wavdrop.data.settings.AppSettingsRepository
import com.launchpoint.wavdrop.data.settings.LibraryScanMode
import com.launchpoint.wavdrop.data.settings.LibraryScanSettings
import com.launchpoint.wavdrop.data.settings.LibraryScanSettingsRepository
import com.launchpoint.wavdrop.data.settings.StartupDestination
import com.launchpoint.wavdrop.data.settings.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class SettingsDiagnosticsUiState(
    val isLoading: Boolean = true,
    val songCount: Int = 0,
    val albumCount: Int = 0,
    val artistCount: Int = 0,
    val playlistCount: Int = 0,
    val listenEventCount: Int = 0,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val accentColor: AccentColor = AccentColor.MIDNIGHT_VIOLET,
    val startupDestination: StartupDestination = StartupDestination.SONGS,
    val scanSettings: LibraryScanSettings = LibraryScanSettings(),
)

@HiltViewModel
class SettingsDiagnosticsViewModel @Inject constructor(
    songRepository: SongRepository,
    playlistRepository: PlaylistRepository,
    statsRepository: StatsRepository,
    appSettingsRepository: AppSettingsRepository,
    scanSettingsRepository: LibraryScanSettingsRepository,
) : ViewModel() {

    private val libraryCounts = combine(
        songRepository.songs,
        playlistRepository.observePlaylists(),
        statsRepository.allListenEvents(),
    ) { songs, playlists, listenEvents ->
        LibraryCounts(
            songCount = songs.size,
            albumCount = AlbumGrouper.group(songs).size,
            artistCount = ArtistGrouper.group(songs).size,
            playlistCount = playlists.size,
            listenEventCount = listenEvents.size,
        )
    }

    private val appPreferences = combine(
        appSettingsRepository.themeMode,
        appSettingsRepository.accentColor,
        appSettingsRepository.startupDestination,
    ) { themeMode, accentColor, startupDestination ->
        AppPreferences(
            themeMode = themeMode,
            accentColor = accentColor,
            startupDestination = startupDestination,
        )
    }

    val uiState: StateFlow<SettingsDiagnosticsUiState> = combine(
        libraryCounts,
        appPreferences,
        scanSettingsRepository.settings,
    ) { counts, preferences, scanSettings ->
        SettingsDiagnosticsUiState(
            isLoading = false,
            songCount = counts.songCount,
            albumCount = counts.albumCount,
            artistCount = counts.artistCount,
            playlistCount = counts.playlistCount,
            listenEventCount = counts.listenEventCount,
            themeMode = preferences.themeMode,
            accentColor = preferences.accentColor,
            startupDestination = preferences.startupDestination,
            scanSettings = scanSettings,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsDiagnosticsUiState(isLoading = true),
    )
}

private data class LibraryCounts(
    val songCount: Int,
    val albumCount: Int,
    val artistCount: Int,
    val playlistCount: Int,
    val listenEventCount: Int,
)

private data class AppPreferences(
    val themeMode: ThemeMode,
    val accentColor: AccentColor,
    val startupDestination: StartupDestination,
)

internal fun LibraryScanMode.displayName(): String = when (this) {
    LibraryScanMode.WHOLE_DEVICE -> "Whole device"
    LibraryScanMode.SELECTED_FOLDERS -> "Selected folders"
}
