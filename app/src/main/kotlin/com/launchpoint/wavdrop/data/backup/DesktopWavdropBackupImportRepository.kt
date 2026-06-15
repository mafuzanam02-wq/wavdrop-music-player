package com.launchpoint.wavdrop.data.backup

import androidx.room.withTransaction
import com.launchpoint.wavdrop.data.local.WavdropDatabase
import com.launchpoint.wavdrop.data.local.dao.PlaylistDao
import com.launchpoint.wavdrop.data.local.dao.SongDao
import com.launchpoint.wavdrop.data.local.dao.TrackStatsDao
import com.launchpoint.wavdrop.data.local.entity.PlaylistEntity
import com.launchpoint.wavdrop.data.local.entity.PlaylistSongEntity
import com.launchpoint.wavdrop.data.local.entity.TrackStatsEntity
import com.launchpoint.wavdrop.data.model.Song
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DesktopWavdropBackupImportRepository @Inject constructor(
    private val db: WavdropDatabase,
    private val songDao: SongDao,
    private val trackStatsDao: TrackStatsDao,
    private val playlistDao: PlaylistDao,
) {
    suspend fun previewImport(backup: DesktopWavdropBackup): DesktopBackupImportPlan =
        DesktopWavdropBackupImportPlanner.plan(
            backup = backup,
            currentSongs = currentSongs(),
            currentStats = trackStatsDao.getAllStatsSnapshot(),
        )

    suspend fun applyImport(backup: DesktopWavdropBackup): WavdropBackupImportApplyResult =
        db.withTransaction {
            val plan = DesktopWavdropBackupImportPlanner.plan(
                backup = backup,
                currentSongs = currentSongs(),
                currentStats = trackStatsDao.getAllStatsSnapshot(),
            )
            val importedAt = System.currentTimeMillis()

            for (row in plan.matchedRows) {
                trackStatsDao.insertIfAbsent(
                    TrackStatsEntity(songId = row.song.id, contentUri = row.song.uri),
                )
                trackStatsDao.mergeMaxStats(
                    songId = row.song.id,
                    importedPlayCount = row.desktopSong.playCount,
                    importedSkipCount = row.mergedStats.skipCount,
                    importedListeningTimeMs = row.desktopSong.totalListeningTimeMs,
                    importedLastPlayedAt = row.desktopSong.lastPlayedAt,
                )
                if (row.desktopSong.favorite) {
                    trackStatsDao.setFavorite(row.song.id, true)
                }
            }

            var playlistsRestored = 0
            var playlistSongsRestored = 0

            for (playlistPlan in plan.playlistPlans) {
                val playlistId = playlistDao.findByName(playlistPlan.name)?.playlistId
                    ?: run {
                        playlistsRestored++
                        playlistDao.insertPlaylist(
                            PlaylistEntity(
                                name      = playlistPlan.name,
                                createdAt = importedAt,
                                updatedAt = importedAt,
                            ),
                        )
                    }

                val existingSongIds = playlistDao.getSongsForPlaylistSnapshot(playlistId)
                    .map { it.songId }
                    .toSet()
                var nextPosition = playlistDao.getMaxPosition(playlistId) + 1
                var addedCount = 0

                for (songId in playlistPlan.resolvedSongIds) {
                    if (songId in existingSongIds) continue
                    playlistDao.insertSong(
                        PlaylistSongEntity(
                            playlistId = playlistId,
                            songId     = songId,
                            position   = nextPosition++,
                        ),
                    )
                    addedCount++
                    playlistSongsRestored++
                }

                if (addedCount > 0) {
                    playlistDao.touchPlaylist(playlistId, importedAt)
                }
            }

            val favoritesInBackup = plan.matchedRows.count { it.desktopSong.favorite } +
                plan.unmatchedSongs.count { it.favorite } +
                plan.ambiguousSongs.count { it.favorite }
            val favoritesUnmatched = plan.unmatchedSongs.count { it.favorite } +
                plan.ambiguousSongs.count { it.favorite }

            WavdropBackupImportApplyResult(
                matchedTracks            = plan.matchedCount,
                unmatchedTracks          = plan.unmatchedCount,
                ambiguousTracks          = plan.ambiguousCount,
                statsUpdated             = plan.statsWillIncreaseCount,
                favoritesRestored        = plan.favoritesWillApplyCount,
                favoritesInBackup        = favoritesInBackup,
                favoritesUnmatched       = favoritesUnmatched,
                playlistsRestored        = playlistsRestored,
                playlistsInBackup        = plan.playlistsInBackup,
                playlistSongsRestored    = playlistSongsRestored,
                playlistEntriesInBackup  = plan.playlistEntriesInBackup,
                playlistEntriesUnmatched = plan.playlistSongsSkippedCount,
                eventsRestored           = 0,
                eventsSkipped            = 0,
                eventsSkippedDuplicate   = 0,
                eventsSkippedUnmatched   = 0,
                dataRestored             = plan.matchedRows.isNotEmpty() || playlistsRestored > 0,
                warnings                 = listOf(
                    "Desktop song IDs are not Android song IDs. Songs were matched by metadata.",
                ),
            )
        }

    private suspend fun currentSongs(): List<Song> =
        songDao.getAllSongsSnapshot().map { e ->
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
                folderPath  = e.folderPath,
                folderName  = e.folderName,
            )
        }
}
