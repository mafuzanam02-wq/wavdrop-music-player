package com.launchpoint.wavdrop.data.backup

import android.util.Log
import androidx.room.withTransaction
import com.launchpoint.wavdrop.data.local.WavdropDatabase
import com.launchpoint.wavdrop.data.local.dao.ImportBaselineDao
import com.launchpoint.wavdrop.data.local.dao.LyricsOverrideDao
import com.launchpoint.wavdrop.data.local.dao.PendingTrackDao
import com.launchpoint.wavdrop.data.local.dao.PlaylistDao
import com.launchpoint.wavdrop.data.local.dao.SongDao
import com.launchpoint.wavdrop.data.local.dao.TrackListenEventDao
import com.launchpoint.wavdrop.data.local.dao.TrackStatsDao
import com.launchpoint.wavdrop.data.local.entity.LyricsOverrideEntity
import com.launchpoint.wavdrop.data.local.entity.PendingImportBaselineEntity
import com.launchpoint.wavdrop.data.local.entity.PendingListenEventEntity
import com.launchpoint.wavdrop.data.local.entity.PendingLyricsOverrideEntity
import com.launchpoint.wavdrop.data.local.entity.PendingPlaylistEntryEntity
import com.launchpoint.wavdrop.data.local.entity.PendingTrackEntity
import com.launchpoint.wavdrop.data.local.entity.PendingTrackStatsEntity
import com.launchpoint.wavdrop.data.local.entity.PlaylistEntity
import com.launchpoint.wavdrop.data.local.entity.PlaylistSongEntity
import com.launchpoint.wavdrop.data.local.entity.TrackListenEventEntity
import com.launchpoint.wavdrop.data.local.entity.TrackStatsEntity
import com.launchpoint.wavdrop.data.model.Song
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WavdropBackupImportRepository @Inject constructor(
    private val db: WavdropDatabase,
    private val songDao: SongDao,
    private val trackStatsDao: TrackStatsDao,
    private val lyricsOverrideDao: LyricsOverrideDao,
    private val importBaselineDao: ImportBaselineDao,
    private val playlistDao: PlaylistDao,
    private val trackListenEventDao: TrackListenEventDao,
    private val pendingTrackDao: PendingTrackDao,
) {
    /**
     * Recovery is offered only when no local listening/history state exists. The songs table
     * is deliberately excluded: a clean install may already have completed its first scan.
     */
    suspend fun isCleanInstallRecoveryCandidate(): Boolean =
        trackStatsDao.getAllStatsSnapshot().isEmpty() &&
            trackListenEventDao.getAllSnapshot().isEmpty() &&
            importBaselineDao.getAllImportBaselinesSnapshot().isEmpty() &&
            lyricsOverrideDao.getAllSnapshot().isEmpty() &&
            playlistDao.getAllPlaylistsSnapshot().isEmpty() &&
            pendingTrackDao.getAllOriginKeys().isEmpty()

    suspend fun applyImport(backup: WavdropBackup): WavdropBackupImportApplyResult {
        val dbResult = db.withTransaction {

            // ── 1. Load all snapshots upfront (single read pass inside the transaction)
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

            // Backup song id → current library song, shared by event and baseline
            // restore so every songId-keyed row follows its track after a reinstall.
            val match            = WavdropBackupStatsMatcher.match(backup, currentSongs)
            val resolvedBySongId = WavdropBackupStatsMatcher.resolveBackupSongIds(backup, currentSongs)

            // Pre-load current stats for all matched songs to compute reporting deltas
            // without N individual queries inside the loop; also needed for the
            // authoritative mergeability recheck (§8.2 apply-side).
            val currentStatsById = trackStatsDao.getAllStatsSnapshot().associateBy { it.songId }

            val existingEventFingerprints: Set<String> = if (backup.listenEvents.isNotEmpty()) {
                val minMs = backup.listenEvents.minOf { it.occurredAt }
                val maxMs = backup.listenEvents.maxOf { it.occurredAt }
                trackListenEventDao.getInRangeSnapshot(minMs, maxMs)
                    .mapTo(HashSet()) { "${it.songId}|${it.occurredAt}|${it.eventType}|${it.listenedMs}" }
            } else {
                emptySet()
            }

            val existingEventIds: Set<String> = if (backup.listenEvents.isNotEmpty()) {
                val minMs = backup.listenEvents.minOf { it.occurredAt }
                val maxMs = backup.listenEvents.maxOf { it.occurredAt }
                trackListenEventDao.getEventIdsInRangeSnapshot(minMs, maxMs).toHashSet()
            } else {
                emptySet()
            }

            val existingBaselines = importBaselineDao.getAllImportBaselinesSnapshot()

            val existingQuarantineOriginKeys: Set<String> =
                pendingTrackDao.getAllOriginKeys().toHashSet()

            val existingLyricsMap = lyricsOverrideDao.getAllSnapshot()
                .associate { WavdropMergePreviewAnalyzer.ExistingLyricsKey(it.songId, it.contentUri) to it.updatedAt }

            val existingPlaylistsMap: Map<String, Set<Long>> = if (backup.playlists.isNotEmpty()) {
                buildMap {
                    for (bp in backup.playlists) {
                        val name = bp.name.trim()
                        if (name.isBlank()) continue
                        val playlist = playlistDao.findByName(name)
                        put(
                            name,
                            if (playlist == null) emptySet()
                            else playlistDao.getSongsForPlaylistSnapshot(playlist.playlistId)
                                .mapTo(HashSet()) { it.songId },
                        )
                    }
                }
            } else {
                emptyMap()
            }

            // ── 2. Authoritative no-op recheck — same shared analyzer used at preview.
            //       Runs inside the transaction so it sees the current committed state;
            //       preview state may have been stale if playback or scans ran between
            //       preview and apply.
            val mergeCheck = WavdropMergePreviewAnalyzer.analyze(
                backup                       = backup,
                currentSongs                 = currentSongs,
                existingQuarantineOriginKeys = existingQuarantineOriginKeys,
                existingStats                = currentStatsById,
                existingEventFingerprints    = existingEventFingerprints,
                existingEventIds             = existingEventIds,
                existingBaselines            = existingBaselines,
                existingLyrics               = existingLyricsMap,
                existingPlaylists            = existingPlaylistsMap,
            )
            if (!mergeCheck.hasMergeableData) {
                // No persistent state change is possible. Return without any writes.
                return@withTransaction WavdropBackupImportApplyResult(
                    matchedTracks   = match.matchedRows.size,
                    unmatchedTracks = match.unmatchedCount,
                    statsUpdated    = 0,
                    isNoOp          = true,
                )
            }

            // ── 3. Proceed with writes ────────────────────────────────────────────────
            val importedAt        = System.currentTimeMillis()
            var statsUpdated      = 0
            var statsRetainedLocal = 0
            var favoritesRestored = 0

            for ((song, backupStats) in match.matchedRows) {
                val current = currentStatsById[song.id]

                // Ensure a stats row exists before updating.
                trackStatsDao.insertIfAbsent(
                    TrackStatsEntity(songId = song.id, contentUri = song.uri)
                )

                // Effective lastListenedAt depends on source format (contract §C):
                //   v1 backup → use lastPlayedAt as conservative fallback
                //   v2 backup → use exact lastListenedAt (always non-null for v2)
                val effectiveLastListenedAt = when (backup.sourceVersion) {
                    BackupFormatVersion.V2 -> backupStats.lastListenedAt!! // non-null by v2 contract
                    BackupFormatVersion.V1 -> backupStats.lastPlayedAt
                }

                // Merge Restore (P1-B): both local and backup are valid.
                // Each field is set to MAX(local, backup) — stats can only increase.
                // Idempotent: merging the same backup twice yields no change the second time.
                trackStatsDao.mergeMaxStats(
                    songId                   = song.id,
                    importedPlayCount        = backupStats.playCount,
                    importedSkipCount        = backupStats.skipCount,
                    importedListeningTimeMs  = backupStats.totalListeningTimeMs,
                    importedLastPlayedAt     = backupStats.lastPlayedAt,
                    importedLastListenedAt   = effectiveLastListenedAt,
                )

                val effect = StatsRestoreStrategy.computeEffect(
                    currentPlayCount              = current?.playCount ?: 0,
                    currentSkipCount              = current?.skipCount ?: 0,
                    currentListeningTimeMs        = current?.totalListeningTimeMs ?: 0L,
                    currentLastPlayedAt           = current?.lastPlayedAt ?: 0L,
                    currentLastListenedAt         = current?.lastListenedAt ?: 0L,
                    backupPlayCount               = backupStats.playCount,
                    backupSkipCount               = backupStats.skipCount,
                    backupListeningTimeMs         = backupStats.totalListeningTimeMs,
                    backupLastPlayedAt            = backupStats.lastPlayedAt,
                    effectiveBackupLastListenedAt = effectiveLastListenedAt,
                )
                if (effect.anyIncreased) statsUpdated++
                if (effect.anyRetainedLocal) statsRetainedLocal++

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

            // Lyrics overrides restore — use the pre-loaded snapshot to avoid N per-song queries.
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

                val existingUpdatedAt = existingLyricsMap[
                    WavdropMergePreviewAnalyzer.ExistingLyricsKey(song.id, song.uri)
                ]
                if (existingUpdatedAt == null || override.updatedAt > existingUpdatedAt) {
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

                // Use the pre-loaded snapshot for duplicate detection (consistent with the
                // mergeability recheck above); avoids a second DB round-trip per playlist.
                val existingSongIds = existingPlaylistsMap[name] ?: emptySet()

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
                    existingFingerprints = existingEventFingerprints,
                    existingEventIds     = existingEventIds,
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
                existing      = existingBaselines,
            )
            for (entity in baselinePlan.toUpsert) {
                importBaselineDao.upsertBaseline(entity)
            }

            // ── Quarantine: preserve unmatched backup history ─────────────────────────
            val fp = QuarantinePlanner.backupFingerprint(backup)
            val quarantinePlan = QuarantinePlanner.plan(
                backup                = backup,
                backupFingerprint     = fp,
                resolvedBackupSongIds = resolvedBySongId.keys,
                existingOriginKeys    = existingQuarantineOriginKeys,
            )
            val now = System.currentTimeMillis()
            var pendingTracksInserted = 0
            var pendingEventsInserted = 0
            var pendingPlaylistEntriesInserted = 0

            for (candidate in quarantinePlan.newCandidates) {
                val trackEntity = PendingTrackEntity(
                    originKey        = candidate.originKey,
                    backupFingerprint = fp,
                    backupSongId     = candidate.backupSongId.toString(),
                    title            = candidate.title,
                    artist           = candidate.artist,
                    album            = candidate.album,
                    duration         = candidate.duration,
                    trackNumber      = candidate.trackNumber,
                    year             = candidate.year,
                    sourceUri        = candidate.sourceUri,
                    sourceFolderPath = candidate.sourceFolderPath,
                    restoredAt       = now,
                )
                val rowId = pendingTrackDao.insertTrack(trackEntity)
                val pendingId = if (rowId != -1L) rowId
                else pendingTrackDao.getPendingId(candidate.originKey) ?: continue

                pendingTracksInserted++

                candidate.stats?.let { s ->
                    pendingTrackDao.insertStats(
                        PendingTrackStatsEntity(
                            pendingId            = pendingId,
                            playCount            = s.playCount,
                            skipCount            = s.skipCount,
                            lastPlayedAt         = s.lastPlayedAt,
                            totalListeningTimeMs = s.totalListeningTimeMs,
                            isFavorite           = s.isFavorite,
                            // null for v1 (field absent in v1 format); exact value for v2.
                            lastListenedAt       = s.lastListenedAt,
                        )
                    )
                }

                for ((event, fingerprint) in candidate.events) {
                    pendingTrackDao.insertEvent(
                        PendingListenEventEntity(
                            pendingId         = pendingId,
                            eventType         = event.eventType,
                            occurredAt        = event.occurredAt,
                            listenedMs        = event.listenedMs,
                            durationMs        = event.durationMs,
                            originFingerprint = fingerprint,
                        )
                    )
                    pendingEventsInserted++
                }

                candidate.lyrics?.let { (override, fingerprint) ->
                    pendingTrackDao.insertLyrics(
                        PendingLyricsOverrideEntity(
                            pendingId         = pendingId,
                            lyrics            = override.lyrics,
                            updatedAt         = override.updatedAt,
                            originFingerprint = fingerprint,
                        )
                    )
                }

                for ((baseline, fingerprint) in candidate.baselines) {
                    pendingTrackDao.insertBaseline(
                        PendingImportBaselineEntity(
                            pendingId         = pendingId,
                            sourceType        = baseline.sourceType,
                            sourceKey         = baseline.sourceKey,
                            playCount         = baseline.lastImportedPlayCount,
                            originFingerprint = fingerprint,
                        )
                    )
                }

                for (entry in candidate.playlistEntries) {
                    pendingTrackDao.insertPlaylistEntry(
                        PendingPlaylistEntryEntity(
                            pendingId               = pendingId,
                            playlistName            = entry.playlistName,
                            position                = entry.position,
                            originFingerprint       = entry.originFingerprint,
                            sourcePlaylistId        = entry.sourcePlaylistId,
                            sourcePlaylistCreatedAt = entry.sourcePlaylistCreatedAt,
                            sourcePlaylistUpdatedAt = entry.sourcePlaylistUpdatedAt,
                        )
                    )
                    pendingPlaylistEntriesInserted++
                }
            }

            val favoritesInBackup = backup.trackStats.count { it.isFavorite }
            val matchedFavorites  = match.matchedRows.count { (_, stat) -> stat.isFavorite }

            WavdropBackupImportApplyResult(
                matchedTracks         = match.matchedRows.size,
                unmatchedTracks       = match.unmatchedCount,
                matchDiagnostics      = match.diagnostics,
                statsUpdated          = statsUpdated,
                statsRetainedLocal    = statsRetainedLocal,
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
                eventsSkippedDuplicate        = eventPlan.skippedDuplicate,
                eventsSkippedUnmatched        = eventPlan.skippedUnmatched,
                currentMonthEventsRestored    = eventPlan.currentMonthRestored,
                pendingTracksPreserved        = pendingTracksInserted,
                pendingEventsPreserved        = pendingEventsInserted,
                pendingPlaylistEntriesPreserved = pendingPlaylistEntriesInserted,
            )
        }

        // No-op result: nothing was written. Return immediately — no diagnostic log,
        // no preferences-skipped decoration; the result flag drives a calm UI state.
        if (dbResult.isNoOp) return dbResult

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

        // Merge Restore does not apply preferences (P1-B / contract §8.2).
        // Preferences are preserved in the backup and visible in preview, but are not
        // written to DataStore during a merge. Recovery Restore (P2) will apply them.
        val preferencesSkipped = backup.preferences != null

        val warnings = mutableListOf<String>()
        if (dbResult.playlistEntriesUnmatched > 0) {
            warnings += "Some playlist songs could not be matched to your current library."
        }

        return dbResult.copy(
            preferencesRestored            = false,
            preferencesSkipped             = preferencesSkipped,
            needsAutoBackupFolderSelection = false,
            launcherIconRestored           = false,
            warnings                       = warnings.distinct(),
        )
    }

    /**
     * Read-only preview: queries DB snapshots and delegates to [WavdropMergePreviewAnalyzer]
     * to determine whether the backup contains any data that would actually be written.
     * No data is modified. Called during the preview phase to drive the Apply button and
     * the no-op explanation notice.
     */
    suspend fun previewMerge(backup: WavdropBackup): WavdropMergePreviewAnalyzer.Result {
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

        val existingStats = trackStatsDao.getAllStatsSnapshot().associateBy { it.songId }

        val existingEventFingerprints: Set<String> = if (backup.listenEvents.isNotEmpty()) {
            val minMs = backup.listenEvents.minOf { it.occurredAt }
            val maxMs = backup.listenEvents.maxOf { it.occurredAt }
            trackListenEventDao.getInRangeSnapshot(minMs, maxMs)
                .mapTo(HashSet()) { "${it.songId}|${it.occurredAt}|${it.eventType}|${it.listenedMs}" }
        } else {
            emptySet()
        }

        val existingEventIds: Set<String> = if (backup.listenEvents.isNotEmpty()) {
            val minMs = backup.listenEvents.minOf { it.occurredAt }
            val maxMs = backup.listenEvents.maxOf { it.occurredAt }
            trackListenEventDao.getEventIdsInRangeSnapshot(minMs, maxMs).toHashSet()
        } else {
            emptySet()
        }

        val existingBaselines = importBaselineDao.getAllImportBaselinesSnapshot()

        val existingLyrics: Map<WavdropMergePreviewAnalyzer.ExistingLyricsKey, Long> =
            lyricsOverrideDao.getAllSnapshot()
                .associate { WavdropMergePreviewAnalyzer.ExistingLyricsKey(it.songId, it.contentUri) to it.updatedAt }

        val existingPlaylists: Map<String, Set<Long>> = if (backup.playlists.isNotEmpty()) {
            val result = mutableMapOf<String, Set<Long>>()
            for (backupPlaylist in backup.playlists) {
                val name = backupPlaylist.name.trim()
                if (name.isBlank()) continue
                val playlist = playlistDao.findByName(name)
                result[name] = if (playlist == null) {
                    emptySet()
                } else {
                    playlistDao.getSongsForPlaylistSnapshot(playlist.playlistId)
                        .mapTo(HashSet()) { it.songId }
                }
            }
            result
        } else {
            emptyMap()
        }

        val existingQuarantineOriginKeys: Set<String> =
            pendingTrackDao.getAllOriginKeys().toHashSet()

        return WavdropMergePreviewAnalyzer.analyze(
            backup                       = backup,
            currentSongs                 = currentSongs,
            existingQuarantineOriginKeys = existingQuarantineOriginKeys,
            existingStats                = existingStats,
            existingEventFingerprints    = existingEventFingerprints,
            existingEventIds             = existingEventIds,
            existingBaselines            = existingBaselines,
            existingLyrics               = existingLyrics,
            existingPlaylists            = existingPlaylists,
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
