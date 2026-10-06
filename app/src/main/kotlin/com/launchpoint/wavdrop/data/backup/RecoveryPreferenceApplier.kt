package com.launchpoint.wavdrop.data.backup

import com.launchpoint.wavdrop.data.model.MostPlayedDisplayLimit
import com.launchpoint.wavdrop.data.model.MostPlayedPeriod
import com.launchpoint.wavdrop.data.settings.AccentColor
import com.launchpoint.wavdrop.data.settings.AppIconChoice
import com.launchpoint.wavdrop.data.settings.ArtworkCornerStyle
import com.launchpoint.wavdrop.data.settings.AutoBackupInterval
import com.launchpoint.wavdrop.data.settings.BackupFileMode
import com.launchpoint.wavdrop.data.settings.HeadphoneResumeMode
import com.launchpoint.wavdrop.data.settings.HomeLayoutSettings
import com.launchpoint.wavdrop.data.settings.LibraryScanSettingsRules
import com.launchpoint.wavdrop.data.settings.NotificationControlsSetting
import com.launchpoint.wavdrop.data.settings.NowPlayingBackground
import com.launchpoint.wavdrop.data.settings.NowPlayingTimeDisplayMode
import com.launchpoint.wavdrop.data.settings.SearchTapBehavior
import com.launchpoint.wavdrop.data.settings.SongSortMode
import com.launchpoint.wavdrop.data.settings.StartupDestination
import com.launchpoint.wavdrop.data.settings.ThemeMode
import com.launchpoint.wavdrop.data.settings.WrappedBackgroundIntensity
import com.launchpoint.wavdrop.data.settings.WrappedFallbackTheme
import com.launchpoint.wavdrop.data.settings.WrappedVisualStyle
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/**
 * The backup writes only settings that differ from their default; a null field therefore means "was default at export time".
 * For a MERGE or a fresh install, leaving that field alone is correct. For an authoritative Recovery on a device whose settings
 * have since changed, leaving it alone would keep the device's non-default value, so Recovery resolves every null to its default
 * first. The defaults are the exact complements of the exporter's `takeIf` rules (see WavdropBackupRepository.buildBackupJson).
 *
 * Deliberately NOT resolved (device-specific, never restored by Recovery): [BackupPreferences.scanMode] and
 * [BackupPreferences.selectedFolderUris] (SAF grants are per-device permission state).
 */
object RecoveryPreferenceDefaults {

