package com.launchpoint.wavdrop.data.backup

import android.content.Context
import android.net.Uri
import com.launchpoint.wavdrop.BuildConfig
import com.launchpoint.wavdrop.data.local.dao.ImportBaselineDao
import com.launchpoint.wavdrop.data.local.dao.LyricsOverrideDao
import com.launchpoint.wavdrop.data.local.dao.PlaylistDao
import com.launchpoint.wavdrop.data.local.dao.SongDao
import com.launchpoint.wavdrop.data.local.dao.TrackListenEventDao
import com.launchpoint.wavdrop.data.local.dao.TrackStatsDao
import com.launchpoint.wavdrop.data.local.entity.ImportBaselineEntity
import com.launchpoint.wavdrop.data.local.entity.LyricsOverrideEntity
import com.launchpoint.wavdrop.data.local.entity.SongEntity
import com.launchpoint.wavdrop.data.local.entity.TrackStatsEntity
import com.launchpoint.wavdrop.data.model.MostPlayedDisplayLimit
import com.launchpoint.wavdrop.data.model.MostPlayedPeriod
import com.launchpoint.wavdrop.data.settings.AccentColor
import com.launchpoint.wavdrop.data.settings.AppIconChoice
import com.launchpoint.wavdrop.data.settings.AppSettingsRepository
import com.launchpoint.wavdrop.data.settings.ArtworkCornerStyle
import com.launchpoint.wavdrop.data.settings.AutoBackupInterval
import com.launchpoint.wavdrop.data.settings.BackupFileMode
import com.launchpoint.wavdrop.data.settings.HeadphoneResumeMode
import com.launchpoint.wavdrop.data.settings.HomeLayoutSettings
import com.launchpoint.wavdrop.data.settings.HomeLayoutSettingsRepository
import com.launchpoint.wavdrop.data.settings.LibraryScanMode
import com.launchpoint.wavdrop.data.settings.LibraryScanSettingsRepository
import com.launchpoint.wavdrop.data.settings.LibraryScanSettingsRules
import com.launchpoint.wavdrop.data.settings.NotificationControlsSetting
import com.launchpoint.wavdrop.data.settings.NowPlayingBackground
import com.launchpoint.wavdrop.data.settings.NowPlayingTimeDisplayMode
import com.launchpoint.wavdrop.data.settings.ResumeBehaviorSettingsRepository
import com.launchpoint.wavdrop.data.settings.StartupDestination
import com.launchpoint.wavdrop.data.settings.ThemeMode
import kotlinx.coroutines.flow.first
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class WavdropBackupRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val songDao: SongDao,
    private val trackStatsDao: TrackStatsDao,
    private val importBaselineDao: ImportBaselineDao,
    private val lyricsOverrideDao: LyricsOverrideDao,
    private val playlistDao: PlaylistDao,
    private val trackListenEventDao: TrackListenEventDao,
    private val appSettingsRepository: AppSettingsRepository,
    private val homeLayoutSettingsRepository: HomeLayoutSettingsRepository,
    private val libraryScanSettingsRepository: LibraryScanSettingsRepository,
    private val resumeBehaviorSettingsRepository: ResumeBehaviorSettingsRepository,
) {
    suspend fun exportToUri(uri: Uri) = withContext(Dispatchers.IO) {
        val json = buildBackupJson()
        // "wt" truncates before writing. The default "w" mode does not truncate on all
        // providers, so overwriting a larger existing file would leave trailing garbage
        // after the JSON and corrupt the export. Fall back to "w" only for providers
        // that reject "wt".
        val wrote = tryWrite(uri, json, "wt") ?: tryWrite(uri, json, "w")
        if (wrote == null) {
            throw IOException("Could not save the backup file. Try a different location.")
        }
        val readBack = readBackupContent(uri)
        if (!BackupSaveValidator.isSavedBackupValid(readBack)) {
            throw IOException(BackupSaveValidator.VALIDATION_FAILED_MESSAGE)
        }
    }

    private fun tryWrite(uri: Uri, json: String, mode: String): Unit? = runCatching {
        context.contentResolver.openOutputStream(uri, mode)?.use { stream ->
            stream.write(json.toByteArray(Charsets.UTF_8))
        }
    }.getOrNull()

    private fun readBackupContent(uri: Uri): String? = runCatching {
        context.contentResolver.openInputStream(uri)
            ?.bufferedReader()
            ?.use { it.readText() }
    }.getOrNull()

    /** Collects all backup data from the database and returns the serialised JSON string. */
    suspend fun buildBackupJson(): String = withContext(Dispatchers.IO) {
        val songs      = songDao.getAllSongsSnapshot()
        val stats      = trackStatsDao.getAllStatsSnapshot()
        val baselines  = importBaselineDao.getAllImportBaselinesSnapshot()
        val lyrics     = lyricsOverrideDao.getAllSnapshot()

        val songById = songs.associateBy { it.id }

        val listenEvents = trackListenEventDao.getAllSnapshot()
            .filter { BackupEventExportRules.shouldExport(it.source) }
            .map { event ->
                val song = songById[event.songId]
                BackupListenEvent(
                    songId     = event.songId,
                    contentUri = song?.uri ?: "",
                    title      = song?.title ?: "",
                    artist     = song?.artist ?: "",
                    album      = song?.album ?: "",
                    eventType  = event.eventType,
                    occurredAt = event.occurredAt,
                    listenedMs = event.listenedMs,
                    durationMs = event.durationMs,
                    source     = event.source,
                )
            }
        val playlists = playlistDao.getAllPlaylistsSnapshot().map { playlist ->
            BackupPlaylist(
                id        = playlist.playlistId,
                name      = playlist.name,
                createdAt = playlist.createdAt,
                updatedAt = playlist.updatedAt,
                songs     = playlistDao.getSongsForPlaylistSnapshot(playlist.playlistId)
                    .sortedBy { it.position }
                    .mapNotNull { ps ->
                        val song = songById[ps.songId] ?: return@mapNotNull null
                        BackupPlaylistSong(
                            songId     = ps.songId,
                            contentUri = song.uri,
                            position   = ps.position,
                            title      = song.title,
                            artist     = song.artist,
                            album      = song.album,
                        )
                    },
            )
        }

        val scanSettings   = libraryScanSettingsRepository.settings.first()
        val resumeSettings = resumeBehaviorSettingsRepository.settings.first()
        val homeSections   = homeLayoutSettingsRepository.settings.first().visibleSections

        // Export rule: only settings that differ from their default are written.
        // A null field means "was default at export time"; restore leaves the
        // default in place, so fresh installs naturally end up identical.
        val preferences = BackupPreferences(
            startupDestination = appSettingsRepository.startupDestination.first()
                .takeIf { it != StartupDestination.SONGS }?.name,
            mostPlayedPeriod = appSettingsRepository.mostPlayedPeriod.first()
                .takeIf { it != MostPlayedPeriod.ALL_TIME }?.name,
            mostPlayedLimit = appSettingsRepository.mostPlayedDisplayLimit.first()
                .takeIf { it != MostPlayedDisplayLimit.TOP_25 }?.name,
            homeVisibleSections = homeSections
                .takeIf { it != HomeLayoutSettings().visibleSections }
                ?.map { it.name },
            scanMode = scanSettings.scanMode
                .takeIf { it != LibraryScanMode.WHOLE_DEVICE }?.name,
            selectedFolderUris = scanSettings.selectedFolderUris.takeIf { it.isNotEmpty() },
            minimumTrackDurationSeconds = scanSettings.minimumTrackDurationSeconds
                .takeIf { it != LibraryScanSettingsRules.DEFAULT_MINIMUM_TRACK_DURATION_SECONDS },
            themeMode = appSettingsRepository.themeMode.first()
                .takeIf { it != ThemeMode.SYSTEM }?.name,
            accentColor = appSettingsRepository.accentColor.first()
                .takeIf { it != AccentColor.MIDNIGHT_VIOLET }?.name,
            launcherIcon = appSettingsRepository.appIconChoice.first()
                .takeIf { it != AppIconChoice.DEFAULT }?.name,
            compactMode = appSettingsRepository.compactMode.first().takeIf { it },
            backupFileMode = appSettingsRepository.backupFileMode.first()
                .takeIf { it != BackupFileMode.DATED }?.name,
            autoBackupInterval = appSettingsRepository.autoBackupInterval.first()
                .takeIf { it != AutoBackupInterval.OFF }?.name,
            artworkCornerStyle = appSettingsRepository.artworkCornerStyle.first()
                .takeIf { it != ArtworkCornerStyle.ROUNDED }?.name,
            showSongThumbnails = appSettingsRepository.showSongThumbnails.first()
                .takeIf { !it }, // default true — export only when turned off
            showAlbumInSongRows = appSettingsRepository.showAlbumInSongRows.first()
                .takeIf { it },  // default false — export only when turned on
            nowPlayingBackground = appSettingsRepository.nowPlayingBackground.first()
                .takeIf { it != NowPlayingBackground.ARTWORK }?.name,
            showQueueCount = appSettingsRepository.showQueueCount.first()
                .takeIf { !it }, // default true
            nowPlayingTimeDisplayMode = appSettingsRepository.nowPlayingTimeDisplayMode.first()
                .takeIf { it != NowPlayingTimeDisplayMode.DURATION }?.name,
            notificationControls = appSettingsRepository.notificationControlsSetting.first()
                .takeIf { it != NotificationControlsSetting.STANDARD }?.name,
            includeWhatsAppVoiceNotes = scanSettings.includeWhatsAppVoiceNotes
                .takeIf { it },  // default false
            pauseOnAudioDisconnect = resumeSettings.pauseOnAudioDisconnect
                .takeIf { !it }, // default true
            rememberLastTrack = resumeSettings.rememberLastTrack.takeIf { !it }, // default true
            rememberPosition  = resumeSettings.rememberPosition.takeIf { !it },  // default true
            restoreQueue      = resumeSettings.restoreQueue.takeIf { !it },      // default true
            bluetoothResumeMode = resumeSettings.bluetoothResumeMode
                .takeIf { it != HeadphoneResumeMode.RESUME_IF_INTERRUPTED }?.name,
            wiredResumeMode = resumeSettings.wiredResumeMode
                .takeIf { it != HeadphoneResumeMode.RESUME_IF_INTERRUPTED }?.name,
        )

        val backup = WavdropBackup(
            exportedAt      = Instant.now().toString(),
            appVersionCode  = BuildConfig.VERSION_CODE,
            appVersionName  = BuildConfig.VERSION_NAME,
            songs           = songs.map(SongEntity::toBackup),
            trackStats      = stats.map(TrackStatsEntity::toBackup),
            importBaselines = baselines.map(ImportBaselineEntity::toBackup),
            lyricsOverrides = lyrics.map(LyricsOverrideEntity::toBackup),
            preferences     = preferences,
            playlists       = playlists,
            listenEvents    = listenEvents,
        )

        WavdropBackupExporter.toJson(backup)
    }
}

