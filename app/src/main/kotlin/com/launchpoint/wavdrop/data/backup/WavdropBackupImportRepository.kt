package com.launchpoint.wavdrop.data.backup

import android.util.Log
import androidx.room.withTransaction
import com.launchpoint.wavdrop.data.local.WavdropDatabase
import com.launchpoint.wavdrop.data.local.dao.ImportBaselineDao
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
import com.launchpoint.wavdrop.data.settings.ArtworkCornerStyle
import com.launchpoint.wavdrop.data.settings.AutoBackupInterval
import com.launchpoint.wavdrop.data.settings.BackupFileMode
import com.launchpoint.wavdrop.data.settings.HeadphoneResumeMode
import com.launchpoint.wavdrop.data.settings.NotificationControlsSetting
import com.launchpoint.wavdrop.data.settings.NowPlayingBackground
import com.launchpoint.wavdrop.data.settings.NowPlayingTimeDisplayMode
import com.launchpoint.wavdrop.data.settings.WrappedBackgroundIntensity
import com.launchpoint.wavdrop.data.settings.WrappedFallbackTheme
import com.launchpoint.wavdrop.data.settings.ResumeBehaviorSettingsRepository
import com.launchpoint.wavdrop.data.settings.SearchTapBehavior
import com.launchpoint.wavdrop.data.settings.HomeSectionId
import com.launchpoint.wavdrop.data.settings.HomeLayoutSettingsRepository
import com.launchpoint.wavdrop.data.settings.LibraryScanMode
import com.launchpoint.wavdrop.data.settings.LibraryScanSettingsRepository
import com.launchpoint.wavdrop.data.settings.SongSortMode
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
    private val importBaselineDao: ImportBaselineDao,
    private val playlistDao: PlaylistDao,
    private val trackListenEventDao: TrackListenEventDao,
    private val appSettingsRepository: AppSettingsRepository,
    private val appIconAliasManager: AppIconAliasManager,
    private val homeLayoutSettingsRepository: HomeLayoutSettingsRepository,
    private val libraryScanSettingsRepository: LibraryScanSettingsRepository,
    private val resumeBehaviorSettingsRepository: ResumeBehaviorSettingsRepository,
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
            // Backup song id → current library song, shared by event and baseline
            // restore so every songId-keyed row follows its track after a reinstall.
            val resolvedBySongId =
                WavdropBackupStatsMatcher.resolveBackupSongIds(backup, currentSongs)
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

            // Shared resolver for song-linked rows (lyrics, playlist entries, events):
            // tier-matched songId first, then URI, then tags only when unambiguous.
            val backupSongById = backup.songs.associateBy { it.id }
            val linkResolver   = BackupSongLinkResolver(currentSongs, resolvedBySongId)

            // Lyrics overrides restore
            var lyricsRestored  = 0
            var lyricsUnmatched = 0

            for (override in backup.lyricsOverrides) {
                val backupSong = backupSongById[override.songId]
                val song = linkResolver.resolve(
                    backupSongId = override.songId,
                    contentUri   = override.contentUri,
                    title        = backupSong?.title,
                    artist       = backupSong?.artist,
                    album        = backupSong?.album,
                )
                if (song == null) {
                    lyricsUnmatched++
                    continue
                }

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
            var playlistsRestored        = 0
            var playlistSongsRestored    = 0
            var playlistEntriesUnmatched = 0
            val playlistSummaries        = mutableListOf<PlaylistRestoreSummary>()

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
                    .toSet()

                val entryPlan = PlaylistEntryRestorePlanner.plan(
                    entries = backupPlaylist.songs,
                    resolve = { entry ->
                        linkResolver.resolve(
                            backupSongId = entry.songId,
                            contentUri   = entry.contentUri,
                            title        = entry.title,
                            artist       = entry.artist,
                            album        = entry.album,
                        )
                    },
                    existingSongIds = existingSongIds,
                    nextPosition    = playlistDao.getMaxPosition(playlistId) + 1,
                )

                for (entry in entryPlan.toAdd) {
                    playlistDao.insertSong(
                        PlaylistSongEntity(
                            playlistId = playlistId,
                            songId     = entry.songId,
                            position   = entry.position,
                        )
                    )
                }
                playlistSongsRestored    += entryPlan.restored
                playlistEntriesUnmatched += entryPlan.skippedUnmatched
                playlistSummaries += PlaylistRestoreSummary(
                    playlistName     = name,
                    entriesInBackup  = entryPlan.entriesInBackup,
                    restored         = entryPlan.restored,
                    skippedUnmatched = entryPlan.skippedUnmatched,
                    skippedDuplicate = entryPlan.skippedExisting,
                )

                if (entryPlan.restored > 0) {
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

                eventPlan = ListenEventRestorePlanner.plan(
                    events = backup.listenEvents,
                    // Tier-matched songId, then URI, then tags-only-if-unique — the
                    // tags fallback covers old backups whose songs array lacks this
                    // track, without guessing between duplicate-tag songs.
                    resolveSong = { event ->
                        linkResolver.resolve(
                            backupSongId = event.songId,
                            contentUri   = event.contentUri,
                            title        = event.title,
                            artist       = event.artist,
                            album        = event.album,
                        )
                    },
                    existingFingerprints = existingFingerprints,
                )

                for (entity in eventPlan.toInsert) {
                    trackListenEventDao.insert(entity)
                }
            }
            val eventsRestored = eventPlan.restored
            val eventsSkipped  = eventPlan.skippedTotal

            // Import baseline restore. Baselines track what previous BlackPlayer
            // imports contributed; without them, a future re-import reports inflated
            // "plays imported" numbers (stored stats stay correct via MAX-merge).
            // Re-keyed to current song ids through the same tier matcher; an existing
            // local baseline with a newer lastImportedAt always wins.
            val baselinePlan = ImportBaselineRestorePlanner.plan(
                baselines     = backup.importBaselines,
                resolveSongId = { backupSongId -> resolvedBySongId[backupSongId]?.id },
                existing      = importBaselineDao.getAllImportBaselinesSnapshot(),
            )
            for (entity in baselinePlan.toUpsert) {
                importBaselineDao.upsertBaseline(entity)
            }

            val favoritesInBackup = backup.trackStats.count { it.isFavorite }
            val matchedFavorites  = match.matchedRows.count { (_, stat) -> stat.isFavorite }

            WavdropBackupImportApplyResult(
                matchedTracks         = match.matchedRows.size,
                unmatchedTracks       = match.unmatchedCount,
                matchDiagnostics      = match.diagnostics,
                statsUpdated          = statsUpdated,
                lyricsRestored        = lyricsRestored,
                lyricsInBackup        = backup.lyricsOverrides.size,
                lyricsUnmatched       = lyricsUnmatched,
                favoritesRestored     = favoritesRestored,
                favoritesInBackup     = favoritesInBackup,
                favoritesUnmatched    = favoritesInBackup - matchedFavorites,
                playlistsRestored     = playlistsRestored,
                playlistsInBackup     = backup.playlists.size,
                playlistSongsRestored = playlistSongsRestored,
                playlistEntriesInBackup  = backup.playlists.sumOf { it.songs.size },
                playlistEntriesUnmatched = playlistEntriesUnmatched,
                playlistRestoreSummaries = playlistSummaries,
                eventsRestored        = eventsRestored,
                baselinesRestored     = baselinePlan.restored,
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
                "currentMonthEventsRestored=${dbResult.currentMonthEventsRestored} " +
                "baselinesInBackup=${backup.importBaselines.size} " +
                "baselinesRestored=${dbResult.baselinesRestored} " +
                "favorites=${dbResult.favoritesRestored}/${dbResult.favoritesInBackup}" +
                "(unmatched=${dbResult.favoritesUnmatched}) " +
                "lyrics=${dbResult.lyricsRestored}/${dbResult.lyricsInBackup}" +
                "(unmatched=${dbResult.lyricsUnmatched}) " +
                "playlists=${dbResult.playlistsRestored}/${dbResult.playlistsInBackup} " +
                "playlistEntries=${dbResult.playlistSongsRestored}/${dbResult.playlistEntriesInBackup}" +
                "(unmatched=${dbResult.playlistEntriesUnmatched})",
        )

        // Restore preferences outside the Room transaction — DataStore is not Room-transactional.
        val warnings = mutableListOf<String>()
        BackupRestoreWarnings.selectedFolderPermissionWarning(backup.preferences)?.let { warnings += it }
        if (dbResult.playlistEntriesUnmatched > 0) {
            warnings += "Some playlist songs could not be matched to your current library."
        }

        var preferencesRestored = false
        backup.preferences?.let { prefs ->
            runCatching {
            prefs.startupDestination
                ?.let { runCatching { StartupDestination.valueOf(it) }.getOrNull() }
                ?.let { appSettingsRepository.setStartupDestination(it); preferencesRestored = true }

            prefs.mostPlayedPeriod
                ?.let { runCatching { MostPlayedPeriod.valueOf(it) }.getOrNull() }
                ?.let { appSettingsRepository.setMostPlayedPeriod(it); preferencesRestored = true }

            prefs.mostPlayedLimit
                ?.let { runCatching { MostPlayedDisplayLimit.valueOf(it) }.getOrNull() }
                ?.let { appSettingsRepository.setMostPlayedDisplayLimit(it); preferencesRestored = true }

            prefs.songSortMode
                ?.let { SongSortMode.fromStoredName(it) }
                ?.let { appSettingsRepository.setSongSortMode(it); preferencesRestored = true }

            prefs.searchTapBehavior
                ?.let { SearchTapBehavior.fromStoredName(it) }
                ?.let { appSettingsRepository.setSearchTapBehavior(it); preferencesRestored = true }

            prefs.homeVisibleSections
                ?.mapNotNull { runCatching { HomeSectionId.valueOf(it) }.getOrNull() }
                ?.takeIf { it.isNotEmpty() }
                ?.let { homeLayoutSettingsRepository.setVisibleSections(it.toSet()); preferencesRestored = true }

            val restoredScanMode = prefs.scanMode
                ?.let { runCatching { LibraryScanMode.valueOf(it) }.getOrNull() }
            restoredScanMode?.let { libraryScanSettingsRepository.setScanMode(it); preferencesRestored = true }

            prefs.minimumTrackDurationSeconds
                ?.let { libraryScanSettingsRepository.setMinimumTrackDurationSeconds(it); preferencesRestored = true }

            // SAF folder URIs are device- and permission-specific. Restoring them on a new
            // device creates SELECTED_FOLDERS mode with URIs that appear non-empty but cannot
            // be opened, silently leaving the library empty. When SELECTED_FOLDERS was backed
            // up, skip the URI restore and instead set a flag that shows a targeted
            // "Re-select folders after restore" prompt through the existing empty-library UI.
            if (restoredScanMode == LibraryScanMode.SELECTED_FOLDERS) {
                runCatching { appSettingsRepository.setNeedsFolderReselectionAfterRestore(true) }
                    .onFailure { error ->
                        Log.e(TAG, "Restore: could not set folder reselection flag", error)
                    }
            } else {
                val folderUris = prefs.selectedFolderUris?.filter { it.isNotBlank() }
                if (!folderUris.isNullOrEmpty()) {
                    libraryScanSettingsRepository.setSelectedFolderUris(folderUris)
                    preferencesRestored = true
                }
            }

            prefs.themeMode
                ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?.let { appSettingsRepository.setThemeMode(it); preferencesRestored = true }

            prefs.accentColor
                ?.let { runCatching { AccentColor.valueOf(it) }.getOrNull() }
                ?.let { appSettingsRepository.setAccentColor(it); preferencesRestored = true }

            prefs.compactMode
                ?.let { appSettingsRepository.setCompactMode(it); preferencesRestored = true }

            prefs.artworkCornerStyle
                ?.let { runCatching { ArtworkCornerStyle.valueOf(it) }.getOrNull() }
                ?.let { appSettingsRepository.setArtworkCornerStyle(it); preferencesRestored = true }

            prefs.showSongThumbnails
                ?.let { appSettingsRepository.setShowSongThumbnails(it); preferencesRestored = true }

            prefs.showAlbumInSongRows
                ?.let { appSettingsRepository.setShowAlbumInSongRows(it); preferencesRestored = true }

            prefs.nowPlayingBackground
                ?.let { runCatching { NowPlayingBackground.valueOf(it) }.getOrNull() }
                ?.let { appSettingsRepository.setNowPlayingBackground(it); preferencesRestored = true }

            prefs.showQueueCount
                ?.let { appSettingsRepository.setShowQueueCount(it); preferencesRestored = true }

            prefs.nowPlayingTimeDisplayMode
                ?.let { runCatching { NowPlayingTimeDisplayMode.valueOf(it) }.getOrNull() }
                ?.let { appSettingsRepository.setNowPlayingTimeDisplayMode(it); preferencesRestored = true }

            prefs.notificationControls
                ?.let { runCatching { NotificationControlsSetting.valueOf(it) }.getOrNull() }
                ?.let { appSettingsRepository.setNotificationControlsSetting(it); preferencesRestored = true }

            prefs.includeWhatsAppVoiceNotes
                ?.let { libraryScanSettingsRepository.setIncludeWhatsAppVoiceNotes(it); preferencesRestored = true }

            prefs.pauseOnAudioDisconnect
                ?.let { resumeBehaviorSettingsRepository.setPauseOnAudioDisconnect(it); preferencesRestored = true }

            prefs.rememberLastTrack
                ?.let { resumeBehaviorSettingsRepository.setRememberLastTrack(it); preferencesRestored = true }

            prefs.rememberPosition
                ?.let { resumeBehaviorSettingsRepository.setRememberPosition(it); preferencesRestored = true }

            prefs.restoreQueue
                ?.let { resumeBehaviorSettingsRepository.setRestoreQueue(it); preferencesRestored = true }

            prefs.bluetoothResumeMode
                ?.let { runCatching { HeadphoneResumeMode.valueOf(it) }.getOrNull() }
                ?.let { resumeBehaviorSettingsRepository.setBluetoothResumeMode(it); preferencesRestored = true }

            prefs.wiredResumeMode
                ?.let { runCatching { HeadphoneResumeMode.valueOf(it) }.getOrNull() }
                ?.let { resumeBehaviorSettingsRepository.setWiredResumeMode(it); preferencesRestored = true }

            prefs.showMilestoneCelebrations
                ?.let { appSettingsRepository.setShowMilestoneCelebrations(it); preferencesRestored = true }

            prefs.wrappedUseArtworkBackgrounds
                ?.let { appSettingsRepository.setWrappedUseArtworkBackgrounds(it); preferencesRestored = true }

            prefs.wrappedBackgroundIntensity
                ?.let { runCatching { WrappedBackgroundIntensity.valueOf(it) }.getOrNull() }
                ?.let { appSettingsRepository.setWrappedBackgroundIntensity(it); preferencesRestored = true }

            prefs.wrappedFallbackTheme
                ?.let { runCatching { WrappedFallbackTheme.valueOf(it) }.getOrNull() }
                ?.let { appSettingsRepository.setWrappedFallbackTheme(it); preferencesRestored = true }

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
            }.onFailure { error ->
                Log.e(TAG, "Restore data committed but some settings failed to restore", error)
                warnings += BackupRestoreWarnings.SETTINGS_PARTIAL
            }
        }

        // Folder selection is needed if auto-backup was restored with a non-OFF interval
        // and there is no folder already set on this device. SAF permissions are not portable
        // across devices, so the old folder URI is never restored from backup.
        val restoredInterval = backup.preferences?.autoBackupInterval
            ?.let { runCatching { AutoBackupInterval.valueOf(it) }.getOrNull() }
        val currentFolderUri = runCatching {
            appSettingsRepository.autoBackupFolderUri.first()
        }.getOrElse { error ->
            Log.e(TAG, "Restore data committed but current backup folder could not be read", error)
            if (BackupRestoreWarnings.SETTINGS_PARTIAL !in warnings) {
                warnings += BackupRestoreWarnings.SETTINGS_PARTIAL
            }
            null
        }
        val needsAutoBackupFolderSelection =
            restoredInterval != null
                && restoredInterval != AutoBackupInterval.OFF
                && currentFolderUri == null

        // Persist the pending-folder flag BEFORE applying the launcher icon below: alias
        // switching can kill the process, and this flag is what makes the folder prompt
        // reappear after the relaunch.
        if (needsAutoBackupFolderSelection) {
            runCatching {
                appSettingsRepository.setNeedsAutoBackupFolderSelectionAfterRestore(true)
            }.onFailure { error ->
                Log.e(TAG, "Restore data committed but backup folder prompt flag failed", error)
                if (BackupRestoreWarnings.SETTINGS_PARTIAL !in warnings) {
                    warnings += BackupRestoreWarnings.SETTINGS_PARTIAL
                }
            }
        }

        // Launcher icon is restored LAST. Applying the activity-alias switch can restart or
        // close the app on some launchers, so everything else (including the pending-folder
        // flag above) must already be persisted by this point.
        var launcherIconRestored = false
        backup.preferences?.launcherIcon
            ?.let { AppIconChoice.fromStoredName(it) }
            ?.let {
                runCatching {
                    appSettingsRepository.setAppIconChoice(it)
                    runCatching { appIconAliasManager.apply(it) }
                    preferencesRestored = true
                    launcherIconRestored = true
                }.onFailure { error ->
                    Log.e(TAG, "Restore data committed but launcher icon failed to restore", error)
                    if (BackupRestoreWarnings.SETTINGS_PARTIAL !in warnings) {
                        warnings += BackupRestoreWarnings.SETTINGS_PARTIAL
                    }
                }
            }

        return dbResult.copy(
            preferencesRestored            = preferencesRestored,
            needsAutoBackupFolderSelection = needsAutoBackupFolderSelection,
            launcherIconRestored           = launcherIconRestored,
            warnings                       = warnings.distinct(),
        )
    }

    /**
     * Reads current DB state, runs the stats matcher against [backup], and returns true
     * if any matched song has local stats strictly higher than the backup values.
     * No data is written. Called during the preview phase to decide whether to show a
     * "newer local activity" warning before the user confirms the restore.
     */
    suspend fun detectStatsRegression(backup: WavdropBackup): StatsRegressionDetector.StatsRegressionSummary {
        if (backup.trackStats.isEmpty()) return StatsRegressionDetector.StatsRegressionSummary(0)
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
        val match = WavdropBackupStatsMatcher.match(backup, currentSongs)
        if (match.matchedRows.isEmpty()) return StatsRegressionDetector.StatsRegressionSummary(0)
        val currentStatsById = trackStatsDao.getAllStatsSnapshot().associateBy { it.songId }
        val pairs = match.matchedRows.mapNotNull { (song, backupStats) ->
            val local = currentStatsById[song.id] ?: return@mapNotNull null
            StatsRegressionDetector.MatchedStatsPair(
                backupPlayCount        = backupStats.playCount,
                backupSkipCount        = backupStats.skipCount,
                backupListeningTimeMs  = backupStats.totalListeningTimeMs,
                backupLastPlayedAt     = backupStats.lastPlayedAt,
                localPlayCount         = local.playCount,
                localSkipCount         = local.skipCount,
                localListeningTimeMs   = local.totalListeningTimeMs,
                localLastPlayedAt      = local.lastPlayedAt,
            )
        }
        return StatsRegressionDetector.detect(pairs)
    }
}

private const val TAG = "WavdropRestore"
