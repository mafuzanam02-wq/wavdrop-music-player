package com.launchpoint.wavdrop.data.backup

import android.content.Context
import android.net.Uri
import com.launchpoint.wavdrop.data.local.dao.ImportBaselineDao
import com.launchpoint.wavdrop.data.local.dao.LyricsOverrideDao
import com.launchpoint.wavdrop.data.local.dao.SongDao
import com.launchpoint.wavdrop.data.local.dao.TrackStatsDao
import com.launchpoint.wavdrop.data.local.entity.ImportBaselineEntity
import com.launchpoint.wavdrop.data.local.entity.LyricsOverrideEntity
import com.launchpoint.wavdrop.data.local.entity.SongEntity
import com.launchpoint.wavdrop.data.local.entity.TrackStatsEntity
import com.launchpoint.wavdrop.data.settings.AppSettingsRepository
import com.launchpoint.wavdrop.data.settings.HomeLayoutSettingsRepository
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
    private val appSettingsRepository: AppSettingsRepository,
    private val homeLayoutSettingsRepository: HomeLayoutSettingsRepository,
) {
    suspend fun exportToUri(uri: Uri) = withContext(Dispatchers.IO) {
        val songs      = songDao.getAllSongsSnapshot()
        val stats      = trackStatsDao.getAllStatsSnapshot()
        val baselines  = importBaselineDao.getAllImportBaselinesSnapshot()
        val lyrics     = lyricsOverrideDao.getAllSnapshot()

        val preferences = BackupPreferences(
            startupDestination  = appSettingsRepository.startupDestination.first().name,
            mostPlayedPeriod    = appSettingsRepository.mostPlayedPeriod.first().name,
            mostPlayedLimit     = appSettingsRepository.mostPlayedDisplayLimit.first().name,
            homeVisibleSections = homeLayoutSettingsRepository.settings.first()
                .visibleSections.map { it.name },
        )

        val backup = WavdropBackup(
            exportedAt      = Instant.now().toString(),
            songs           = songs.map(SongEntity::toBackup),
            trackStats      = stats.map(TrackStatsEntity::toBackup),
            importBaselines = baselines.map(ImportBaselineEntity::toBackup),
            lyricsOverrides = lyrics.map(LyricsOverrideEntity::toBackup),
            preferences     = preferences,
        )

        val json = WavdropBackupExporter.toJson(backup)

        context.contentResolver.openOutputStream(uri)?.use { stream ->
            stream.write(json.toByteArray(Charsets.UTF_8))
        } ?: throw IOException("Could not open output stream for the selected file.")
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