// Entity to backup model mappings.

private fun SongEntity.toBackup() = BackupSong(
    id          = id,
    uri         = uri,
    title       = title,
    artist      = artist,
    album       = album,
    albumId     = albumId,
    duration    = duration,
    dateAdded   = dateAdded,
    trackNumber = trackNumber,
    year        = year,
    folderPath  = folderPath,
    folderName  = folderName,
)

private fun TrackStatsEntity.toBackup() = BackupTrackStats(
    songId               = songId,
    contentUri           = contentUri,
    playCount            = playCount,
    skipCount            = skipCount,
    lastPlayedAt         = lastPlayedAt,
    totalListeningTimeMs = totalListeningTimeMs,
    isFavorite           = isFavorite,
)

private fun ImportBaselineEntity.toBackup() = BackupImportBaseline(
    songId                = songId,
    sourceType            = sourceType,
    sourceKey             = sourceKey,
    lastImportedPlayCount = lastImportedPlayCount,
    lastImportedSkipCount = lastImportedSkipCount,
    lastImportedAt        = lastImportedAt,
)

private fun LyricsOverrideEntity.toBackup() = BackupLyricsOverride(
    songId     = songId,
    contentUri = contentUri,
    lyrics     = lyrics,
    updatedAt  = updatedAt,
)
