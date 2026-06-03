package com.launchpoint.wavdrop.data.backup

import androidx.room.withTransaction
import com.launchpoint.wavdrop.data.legacy.ImportDeltaCalculator
import com.launchpoint.wavdrop.data.legacy.ImportSourceTypes
import com.launchpoint.wavdrop.data.local.WavdropDatabase
import com.launchpoint.wavdrop.data.local.dao.ImportBaselineDao
import com.launchpoint.wavdrop.data.local.dao.LyricsOverrideDao
import com.launchpoint.wavdrop.data.local.dao.PlaylistDao
import com.launchpoint.wavdrop.data.local.dao.SongDao
import com.launchpoint.wavdrop.data.local.dao.TrackStatsDao
import com.launchpoint.wavdrop.data.local.entity.ImportBaselineEntity
import com.launchpoint.wavdrop.data.local.entity.LyricsOverrideEntity
import com.launchpoint.wavdrop.data.local.entity.PlaylistEntity
import com.launchpoint.wavdrop.data.local.entity.PlaylistSongEntity
import com.launchpoint.wavdrop.data.local.entity.TrackStatsEntity
import com.launchpoint.wavdrop.data.model.MostPlayedDisplayLimit
import com.launchpoint.wavdrop.data.model.MostPlayedPeriod
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.settings.AppSettingsRepository
import com.launchpoint.wavdrop.data.settings.HomeSectionId
import com.launchpoint.wavdrop.data.settings.HomeLayoutSettingsRepository
import com.launchpoint.wavdrop.data.settings.StartupDestination
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WavdropBackupImportRepository @Inject constructor(
    private val db: WavdropDatabase,
    private val songDao: SongDao,
    private val trackStatsDao: TrackStatsDao,
    private val importBaselineDao: ImportBaselineDao,
    private val lyricsOverrideDao: LyricsOverrideDao,
    private val playlistDao: PlaylistDao,
    private val appSettingsRepository: AppSettingsRepository,
    private val homeLayoutSettingsRepository: HomeLayoutSettingsRepository,
) {
    suspend fun applyImport(backup: WavdropBackup): WavdropBackupImportApplyResult {
        val dbResult = db.withTransaction {
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

            val match            = WavdropBackupStatsMatcher.match(backup, currentSongs)
            val importedAt       = System.currentTimeMillis()
            var playsAdded       = 0L
            var skipsAdded       = 0L
            var favoritesRestored = 0

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

                // Restore favorite: only set true, never clear a local favorite.
                if (backupStats.isFavorite) {
                    trackStatsDao.insertIfAbsent(
                        TrackStatsEntity(songId = song.id, contentUri = song.uri)
                    )
                    trackStatsDao.setFavorite(song.id, true)
                    favoritesRestored++
                }
            }

            // Lyrics overrides restore
            val backupSongById = backup.songs.associateBy { it.id }
            val byUri  = currentSongs.associateBy { it.uri }
            val byTags = currentSongs.associateBy {
                Triple(it.title.norm(), it.artist.norm(), it.album.norm())
            }
            var lyricsRestored = 0

            for (override in backup.lyricsOverrides) {
                val song = byUri[override.contentUri]
                    ?: run {
                        val backupSong = backupSongById[override.songId] ?: return@run null
                        byTags[Triple(
                            backupSong.title.norm(),
                            backupSong.artist.norm(),
                            backupSong.album.norm(),
                        )]
                    } ?: continue

                val existing = lyricsOverrideDao.getForSong(song.id, song.uri)
                if (existing == null || override.updatedAt > existing.updatedAt) {
                    lyricsOverrideDao.upsert(
                        LyricsOverrideEntity(
                            songId     = song.id,
                            contentUri = song.uri,
                            lyrics     = override.lyrics,
                            updatedAt  = override.updatedAt,
                        )
                    )
                    lyricsRestored++
                }
            }

            // Playlist restore
            var playlistsRestored     = 0
            var playlistSongsRestored = 0

            for (backupPlaylist in backup.playlists) {
                val name = backupPlaylist.name.trim()
                if (name.isBlank()) continue

                val playlistId = playlistDao.findByName(name)?.playlistId
                    ?: run {
                        playlistsRestored++
                        playlistDao.insertPlaylist(
                            PlaylistEntity(name = name, createdAt = importedAt, updatedAt = importedAt)
                        )
                    }

                val existingSongIds = playlistDao.getSongsForPlaylistSnapshot(playlistId)
                    .map { it.songId }
                    .toMutableSet()
                var nextPos = playlistDao.getMaxPosition(playlistId) + 1
                var songsAddedHere = 0

                for (backupSong in backupPlaylist.songs.sortedBy { it.position }) {
                    val currentSong = byUri[backupSong.contentUri]
                        ?: byTags[Triple(
                            backupSong.title.norm(),
                            backupSong.artist.norm(),
                            backupSong.album.norm(),
                        )]
                        ?: continue

                    if (currentSong.id !in existingSongIds) {
                        playlistDao.insertSong(
                            PlaylistSongEntity(
                                playlistId = playlistId,
                                songId     = currentSong.id,
                                position   = nextPos,
                            )
                        )
                        existingSongIds += currentSong.id
                        nextPos++
                        songsAddedHere++
                        playlistSongsRestored++
                    }
                }

                if (songsAddedHere > 0) {
                    playlistDao.touchPlaylist(playlistId, importedAt)
                }
            }

            WavdropBackupImportApplyResult(
                matchedTracks         = match.matchedRows.size,
                unmatchedTracks       = match.unmatchedCount,
                playsAdded            = playsAdded,
                skipsAdded            = skipsAdded,
                lyricsRestored        = lyricsRestored,
                favoritesRestored     = favoritesRestored,
                playlistsRestored     = playlistsRestored,
                playlistSongsRestored = playlistSongsRestored,
            )
        }

        // Restore preferences outside the Room transaction — DataStore is not Room-transactional.
        var preferencesRestored = false
        backup.preferences?.let { prefs ->
            prefs.startupDestination
                ?.let { runCatching { StartupDestination.valueOf(it) }.getOrNull() }
                ?.let { appSettingsRepository.setStartupDestination(it); preferencesRestored = true }

            prefs.mostPlayedPeriod
                ?.let { runCatching { MostPlayedPeriod.valueOf(it) }.getOrNull() }
                ?.let { appSettingsRepository.setMostPlayedPeriod(it); preferencesRestored = true }

            prefs.mostPlayedLimit
                ?.let { runCatching { MostPlayedDisplayLimit.valueOf(it) }.getOrNull() }
                ?.let { appSettingsRepository.setMostPlayedDisplayLimit(it); preferencesRestored = true }

            prefs.homeVisibleSections
                ?.mapNotNull { runCatching { HomeSectionId.valueOf(it) }.getOrNull() }
                ?.takeIf { it.isNotEmpty() }
                ?.let { homeLayoutSettingsRepository.setVisibleSections(it.toSet()); preferencesRestored = true }
        }

        return dbResult.copy(preferencesRestored = preferencesRestored)
    }
}

private fun String.norm() = trim().lowercase()
