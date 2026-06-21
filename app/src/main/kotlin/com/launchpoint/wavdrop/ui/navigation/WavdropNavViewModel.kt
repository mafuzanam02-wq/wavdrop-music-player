package com.launchpoint.wavdrop.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.launchpoint.wavdrop.BuildConfig
import com.launchpoint.wavdrop.data.backup.AutoBackupRepository
import com.launchpoint.wavdrop.data.playback.PlaybackSessionRepository
import com.launchpoint.wavdrop.data.settings.AppSettingsRepository
import com.launchpoint.wavdrop.data.settings.ArtworkCornerStyle
import com.launchpoint.wavdrop.data.settings.NowPlayingBackground
import com.launchpoint.wavdrop.data.settings.NowPlayingTimeDisplayMode
import com.launchpoint.wavdrop.data.settings.StartupDestination
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class WavdropNavViewModel @Inject constructor(
    private val appSettingsRepository: AppSettingsRepository,
    private val autoBackupRepository: AutoBackupRepository,
    private val playbackSessionRepository: PlaybackSessionRepository,
) : ViewModel() {

    // Guard: run at most once per process lifetime, regardless of recompositions.
    private var autoBackupCheckedThisSession = false

    /**
     * Checks whether an auto-backup is due and performs it in the background.
     * Safe to call from a LaunchedEffect — it is a no-op after the first invocation.
     */
    fun triggerAutoBackupIfDue() {
        if (autoBackupCheckedThisSession) return
        autoBackupCheckedThisSession = true
        viewModelScope.launch {
            autoBackupRepository.runIfDue()
        }
    }

    val startupDestination: StateFlow<StartupDestination?> =
        appSettingsRepository.startupDestination
            .map<StartupDestination, StartupDestination?> { it }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = null,
            )

    // Null until the check completes; false means no persisted session (queue was empty).
    val hasPlaybackSession: StateFlow<Boolean?> =
        flow { emit(playbackSessionRepository.load() != null) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = null,
            )

    val compactMode: StateFlow<Boolean> =
        appSettingsRepository.compactMode
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = false,
            )

    val showSongThumbnails: StateFlow<Boolean> =
        appSettingsRepository.showSongThumbnails
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = true,
            )

    val showAlbumInSongRows: StateFlow<Boolean> =
        appSettingsRepository.showAlbumInSongRows
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = false,
            )

    val artworkCornerStyle: StateFlow<ArtworkCornerStyle> =
        appSettingsRepository.artworkCornerStyle
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = ArtworkCornerStyle.ROUNDED,
            )

    val nowPlayingBackground: StateFlow<NowPlayingBackground> =
        appSettingsRepository.nowPlayingBackground
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = NowPlayingBackground.ARTWORK,
            )

    val showQueueCount: StateFlow<Boolean> =
        appSettingsRepository.showQueueCount
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = true,
            )

    val nowPlayingTimeDisplayMode: StateFlow<NowPlayingTimeDisplayMode> =
        appSettingsRepository.nowPlayingTimeDisplayMode
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = NowPlayingTimeDisplayMode.DURATION,
            )

    // Show onboarding when lastCompletedOnboardingVersion < CURRENT_ONBOARDING_VERSION.
    // Migration: existing users with the old boolean flag are treated as version 1 and are
    // never shown onboarding again unless CURRENT_ONBOARDING_VERSION is deliberately bumped.
    val hasCompletedOnboarding: StateFlow<Boolean?> =
        appSettingsRepository.lastCompletedOnboardingVersion
            .map<Int, Boolean?> { version -> version >= CURRENT_ONBOARDING_VERSION }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = null,
            )

    val showChangelog: StateFlow<Boolean> =
        combine(
            appSettingsRepository.hasCompletedOnboarding,
            appSettingsRepository.lastSeenChangelogVersion,
        ) { completed, lastSeen ->
            WhatsNewVersionRules.shouldShow(
                hasCompletedOnboarding = completed,
                lastSeenVersionCode = lastSeen,
                currentVersionCode = BuildConfig.VERSION_CODE,
            )
        }.stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = false,
        )

    fun completeOnboarding() {
        viewModelScope.launch {
            appSettingsRepository.setLastCompletedOnboardingVersion(CURRENT_ONBOARDING_VERSION)
        }
    }

    fun dismissChangelog() {
        viewModelScope.launch {
            appSettingsRepository.setLastSeenChangelogVersion(BuildConfig.VERSION_CODE)
        }
    }

    companion object {
        // Increment this only when onboarding content changes enough to warrant showing it
        // again to existing users. Keep at 1 for routine app updates.
        const val CURRENT_ONBOARDING_VERSION = 1
    }
}
