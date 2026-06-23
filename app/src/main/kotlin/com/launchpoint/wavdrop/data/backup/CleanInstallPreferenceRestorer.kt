package com.launchpoint.wavdrop.data.backup

import com.launchpoint.wavdrop.data.model.MostPlayedDisplayLimit
import com.launchpoint.wavdrop.data.model.MostPlayedPeriod
import com.launchpoint.wavdrop.data.settings.AccentColor
import com.launchpoint.wavdrop.data.settings.AppIconAliasManager
import com.launchpoint.wavdrop.data.settings.AppIconChoice
import com.launchpoint.wavdrop.data.settings.AppSettingsRepository
import com.launchpoint.wavdrop.data.settings.ArtworkCornerStyle
import com.launchpoint.wavdrop.data.settings.AutoBackupInterval
import com.launchpoint.wavdrop.data.settings.BackupFileMode
import com.launchpoint.wavdrop.data.settings.HeadphoneResumeMode
import com.launchpoint.wavdrop.data.settings.HomeLayoutSettingsRepository
import com.launchpoint.wavdrop.data.settings.HomeSectionId
import com.launchpoint.wavdrop.data.settings.LibraryScanMode
import com.launchpoint.wavdrop.data.settings.LibraryScanSettingsRepository
import com.launchpoint.wavdrop.data.settings.NotificationControlsSetting
import com.launchpoint.wavdrop.data.settings.NowPlayingBackground
import com.launchpoint.wavdrop.data.settings.NowPlayingTimeDisplayMode
import com.launchpoint.wavdrop.data.settings.ResumeBehaviorSettingsRepository
import com.launchpoint.wavdrop.data.settings.SearchTapBehavior
import com.launchpoint.wavdrop.data.settings.SongSortMode
import com.launchpoint.wavdrop.data.settings.StartupDestination
import com.launchpoint.wavdrop.data.settings.ThemeMode
import com.launchpoint.wavdrop.data.settings.WrappedBackgroundIntensity
import com.launchpoint.wavdrop.data.settings.WrappedFallbackTheme
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

data class CleanInstallPreferenceRestoreResult(
    val restored: Boolean,
    val needsFolderReselection: Boolean,
    val needsAutoBackupFolderSelection: Boolean,
    val launcherIconRestored: Boolean,
)

