package com.launchpoint.wavdrop.data.backup

import android.util.Log
import androidx.room.withTransaction
import com.launchpoint.wavdrop.data.local.WavdropDatabase
import com.launchpoint.wavdrop.data.local.dao.LyricsOverrideDao
import com.launchpoint.wavdrop.data.local.dao.PlaylistDao
import com.launchpoint.wavdrop.data.local.dao.SongDao
import com.launchpoint.wavdrop.data.local.dao.TrackListenEventDao
import com.launchpoint.wavdrop.data.local.dao.TrackStatsDao
import com.launchpoint.wavdrop.data.local.entity.LyricsOverrideEntity
import com.launchpoint.wavdrop.data.local.entity.PlaylistEntity
import com.launchpoint.wavdrop.data.local.entity.PlaylistSongEntity
import com.launchpoint.wavdrop.data.local.entity.TrackListenEventEntity
import com.launchpoint.wavdrop.data.local.entity.TrackStatsEntity
import com.launchpoint.wavdrop.data.model.MostPlayedDisplayLimit
import com.launchpoint.wavdrop.data.model.MostPlayedPeriod
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.settings.AccentColor
import com.launchpoint.wavdrop.data.settings.AppIconAliasManager
import com.launchpoint.wavdrop.data.settings.AppIconChoice
import com.launchpoint.wavdrop.data.settings.AppSettingsRepository
import com.launchpoint.wavdrop.data.settings.AutoBackupInterval
import com.launchpoint.wavdrop.data.settings.BackupFileMode
import com.launchpoint.wavdrop.data.settings.HomeSectionId
import com.launchpoint.wavdrop.data.settings.HomeLayoutSettingsRepository
import com.launchpoint.wavdrop.data.settings.LibraryScanMode
import com.launchpoint.wavdrop.data.settings.LibraryScanSettingsRepository
import com.launchpoint.wavdrop.data.settings.StartupDestination
import com.launchpoint.wavdrop.data.settings.ThemeMode
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