    fun resolve(p: BackupPreferences): BackupPreferences = p.copy(
        startupDestination = p.startupDestination ?: StartupDestination.SONGS.name,
        mostPlayedPeriod = p.mostPlayedPeriod ?: MostPlayedPeriod.ALL_TIME.name,
        mostPlayedLimit = p.mostPlayedLimit ?: MostPlayedDisplayLimit.TOP_25.name,
        songSortMode = p.songSortMode ?: SongSortMode.DEFAULT.name,
        searchTapBehavior = p.searchTapBehavior ?: SearchTapBehavior.DEFAULT.name,
        homeVisibleSections = p.homeVisibleSections ?: HomeLayoutSettings().visibleSections.map { it.name },
        minimumTrackDurationSeconds = p.minimumTrackDurationSeconds
            ?: LibraryScanSettingsRules.DEFAULT_MINIMUM_TRACK_DURATION_SECONDS,
        themeMode = p.themeMode ?: ThemeMode.SYSTEM.name,
        accentColor = p.accentColor ?: AccentColor.MIDNIGHT_VIOLET.name,
        launcherIcon = p.launcherIcon ?: AppIconChoice.DEFAULT.name,
        compactMode = p.compactMode ?: false,
        backupFileMode = p.backupFileMode ?: BackupFileMode.DATED.name,
        autoBackupInterval = p.autoBackupInterval ?: AutoBackupInterval.OFF.name,
        artworkCornerStyle = p.artworkCornerStyle ?: ArtworkCornerStyle.ROUNDED.name,
        showSongThumbnails = p.showSongThumbnails ?: true,
        showAlbumInSongRows = p.showAlbumInSongRows ?: false,
        nowPlayingBackground = p.nowPlayingBackground ?: NowPlayingBackground.ARTWORK.name,
        showQueueCount = p.showQueueCount ?: true,
        nowPlayingTimeDisplayMode = p.nowPlayingTimeDisplayMode ?: NowPlayingTimeDisplayMode.DURATION.name,
        notificationControls = p.notificationControls ?: NotificationControlsSetting.STANDARD_SHUFFLE_REPEAT.name,
        includeWhatsAppVoiceNotes = p.includeWhatsAppVoiceNotes ?: false,
        pauseOnAudioDisconnect = p.pauseOnAudioDisconnect ?: true,
        rememberLastTrack = p.rememberLastTrack ?: true,
        rememberPosition = p.rememberPosition ?: true,
        restoreQueue = p.restoreQueue ?: true,
        bluetoothResumeMode = p.bluetoothResumeMode ?: HeadphoneResumeMode.RESUME_IF_INTERRUPTED.name,
        wiredResumeMode = p.wiredResumeMode ?: HeadphoneResumeMode.RESUME_IF_INTERRUPTED.name,
        showMilestoneCelebrations = p.showMilestoneCelebrations ?: true,
        wrappedUseArtworkBackgrounds = p.wrappedUseArtworkBackgrounds ?: true,
        wrappedBackgroundIntensity = p.wrappedBackgroundIntensity ?: WrappedBackgroundIntensity.MEDIUM.name,
        wrappedFallbackTheme = p.wrappedFallbackTheme ?: WrappedFallbackTheme.AUTO.name,
        wrappedVisualStyle = p.wrappedVisualStyle ?: WrappedVisualStyle.DEFAULT.name,
        // Device-specific permission state: never carried by Recovery.
        scanMode = null,
        selectedFolderUris = null,
    )
}

/** Outcome of the DataStore half of Recovery. DataStore cannot join the Room transaction, so it is reported separately. */
sealed interface RecoveryPreferenceResult {
    /** The backup carried no preferences section; local settings were left alone. */
    data object NotIncluded : RecoveryPreferenceResult

    data class Applied(
        val needsAutoBackupFolderSelection: Boolean,
        val launcherIconRestored: Boolean,
    ) : RecoveryPreferenceResult

    data class Failed(val detail: String) : RecoveryPreferenceResult
}

interface RecoveryPreferenceApplier {
    /** Never throws (except cancellation): failure is a [RecoveryPreferenceResult.Failed]. */
    suspend fun applyRecoveryPreferences(preferences: BackupPreferences?): RecoveryPreferenceResult
}

/**
 * Applies the supported backup preferences after the database Recovery committed. Reuses the clean-install restorer with
 * [CleanInstallPreferenceRestorer.FolderPolicy.KEEP_DEVICE_FOLDERS] so SAF folder grants and scan mode stay as they are on this
 * device, then re-reconciles the automatic-backup schedule. If the backup restores an automatic-backup interval but this device
 * has no backup folder, the existing "Choose backup folder" prompt flag is set.
 */
@Singleton
class RecoveryPreferenceApplierImpl @Inject constructor(
    private val restorer: CleanInstallPreferenceRestorer,
    private val autoBackupWorkScheduler: AutoBackupWorkScheduler,
) : RecoveryPreferenceApplier {

    override suspend fun applyRecoveryPreferences(preferences: BackupPreferences?): RecoveryPreferenceResult {
        if (preferences == null) return RecoveryPreferenceResult.NotIncluded
        return try {
            val r = restorer.restore(
                RecoveryPreferenceDefaults.resolve(preferences),
                CleanInstallPreferenceRestorer.FolderPolicy.KEEP_DEVICE_FOLDERS,
            )
            autoBackupWorkScheduler.reconcile()
            RecoveryPreferenceResult.Applied(
                needsAutoBackupFolderSelection = r.needsAutoBackupFolderSelection,
                launcherIconRestored = r.launcherIconRestored,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RecoveryPreferenceResult.Failed(e.message ?: "settings could not be applied")
        }
    }
}