@Singleton
class CleanInstallPreferenceRestorer @Inject constructor(
    private val appSettings: AppSettingsRepository,
    private val homeSettings: HomeLayoutSettingsRepository,
    private val scanSettings: LibraryScanSettingsRepository,
    private val resumeSettings: ResumeBehaviorSettingsRepository,
    private val iconManager: AppIconAliasManager,
) {
    suspend fun restore(preferences: BackupPreferences?): CleanInstallPreferenceRestoreResult {
        if (preferences == null) {
            return CleanInstallPreferenceRestoreResult(false, false, false, false)
        }

        preferences.startupDestination.enumValue<StartupDestination>()?.let {
            appSettings.setStartupDestination(it)
        }
        preferences.mostPlayedPeriod.enumValue<MostPlayedPeriod>()?.let {
            appSettings.setMostPlayedPeriod(it)
        }
        preferences.mostPlayedLimit.enumValue<MostPlayedDisplayLimit>()?.let {
            appSettings.setMostPlayedDisplayLimit(it)
        }
        preferences.songSortMode.enumValue<SongSortMode>()?.let { appSettings.setSongSortMode(it) }
        preferences.searchTapBehavior.enumValue<SearchTapBehavior>()?.let { appSettings.setSearchTapBehavior(it) }
        preferences.homeVisibleSections
            ?.mapNotNull { it.enumValue<HomeSectionId>() }
            ?.toSet()
            ?.let { homeSettings.setVisibleSections(it) }

        preferences.minimumTrackDurationSeconds?.let { scanSettings.setMinimumTrackDurationSeconds(it) }
        preferences.includeWhatsAppVoiceNotes?.let { scanSettings.setIncludeWhatsAppVoiceNotes(it) }

        val needsFolders = CleanInstallRecoveryPolicy.requiresFolderReselection(preferences)
        scanSettings.setSelectedFolderUris(emptyList())
        scanSettings.setScanMode(
            if (needsFolders) LibraryScanMode.SELECTED_FOLDERS else LibraryScanMode.WHOLE_DEVICE,
        )
        appSettings.setNeedsFolderReselectionAfterRestore(needsFolders)

        preferences.themeMode.enumValue<ThemeMode>()?.let { appSettings.setThemeMode(it) }
        preferences.accentColor.enumValue<AccentColor>()?.let { appSettings.setAccentColor(it) }
        preferences.compactMode?.let { appSettings.setCompactMode(it) }
        preferences.backupFileMode.enumValue<BackupFileMode>()?.let { appSettings.setBackupFileMode(it) }
        preferences.artworkCornerStyle.enumValue<ArtworkCornerStyle>()?.let { appSettings.setArtworkCornerStyle(it) }
        preferences.showSongThumbnails?.let { appSettings.setShowSongThumbnails(it) }
        preferences.showAlbumInSongRows?.let { appSettings.setShowAlbumInSongRows(it) }
        preferences.nowPlayingBackground.enumValue<NowPlayingBackground>()
            ?.let { appSettings.setNowPlayingBackground(it) }
        preferences.showQueueCount?.let { appSettings.setShowQueueCount(it) }
        preferences.nowPlayingTimeDisplayMode.enumValue<NowPlayingTimeDisplayMode>()
            ?.let { appSettings.setNowPlayingTimeDisplayMode(it) }
        preferences.notificationControls.enumValue<NotificationControlsSetting>()
            ?.let { appSettings.setNotificationControlsSetting(it) }
        preferences.showMilestoneCelebrations?.let { appSettings.setShowMilestoneCelebrations(it) }
        preferences.wrappedUseArtworkBackgrounds?.let { appSettings.setWrappedUseArtworkBackgrounds(it) }
        preferences.wrappedBackgroundIntensity.enumValue<WrappedBackgroundIntensity>()
            ?.let { appSettings.setWrappedBackgroundIntensity(it) }
        preferences.wrappedFallbackTheme.enumValue<WrappedFallbackTheme>()
            ?.let { appSettings.setWrappedFallbackTheme(it) }

        preferences.pauseOnAudioDisconnect?.let { resumeSettings.setPauseOnAudioDisconnect(it) }
        preferences.rememberLastTrack?.let { resumeSettings.setRememberLastTrack(it) }
        preferences.rememberPosition?.let { resumeSettings.setRememberPosition(it) }
        preferences.restoreQueue?.let { resumeSettings.setRestoreQueue(it) }
        preferences.bluetoothResumeMode.enumValue<HeadphoneResumeMode>()
            ?.let { resumeSettings.setBluetoothResumeMode(it) }
        preferences.wiredResumeMode.enumValue<HeadphoneResumeMode>()
            ?.let { resumeSettings.setWiredResumeMode(it) }

        val interval = preferences.autoBackupInterval.enumValue<AutoBackupInterval>()
        if (interval != null) appSettings.setAutoBackupInterval(interval)
        val needsBackupFolder =
            interval != null &&
                interval != AutoBackupInterval.OFF &&
                appSettings.autoBackupFolderUri.first().isNullOrBlank()
        appSettings.setNeedsAutoBackupFolderSelectionAfterRestore(needsBackupFolder)

        val icon = preferences.launcherIcon.enumValue<AppIconChoice>()
        val iconRestored = if (icon != null) {
            appSettings.setAppIconChoice(icon)
            runCatching { iconManager.apply(icon) }.isSuccess
        } else {
            false
        }

        return CleanInstallPreferenceRestoreResult(
            restored = true,
            needsFolderReselection = needsFolders,
            needsAutoBackupFolderSelection = needsBackupFolder,
            launcherIconRestored = iconRestored,
        )
    }
}

private inline fun <reified T : Enum<T>> String?.enumValue(): T? =
    this?.let { runCatching { enumValueOf<T>(it) }.getOrNull() }
