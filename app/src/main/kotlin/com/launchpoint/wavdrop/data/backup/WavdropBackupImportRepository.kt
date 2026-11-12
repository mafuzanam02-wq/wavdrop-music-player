package com.launchpoint.wavdrop.data.backup

import android.util.Log
import androidx.room.withTransaction
import com.launchpoint.wavdrop.BuildConfig
import com.launchpoint.wavdrop.data.local.WavdropDatabase
import com.launchpoint.wavdrop.data.local.dao.ImportBaselineDao
import com.launchpoint.wavdrop.data.local.dao.LyricsOverrideDao
import com.launchpoint.wavdrop.data.local.dao.PendingBackupExtensionDao
import com.launchpoint.wavdrop.data.local.dao.PendingTrackDao
import com.launchpoint.wavdrop.data.local.dao.PlaylistDao
import com.launchpoint.wavdrop.data.local.dao.SongDao
import com.launchpoint.wavdrop.data.local.dao.TrackListenEventDao
import com.launchpoint.wavdrop.data.local.dao.TrackStatsDao
import com.launchpoint.wavdrop.data.local.entity.LyricsOverrideEntity
import com.launchpoint.wavdrop.data.local.entity.PendingBackupExtensionEntity
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
    private val pendingBackupExtensionDao: PendingBackupExtensionDao,
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

    suspend fun applyImport(
        backup: WavdropBackup,
        onStage: ((String) -> Unit)? = null,
    ): WavdropBackupImportApplyResult {
        val t0 = System.currentTimeMillis()
        if (BuildConfig.DEBUG) Log.d(APPLY_TAG, "start rootStats=${backup.trackStats.size} " +
            "rootEvents=${backup.listenEvents.size} overlay=${backup.desktopOverlay != null} " +
            "overlayStats=${backup.desktopOverlay?.trackStats?.size ?: 0} " +
            "overlayEvents=${backup.desktopOverlay?.listenEvents?.size ?: 0}")
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
            val songResolution = WavdropBackupStatsMatcher.resolveBackupSongs(backup, currentSongs)
            val resolvedBySongId = songResolution.resolved

            // Pre-load current stats for all matched songs to compute reporting deltas
            // without N individual queries inside the loop; also needed for the
            // authoritative mergeability recheck (§8.2 apply-side).
            val currentStatsById = trackStatsDao.getAllStatsSnapshot().associateBy { it.songId }

            // Union root + overlay event timestamps; same logic as previewMerge via the shared helper.
            val dedupSnapshot = loadEventDedupSnapshot(backup)
            val existingEventFingerprints = dedupSnapshot.fingerprints
            val existingEventIds = dedupSnapshot.eventIds
            val existingListenEvents = dedupSnapshot.existing

            val existingBaselines = importBaselineDao.getAllImportBaselinesSnapshot()

            val existingQuarantineOriginKeys: Set<String> =
                pendingTrackDao.getAllOriginKeys().toHashSet()
            val quarantineFp = QuarantinePlanner.backupFingerprint(backup)
            val storedQuarantineEvents = storedQuarantineEventsFor(backup, quarantineFp, existingQuarantineOriginKeys)

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

            val backupSongById = backup.songs.associateBy { it.id }
            val linkResolver = BackupSongLinkResolver(currentSongs, resolvedBySongId, songResolution.rejectedLocalIds)

            var eventPlan = ListenEventRestorePlanner.Plan(
                toInsert = emptyList(), eventsInBackup = 0, restored = 0,
                skippedDuplicate = 0, skippedUnmatched = 0, skippedInvalidType = 0,
                currentMonthRestored = 0,
            )

            if (backup.listenEvents.isNotEmpty()) {
                val tEvents = System.currentTimeMillis()
                eventPlan = ListenEventRestorePlanner.plan(
                    events = backup.listenEvents,
                    resolveSong = { event ->
                        linkResolver.resolve(
                            backupSongId = event.songId,
                            contentUri = event.contentUri,
                            title = event.title,
                            artist = event.artist,
                            album = event.album,
                        )
                    },
                    existing = existingListenEvents,
                )
                if (BuildConfig.DEBUG) Log.d(APPLY_TAG, "root event plan done " +
                    "toInsert=${eventPlan.toInsert.size} skipped=${eventPlan.skippedTotal} " +
                    "planMs=${System.currentTimeMillis() - tEvents}ms t=${System.currentTimeMillis() - t0}ms")
            }

            val eventFingerprintsAfterAndroidRestore = existingEventFingerprints.toMutableSet().apply {
                eventPlan.toInsert.forEach {
                    add("${it.songId}|${it.occurredAt}|${it.eventType}|${it.listenedMs}")
                }
            }
            val eventIdsAfterAndroidRestore = existingEventIds.toMutableSet().apply {
                eventPlan.toInsert.mapNotNullTo(this) { it.eventId }
            }

            val precomputedDesktopOverlayPlan = backup.desktopOverlay?.let { overlay ->
                val tOverlay = System.currentTimeMillis()
                DesktopOverlayRestorePlanner.plan(
                    overlay = overlay,
                    currentSongs = currentSongs,
                    currentStats = currentStatsById,
                    existingEventFingerprints = eventFingerprintsAfterAndroidRestore,
                    existingEventIds = eventIdsAfterAndroidRestore,
                ).also { plan ->
                    if (BuildConfig.DEBUG) Log.d(APPLY_TAG, "overlay planner done " +
                        "matched=${plan.matchedStats.size} unresolved=${plan.unresolvedStats.size} " +
                        "toInsert=${plan.eventPlan.toInsert.size} " +
                        "planMs=${System.currentTimeMillis() - tOverlay}ms t=${System.currentTimeMillis() - t0}ms")
                }
            }
            if (BuildConfig.DEBUG) Log.d(APPLY_TAG, "snapshots loaded songs=${currentSongs.size} " +
                "stats=${currentStatsById.size} fingerprints=${existingEventFingerprints.size} " +
                "eventIds=${existingEventIds.size} t=${System.currentTimeMillis() - t0}ms")

            // ── 2. Authoritative no-op recheck — same shared analyzer used at preview.
            //       Runs inside the transaction so it sees the current committed state;
            //       preview state may have been stale if playback or scans ran between
            //       preview and apply.
            val mergeCheck = WavdropMergePreviewAnalyzer.analyze(
                backup                       = backup,
                currentSongs                 = currentSongs,
                existingQuarantineOriginKeys = existingQuarantineOriginKeys,
                backupFingerprint            = quarantineFp,
                storedQuarantineEvents       = storedQuarantineEvents,
                existingStats                = currentStatsById,
                existingEventFingerprints    = existingEventFingerprints,
                existingEventIds             = existingEventIds,
                existingListenEvents         = existingListenEvents,
                existingBaselines            = existingBaselines,
                existingLyrics               = existingLyricsMap,
                existingPlaylists            = existingPlaylistsMap,
                precomputedDesktopOverlayPlan = precomputedDesktopOverlayPlan,
            )
            if (BuildConfig.DEBUG) Log.d(APPLY_TAG, "mergeCheck done " +
                "hasMergeable=${mergeCheck.hasMergeableData} t=${System.currentTimeMillis() - t0}ms")
            // P5-STATS-17B: quarantined history carried by the backup. It never touches live data, but it IS persistent state, so a
            // backup whose only news is pending history must not be treated as a no-op (that is exactly the "Backup B" case).
            val pendingHistory = backup.pendingHistory?.takeIf { !it.isEmpty }
            val pendingStore = PendingHistoryStore(pendingTrackDao)
            if (!mergeCheck.hasMergeableData) {
                val onlyPending = pendingHistory?.let { pendingStore.restore(it) }
                if (onlyPending == null || onlyPending.rowsInserted == 0) {
                    // No persistent state change was possible (or the pending history was already fully present). Nothing was written.
                    return@withTransaction WavdropBackupImportApplyResult(
                        matchedTracks   = match.matchedRows.size,
                        unmatchedTracks = match.unmatchedCount,
                        statsUpdated    = 0,
                        isNoOp          = true,
                    )
                }
                return@withTransaction WavdropBackupImportApplyResult(
                    matchedTracks   = match.matchedRows.size,
                    unmatchedTracks = match.unmatchedCount,
                    statsUpdated    = 0,
                    pendingTracksPreserved = onlyPending.tracksInserted,
                    pendingEventsPreserved = onlyPending.eventsInserted,
                    pendingPlaylistEntriesPreserved = onlyPending.playlistEntriesInserted,
                )
            }

            // ── 3. Proceed with writes ────────────────────────────────────────────────
            onStage?.invoke("Restoring song matches…")
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

            if (BuildConfig.DEBUG) Log.d(APPLY_TAG, "stats merged matched=${match.matchedRows.size} " +
                "updated=$statsUpdated t=${System.currentTimeMillis() - t0}ms")

            // Shared resolver for song-linked rows (lyrics, playlist entries, events):
            // tier-matched songId first, then URI, then tags only when unambiguous.

            onStage?.invoke("Restoring lyrics…")
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

            if (BuildConfig.DEBUG) Log.d(APPLY_TAG, "lyrics restored=$lyricsRestored " +
                "unmatched=$lyricsUnmatched t=${System.currentTimeMillis() - t0}ms")

            onStage?.invoke("Restoring playlists…")
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

                if (entryPlan.toAdd.isNotEmpty()) {
                    playlistDao.insertSongs(
                        entryPlan.toAdd.map { entry ->
                            PlaylistSongEntity(
                                playlistId = playlistId,
                                songId     = entry.songId,
                                position   = entry.position,
                            )
                        }
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

            if (BuildConfig.DEBUG) Log.d(APPLY_TAG, "playlists restored=$playlistsRestored " +
                "songs=$playlistSongsRestored t=${System.currentTimeMillis() - t0}ms")

            onStage?.invoke("Restoring listening history…")
            if (eventPlan.toInsert.isNotEmpty()) {
                trackListenEventDao.insertAll(eventPlan.toInsert)
                if (BuildConfig.DEBUG) Log.d(APPLY_TAG, "event insertAll done " +
                    "count=${eventPlan.toInsert.size} t=${System.currentTimeMillis() - t0}ms")
            }
            val eventsRestored = eventPlan.restored
            val eventsSkipped  = eventPlan.skippedTotal

            var desktopOverlayStatsUpdated = 0
            var desktopOverlayFavoritesRestored = 0
            var desktopOverlayEventsRestored = 0
            var desktopOverlayEventsSkipped = 0
            var desktopOverlayEventsSkippedDuplicate = 0
            var desktopOverlayEventsSkippedUnmatched = 0
            var desktopOverlayCurrentMonthEventsRestored = 0
            var desktopOverlayUnmatchedTracks = 0
            var desktopOverlayFavoritesInBackup = 0
            var desktopOverlayFavoritesUnmatched = 0

            backup.desktopOverlay?.let { overlay ->
                onStage?.invoke("Restoring Desktop activity…")
                desktopOverlayFavoritesInBackup = overlay.trackStats.count { it.favorite }
                pendingBackupExtensionDao.upsert(
                    PendingBackupExtensionEntity(
                        rootName = DESKTOP_OVERLAY_ROOT,
                        rawJson = overlay.rawJson,
                        importedAt = importedAt,
                    )
                )
                // Reuse the stats snapshot already loaded at the top of the transaction.
                // currentStatsById reflects the state before Android stats were merged,
                // which is acceptable for the reporting counters (statsWillIncrease /
                // favoriteWillApply) — actual DB writes are always max-merge idempotent.
                val overlayPlan = requireNotNull(precomputedDesktopOverlayPlan)
                for (row in overlayPlan.matchedStats) {
                    trackStatsDao.insertIfAbsent(
                        TrackStatsEntity(songId = row.song.id, contentUri = row.song.uri)
                    )
                    trackStatsDao.mergeMaxStats(
                        songId = row.song.id,
                        importedPlayCount = row.stats.playCount,
                        importedSkipCount = row.stats.skipCount,
                        importedListeningTimeMs = row.stats.totalListeningTimeMs,
                        importedLastPlayedAt = row.stats.lastPlayedAt,
                        importedLastListenedAt = row.stats.lastListenedAt,
                    )
                    if (row.statsWillIncrease) desktopOverlayStatsUpdated++
                    if (row.stats.favorite) {
                        trackStatsDao.setFavorite(row.song.id, true)
                        if (row.favoriteWillApply) desktopOverlayFavoritesRestored++
                    }
                }
                if (overlayPlan.eventPlan.toInsert.isNotEmpty()) {
                    trackListenEventDao.insertAll(overlayPlan.eventPlan.toInsert)
                    if (BuildConfig.DEBUG) Log.d(APPLY_TAG, "overlay event insertAll done " +
                        "count=${overlayPlan.eventPlan.toInsert.size} t=${System.currentTimeMillis() - t0}ms")
                }
                desktopOverlayEventsRestored = overlayPlan.eventPlan.restored
                desktopOverlayEventsSkipped = overlayPlan.eventPlan.skippedTotal
                desktopOverlayEventsSkippedDuplicate = overlayPlan.eventPlan.skippedDuplicate
                desktopOverlayEventsSkippedUnmatched = overlayPlan.eventPlan.skippedUnmatched
                desktopOverlayCurrentMonthEventsRestored = overlayPlan.eventPlan.currentMonthRestored
                desktopOverlayUnmatchedTracks = overlayPlan.unresolvedStats.size
                desktopOverlayFavoritesUnmatched =
                    overlayPlan.unresolvedStats.count { it.favorite }
            }

            onStage?.invoke("Restoring import baselines…")
            if (BuildConfig.DEBUG) Log.d(APPLY_TAG, "events done t=${System.currentTimeMillis() - t0}ms")
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

            onStage?.invoke("Finishing import…")
            if (BuildConfig.DEBUG) Log.d(APPLY_TAG, "baselines restored=${baselinePlan.restored} " +
                "t=${System.currentTimeMillis() - t0}ms")
            // ── Quarantine: preserve unmatched backup history ─────────────────────────
            val fp = quarantineFp
            val quarantinePlan = QuarantinePlanner.plan(
                backup                = backup,
                backupFingerprint     = fp,
                resolvedBackupSongIds = resolvedBySongId.keys,
                existingOriginKeys    = existingQuarantineOriginKeys,
            )
            val now = System.currentTimeMillis()
            val pendingEventWriter = PendingEventWriter(pendingTrackDao)
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

                // P5-STATS-18C: occurrence-preserving write; counts what was actually stored, not insert attempts.
                pendingEventsInserted += pendingEventWriter.write(pendingId, QuarantinePlanner.eventItems(candidate.events)).inserted

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

            // P5-STATS-18C: tracks that were quarantined by an earlier import still get their missing event occurrences (events only:
            // their stats, lyrics, baselines, playlist entries and metadata are never touched). A conflict rolls the transaction back.
            for (existing in quarantinePlan.existingTrackEvents) {
                val pendingId = pendingTrackDao.getPendingId(existing.originKey) ?: continue
                pendingEventsInserted += pendingEventWriter.write(pendingId, QuarantinePlanner.eventItems(existing.events)).inserted
            }

            // Carried-forward quarantine (P5-STATS-17B): additive, original keys, conflict => whole transaction rolls back.
            val pendingRestored = pendingHistory?.let { pendingStore.restore(it) } ?: PendingHistoryRestoreResult()

            val favoritesInBackup = backup.trackStats.count { it.isFavorite }
            val matchedFavorites  = match.matchedRows.count { (_, stat) -> stat.isFavorite }

            WavdropBackupImportApplyResult(
                matchedTracks         = match.matchedRows.size,
                unmatchedTracks       = match.unmatchedCount + desktopOverlayUnmatchedTracks,
                matchDiagnostics      = match.diagnostics,
                statsUpdated          = statsUpdated + desktopOverlayStatsUpdated,
                statsRetainedLocal    = statsRetainedLocal,
                lyricsRestored        = lyricsRestored,
                lyricsInBackup        = backup.lyricsOverrides.size,
                lyricsUnmatched       = lyricsUnmatched,
                favoritesRestored     = favoritesRestored + desktopOverlayFavoritesRestored,
                favoritesInBackup     = favoritesInBackup + desktopOverlayFavoritesInBackup,
                favoritesUnmatched    = favoritesInBackup - matchedFavorites +
                    desktopOverlayFavoritesUnmatched,
                playlistsRestored     = playlistsRestored,
                playlistsInBackup     = backup.playlists.size,
                playlistSongsRestored = playlistSongsRestored,
                playlistEntriesInBackup  = backup.playlists.sumOf { it.songs.size },
                playlistEntriesUnmatched = playlistEntriesUnmatched,
                playlistRestoreSummaries = playlistSummaries,
                eventsRestored        = eventsRestored + desktopOverlayEventsRestored,
                baselinesRestored     = baselinePlan.restored,
                eventsSkipped         = eventsSkipped + desktopOverlayEventsSkipped,
                eventsSkippedDuplicate        =
                    eventPlan.skippedDuplicate + desktopOverlayEventsSkippedDuplicate,
                eventsSkippedUnmatched        =
                    eventPlan.skippedUnmatched + desktopOverlayEventsSkippedUnmatched,
                eventsSkippedConflict         = eventPlan.skippedConflict,
                currentMonthEventsRestored    =
                    eventPlan.currentMonthRestored + desktopOverlayCurrentMonthEventsRestored,
                pendingTracksPreserved        = pendingTracksInserted + pendingRestored.tracksInserted,
                pendingEventsPreserved        = pendingEventsInserted + pendingRestored.eventsInserted,
                pendingPlaylistEntriesPreserved = pendingPlaylistEntriesInserted + pendingRestored.playlistEntriesInserted,
            )
        }

        if (BuildConfig.DEBUG) Log.d(APPLY_TAG, "transaction done isNoOp=${dbResult.isNoOp} " +
            "totalMs=${System.currentTimeMillis() - t0}ms")
        // No-op result: nothing was written. Return immediately — no diagnostic log,
        // no preferences-skipped decoration; the result flag drives a calm UI state.
        if (dbResult.isNoOp) return dbResult

        // Diagnostics: explains "missing plays" after restore (unmatched tracks are the
        // usual cause — URI changes after reinstall plus tag mismatch).
        val diag = dbResult.matchDiagnostics
        if (BuildConfig.DEBUG) Log.i(
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
     * The stored payload of every ID-bearing event whose id is in [incomingIds], wherever its timestamp lies (P5-STATS-18D1).
     *
     * `track_listen_events.eventId` has no index (and adding one needs a Room migration, which this fix deliberately avoids), so an
     * `IN (...)` lookup would re-scan the table once per chunk and is capped by SQLite's bound-parameter limit. Instead the ID-bearing
     * rows are streamed ONCE in rowid order, [EVENT_SNAPSHOT_PAGE] at a time (indexed rowid range, no sort), and only matches are kept.
     * Cost: one sequential pass, O(rows in the table); memory: O(|incomingIds|) - never the table. Skipped entirely when the backup
     * carries no ids. If the table holds the same id twice, the lowest rowid wins (the planner then reports any disagreement).
     */
    private suspend fun loadStoredPayloadsForIds(incomingIds: Set<String>): Map<String, ListenEventRestorePlanner.EventPayload> {
        return trackListenEventDao.scanPayloadsForIds(incomingIds)
    }

    /**
     * Loads existing event fingerprints and eventIds for the time range spanned by all
     * events in [backup] — both root listenEvents and desktopOverlay listenEvents.
     *
     * Unifying the range in one place ensures [previewMerge] and [applyImport] use
     * identical dedup sets. Querying with a per-section range (root-only) would miss DB
     * fingerprints for overlay events whose timestamps fall outside the root-event window,
     * causing duplicate overlay event insertion on apply.
     */
    private suspend fun loadEventDedupSnapshot(backup: WavdropBackup): EventDedupSnapshot {
        val rootTimes = backup.listenEvents.map { it.occurredAt }
        val overlayTimes = backup.desktopOverlay?.listenEvents
            ?.mapNotNull { it.occurredAt.takeIf { t -> t > 0L } }.orEmpty()
        val allTimes = rootTimes + overlayTimes
        if (allTimes.isEmpty()) return EventDedupSnapshot(emptySet(), emptySet(), ListenEventRestorePlanner.ExistingListenEvents.EMPTY)
        val minMs = allTimes.min()
        val maxMs = allTimes.max()
        // P5-STATS-18D1: stable ids are matched GLOBALLY (a stored id outside the backup's time window must still be seen), only for
        // the ids this backup actually carries, with a bounded-memory streaming pass.
        val byId = loadStoredPayloadsForIds(backup.listenEvents.mapNotNullTo(HashSet()) { it.eventId })
        // P5-STATS-18D: ONE keyset-paged pass over the indexed occurredAt range (never a whole-table or whole-range entity list).
        // Legacy (null-id) rows are counted per 5-field key and stay time-range scoped; the 4-field fingerprints / ids are kept
        // for the Desktop overlay planner (unchanged).
        val fingerprints = HashSet<String>()
        val eventIds = HashSet<String>()
        val legacy = HashMap<String, Int>()
        var afterAt = minMs
        var afterId = Long.MIN_VALUE
        while (true) {
            val page = trackListenEventDao.getRangePage(minMs, maxMs, afterAt, afterId, EVENT_SNAPSHOT_PAGE)
            if (page.isEmpty()) break
            for (r in page) {
                fingerprints += "${r.songId}|${r.occurredAt}|${r.eventType}|${r.listenedMs}"
                val rowEventId = r.eventId
                if (rowEventId != null) eventIds += rowEventId
                else legacy.merge(
                    ListenEventRestorePlanner.legacyKey(r.songId, r.occurredAt, r.eventType, r.listenedMs, r.durationMs), 1, Int::plus,
                )
            }
            if (page.size < EVENT_SNAPSHOT_PAGE) break
            afterAt = page.last().occurredAt
            afterId = page.last().id
        }
        return EventDedupSnapshot(fingerprints, eventIds, ListenEventRestorePlanner.ExistingListenEvents(byId, legacy))
    }

    /**
     * P5-STATS-18C1: the stored event rows of exactly those already-quarantined tracks this backup has events for (track-scoped indexed
     * range reads; unrelated tracks of the same snapshot are never read or compared).
     */
    private suspend fun storedQuarantineEventsFor(
        backup: WavdropBackup,
        fingerprint: String,
        existingOriginKeys: Set<String>,
    ): Map<String, List<PendingListenEventEntity>> {
        val out = HashMap<String, List<PendingListenEventEntity>>()
        for (songId in backup.listenEvents.mapTo(HashSet()) { it.songId }) {
            val key = QuarantinePlanner.originKey(fingerprint, songId)
            if (key in existingOriginKeys) out[key] = pendingTrackDao.getEventsInFingerprintRange("$key|", "$key}")
        }
        return out
    }

    /**
     * Read-only preview: queries DB snapshots and delegates to [WavdropMergePreviewAnalyzer]
     * to determine whether the backup contains any data that would actually be written.
     * No data is modified. Called during the preview phase to drive the Apply button and
     * the no-op explanation notice.
     */
    suspend fun previewMerge(
        backup: WavdropBackup,
        onStage: ((String) -> Unit)? = null,
    ): WavdropMergePreviewAnalyzer.Result {
        val t0 = System.currentTimeMillis()
        if (BuildConfig.DEBUG) Log.d(TAG, "previewMerge: start rootStats=${backup.trackStats.size} " +
            "rootEvents=${backup.listenEvents.size} overlay=${backup.desktopOverlay != null} " +
            "overlayStats=${backup.desktopOverlay?.trackStats?.size ?: 0} " +
            "overlayEvents=${backup.desktopOverlay?.listenEvents?.size ?: 0}")

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
        if (BuildConfig.DEBUG) Log.d(TAG, "previewMerge: snapshots loaded songs=${currentSongs.size} " +
            "stats=${existingStats.size} t=${System.currentTimeMillis() - t0}ms")
        onStage?.invoke("Loading your music library…")

        val dedupSnapshot = loadEventDedupSnapshot(backup)
        val existingEventFingerprints = dedupSnapshot.fingerprints
        val existingEventIds = dedupSnapshot.eventIds
        val existingListenEvents = dedupSnapshot.existing
        if (BuildConfig.DEBUG) Log.d(TAG, "previewMerge: fingerprints loaded count=${existingEventFingerprints.size} " +
            "eventIds=${existingEventIds.size} t=${System.currentTimeMillis() - t0}ms")
        onStage?.invoke("Checking listening history…")

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
        val quarantineFp = QuarantinePlanner.backupFingerprint(backup)
        val storedQuarantineEvents = storedQuarantineEventsFor(backup, quarantineFp, existingQuarantineOriginKeys)

        if (backup.desktopOverlay != null) {
            onStage?.invoke("Checking Desktop activity…")
        }
        if (BuildConfig.DEBUG) Log.d(TAG, "previewMerge: all snapshots ready — calling analyzer " +
            "t=${System.currentTimeMillis() - t0}ms")
        val result = WavdropMergePreviewAnalyzer.analyze(
            backup                       = backup,
            currentSongs                 = currentSongs,
            existingQuarantineOriginKeys = existingQuarantineOriginKeys,
            backupFingerprint            = quarantineFp,
            storedQuarantineEvents       = storedQuarantineEvents,
            existingStats                = existingStats,
            existingEventFingerprints    = existingEventFingerprints,
            existingEventIds             = existingEventIds,
            existingListenEvents         = existingListenEvents,
            existingBaselines            = existingBaselines,
            existingLyrics               = existingLyrics,
            existingPlaylists            = existingPlaylists,
            pendingHistoryHasNewData     = backup.pendingHistory?.let { PendingHistoryStore(pendingTrackDao).hasNewData(it) } == true,
        )
        if (BuildConfig.DEBUG) Log.d(TAG, "previewMerge: done hasMergeable=${result.hasMergeableData} " +
            "t=${System.currentTimeMillis() - t0}ms")
        return result
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

private data class EventDedupSnapshot(
    /** 4-field fingerprints and ids: consumed ONLY by the Desktop overlay planner (its rules are unchanged). */
    val fingerprints: Set<String>,
    val eventIds: Set<String>,
    /** Identity-aware view for the live Android event planner (P5-STATS-18D). */
    val existing: ListenEventRestorePlanner.ExistingListenEvents,
)

private const val EVENT_SNAPSHOT_PAGE = 5_000

private const val TAG = "WavdropRestore"
private const val APPLY_TAG = "WavdropImportApply"