@Singleton
class WavdropBackupImportRepository @Inject constructor(
    private val db: WavdropDatabase,
    private val songDao: SongDao,
    private val trackStatsDao: TrackStatsDao,
    private val lyricsOverrideDao: LyricsOverrideDao,
    private val playlistDao: PlaylistDao,
    private val trackListenEventDao: TrackListenEventDao,
    private val appSettingsRepository: AppSettingsRepository,
    private val appIconAliasManager: AppIconAliasManager,
    private val homeLayoutSettingsRepository: HomeLayoutSettingsRepository,
    private val libraryScanSettingsRepository: LibraryScanSettingsRepository,
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
                    folderPath  = e.folderPath,
                    folderName  = e.folderName,
                )
            }

            val match            = WavdropBackupStatsMatcher.match(backup, currentSongs)
            val importedAt       = System.currentTimeMillis()
            var statsUpdated     = 0
            var favoritesRestored = 0

            // Pre-load current stats for all matched songs to compute reporting deltas
            // without N individual queries inside the loop.
            val currentStatsById = trackStatsDao.getAllStatsSnapshot().associateBy { it.songId }

            for ((song, backupStats) in match.matchedRows) {
                val current = currentStatsById[song.id]

                // Ensure a stats row exists before updating.
                trackStatsDao.insertIfAbsent(
                    TrackStatsEntity(songId = song.id, contentUri = song.uri)
                )

                // Restore mode: the backup is the source of truth. Set each aggregate
                // stat to exactly the backup value (playCount, skipCount,
                // totalListeningTimeMs, lastPlayedAt) — even when the local value is
                // higher. Exact set is naturally idempotent, so restoring the same
                // backup twice yields the same values. BlackPlayer *import* keeps
                // MAX-merge semantics separately in StatsRepository.
                trackStatsDao.restoreExactStats(
                    songId          = song.id,
                    playCount       = backupStats.playCount,
                    skipCount       = backupStats.skipCount,
                    listeningTimeMs = backupStats.totalListeningTimeMs,
                    lastPlayedAt    = backupStats.lastPlayedAt,
                )

                val effect = StatsRestoreStrategy.computeEffect(
                    currentPlayCount       = current?.playCount ?: 0,
                    currentSkipCount       = current?.skipCount ?: 0,
                    currentListeningTimeMs = current?.totalListeningTimeMs ?: 0L,
                    currentLastPlayedAt    = current?.lastPlayedAt ?: 0L,
                    backupPlayCount        = backupStats.playCount,
                    backupSkipCount        = backupStats.skipCount,
                    backupListeningTimeMs  = backupStats.totalListeningTimeMs,
                    backupLastPlayedAt     = backupStats.lastPlayedAt,
                )
                if (effect.anyChanged) statsUpdated++

                // Restore favorite: only set true, never clear a local favorite.
                if (backupStats.isFavorite) {
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

            // Listen event restore. Song identity goes through the same tier matcher
            // as stats so events follow their track to the new song id after a
            // reinstall — exact-tag-only matching previously dropped most events,
            // leaving Monthly Reports/Wrapped empty even though aggregates restored.
            var eventPlan = ListenEventRestorePlanner.Plan(
                toInsert = emptyList(), eventsInBackup = 0, restored = 0,
                skippedDuplicate = 0, skippedUnmatched = 0, skippedInvalidType = 0,
                currentMonthRestored = 0,
            )

            if (backup.listenEvents.isNotEmpty()) {
                val minMs = backup.listenEvents.minOf { it.occurredAt }
                val maxMs = backup.listenEvents.maxOf { it.occurredAt }

                val existingFingerprints = trackListenEventDao
                    .getInRangeSnapshot(minMs, maxMs)
                    .map { "${it.songId}|${it.occurredAt}|${it.eventType}|${it.listenedMs}" }
                    .toHashSet()

                val resolvedBySongId =
                    WavdropBackupStatsMatcher.resolveBackupSongIds(backup, currentSongs)

                eventPlan = ListenEventRestorePlanner.plan(
                    events = backup.listenEvents,
                    resolveSong = { event ->
                        byUri[event.contentUri]
                            ?: resolvedBySongId[event.songId]
                            // Last resort for old backups whose songs array lacks this
                            // track: exact tags carried on the event row itself.
                            ?: byTags[Triple(
                                event.title.norm(),
                                event.artist.norm(),
                                event.album.norm(),
                            )]
                    },
                    existingFingerprints = existingFingerprints,
                )

                for (entity in eventPlan.toInsert) {
                    trackListenEventDao.insert(entity)
                }
            }
            val eventsRestored = eventPlan.restored
            val eventsSkipped  = eventPlan.skippedTotal

            WavdropBackupImportApplyResult(
                matchedTracks         = match.matchedRows.size,
                unmatchedTracks       = match.unmatchedCount,
                matchDiagnostics      = match.diagnostics,
                statsUpdated          = statsUpdated,
                lyricsRestored        = lyricsRestored,
                favoritesRestored     = favoritesRestored,
                playlistsRestored     = playlistsRestored,
                playlistSongsRestored = playlistSongsRestored,
                eventsRestored        = eventsRestored,
                eventsSkipped         = eventsSkipped,
                eventsSkippedDuplicate     = eventPlan.skippedDuplicate,
                eventsSkippedUnmatched     = eventPlan.skippedUnmatched,
                currentMonthEventsRestored = eventPlan.currentMonthRestored,
            )
        }

        // Diagnostics: explains "missing plays" after restore (unmatched tracks are the
        // usual cause — URI changes after reinstall plus tag mismatch).
        val diag = dbResult.matchDiagnostics
        Log.i(
            TAG,
            "Restore applied: statsInBackup=${diag.statsInBackup} " +
                "matched=${dbResult.matchedTracks} " +
                "[uri=${diag.matchedByUri} path=${diag.matchedByPath} " +
                "tags+dur=${diag.matchedByTagsDuration} " +
                "title+artist+dur=${diag.matchedByTitleArtistDuration} " +
                "title+dur=${diag.matchedByTitleDuration} tagsOnly=${diag.matchedByTagsOnly}] " +
                "ambiguous=${diag.ambiguous} collisions=${diag.collisions} " +
                "unmatched=${diag.unmatched} " +
                "statsRestored=${dbResult.statsUpdated} " +
                "eventsInBackup=${backup.listenEvents.size} " +
                "eventsRestored=${dbResult.eventsRestored} " +
                "eventsDuplicate=${dbResult.eventsSkippedDuplicate} " +
                "eventsUnmatched=${dbResult.eventsSkippedUnmatched} " +
                "currentMonthEventsRestored=${dbResult.currentMonthEventsRestored}",
        )

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

            prefs.scanMode
                ?.let { runCatching { LibraryScanMode.valueOf(it) }.getOrNull() }
                ?.let { libraryScanSettingsRepository.setScanMode(it); preferencesRestored = true }

            prefs.minimumTrackDurationSeconds
                ?.let { libraryScanSettingsRepository.setMinimumTrackDurationSeconds(it); preferencesRestored = true }

            val folderUris = prefs.selectedFolderUris?.filter { it.isNotBlank() }
            if (!folderUris.isNullOrEmpty()) {
                libraryScanSettingsRepository.setSelectedFolderUris(folderUris)
                preferencesRestored = true
            }

            prefs.themeMode
                ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?.let { appSettingsRepository.setThemeMode(it); preferencesRestored = true }

            prefs.accentColor
                ?.let { runCatching { AccentColor.valueOf(it) }.getOrNull() }
                ?.let { appSettingsRepository.setAccentColor(it); preferencesRestored = true }

            prefs.compactMode
                ?.let { appSettingsRepository.setCompactMode(it); preferencesRestored = true }

            prefs.backupFileMode
                ?.let { runCatching { BackupFileMode.valueOf(it) }.getOrNull() }
                ?.let { appSettingsRepository.setBackupFileMode(it); preferencesRestored = true }

            // Restore auto-backup interval but NOT the folder URI (SAF permissions are device-specific)
            // and NOT lastAutoBackupAtMillis. Reset lastAutoBackupAtMillis to 0 so a backup is
            // attempted on the next app open once the user selects a folder on this device.
            prefs.autoBackupInterval
                ?.let { runCatching { AutoBackupInterval.valueOf(it) }.getOrNull() }
                ?.let { interval ->
                    appSettingsRepository.setAutoBackupInterval(interval)
                    appSettingsRepository.setLastAutoBackupAtMillis(0L)
                    preferencesRestored = true
                }
        }

        // Folder selection is needed if auto-backup was restored with a non-OFF interval
        // and there is no folder already set on this device. SAF permissions are not portable
        // across devices, so the old folder URI is never restored from backup.
        val restoredInterval = backup.preferences?.autoBackupInterval
            ?.let { runCatching { AutoBackupInterval.valueOf(it) }.getOrNull() }
        val currentFolderUri = appSettingsRepository.autoBackupFolderUri.first()
        val needsAutoBackupFolderSelection =
            restoredInterval != null
                && restoredInterval != AutoBackupInterval.OFF
                && currentFolderUri == null

        // Persist the pending-folder flag BEFORE applying the launcher icon below: alias
        // switching can kill the process, and this flag is what makes the folder prompt
        // reappear after the relaunch.
        if (needsAutoBackupFolderSelection) {
            appSettingsRepository.setNeedsAutoBackupFolderSelectionAfterRestore(true)
        }

        // Launcher icon is restored LAST. Applying the activity-alias switch can restart or
        // close the app on some launchers, so everything else (including the pending-folder
        // flag above) must already be persisted by this point.
        backup.preferences?.launcherIcon
            ?.let { AppIconChoice.fromStoredName(it) }
            ?.let {
                appSettingsRepository.setAppIconChoice(it)
                runCatching { appIconAliasManager.apply(it) }
                preferencesRestored = true
            }

        return dbResult.copy(
            preferencesRestored            = preferencesRestored,
            needsAutoBackupFolderSelection = needsAutoBackupFolderSelection,
        )
    }
}

private const val TAG = "WavdropRestore"

private fun String.norm() = trim().lowercase()
