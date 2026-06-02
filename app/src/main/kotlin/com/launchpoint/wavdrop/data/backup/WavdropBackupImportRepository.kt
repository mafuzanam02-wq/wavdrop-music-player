package com.launchpoint.wavdrop.data.backup

import androidx.room.withTransaction
import com.launchpoint.wavdrop.data.legacy.ImportDeltaCalculator
import com.launchpoint.wavdrop.data.legacy.ImportSourceTypes
import com.launchpoint.wavdrop.data.local.WavdropDatabase
import com.launchpoint.wavdrop.data.local.dao.ImportBaselineDao
import com.launchpoint.wavdrop.data.local.dao.SongDao
import com.launchpoint.wavdrop.data.local.dao.TrackStatsDao
import com.launchpoint.wavdrop.data.local.entity.ImportBaselineEntity
import com.launchpoint.wavdrop.data.local.entity.TrackStatsEntity
import com.launchpoint.wavdrop.data.model.Song
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WavdropBackupImportRepository @Inject constructor(
    private val db: WavdropDatabase,
    private val songDao: SongDao,
    private val trackStatsDao: TrackStatsDao,
    private val importBaselineDao: ImportBaselineDao,
) {
    suspend fun applyImport(backup: WavdropBackup): WavdropBackupImportApplyResult =
        db.withTransaction {
            val currentSongs = songDao.getAllSongsSnapshot().map { e ->
                Song(
                    id          = e.id,
                    title       = e.title,
                    artist      = e.artist,
                    album       = e.album,
                    albumId     = e.albumId,
                    duration    = e.duration,
                    uri         = e.uri,
                    dateAdded   = e.dateAdded,
                    trackNumber = e.trackNumber,
                    year        = e.year,
                )
            }

            val match      = WavdropBackupStatsMatcher.match(backup, currentSongs)
            val importedAt = System.currentTimeMillis()
            var playsAdded = 0L
            var skipsAdded = 0L

            for ((song, backupStats) in match.matchedRows) {
                val sourceKey = "uri:${song.uri}"

                val baseline = importBaselineDao.getBaseline(
                    songId     = song.id,
                    sourceType = ImportSourceTypes.WAVDROP_JSON,
                    sourceKey  = sourceKey,
                )

                val delta = ImportDeltaCalculator.calculate(
                    previousPlayCount = baseline?.lastImportedPlayCount ?: 0,
                    previousSkipCount = baseline?.lastImportedSkipCount ?: 0,
                    incomingPlayCount = backupStats.playCount,
                    incomingSkipCount = backupStats.skipCount,
                )

                if (delta.hasNewStats) {
                    trackStatsDao.insertIfAbsent(
                        TrackStatsEntity(songId = song.id, contentUri = song.uri)
                    )
                    trackStatsDao.mergeImportedStats(
                        songId               = song.id,
                        addPlays             = delta.playDelta,
                        addSkips             = delta.skipDelta,
                        importedLastPlayedAt = backupStats.lastPlayedAt,
                    )
                    playsAdded += delta.playDelta
                    skipsAdded += delta.skipDelta
                }

                // Always update baseline so re-importing doesn't double-count.
                importBaselineDao.upsertBaseline(
                    ImportBaselineEntity(
                        songId                = song.id,
                        sourceType            = ImportSourceTypes.WAVDROP_JSON,
                        sourceKey             = sourceKey,
                        lastImportedPlayCount = delta.nextBaselinePlayCount,
                        lastImportedSkipCount = delta.nextBaselineSkipCount,
                        lastImportedAt        = importedAt,
                    )
                )
            }

            WavdropBackupImportApplyResult(
                matchedTracks   = match.matchedRows.size,
                unmatchedTracks = match.unmatchedCount,
                playsAdded      = playsAdded,
                skipsAdded      = skipsAdded,
            )
        }
}
