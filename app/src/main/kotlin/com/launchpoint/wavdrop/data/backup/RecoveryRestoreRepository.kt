package com.launchpoint.wavdrop.data.backup

import androidx.room.withTransaction
import com.launchpoint.wavdrop.data.local.WavdropDatabase
import com.launchpoint.wavdrop.data.local.dao.ImportBaselineDao
import com.launchpoint.wavdrop.data.local.dao.LyricsOverrideDao
import com.launchpoint.wavdrop.data.local.dao.PendingBackupExtensionDao
import com.launchpoint.wavdrop.data.local.dao.PendingTrackDao
import com.launchpoint.wavdrop.data.local.dao.PlaylistDao
import com.launchpoint.wavdrop.data.local.dao.SongDao
import com.launchpoint.wavdrop.data.local.dao.TrackListenEventDao
import com.launchpoint.wavdrop.data.local.dao.TrackStatsDao
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
import com.launchpoint.wavdrop.data.model.Song
import javax.inject.Inject
import javax.inject.Singleton

/** The database half of a Recovery Restore. Called only by [RecoveryRestoreOrchestrator], after a verified safety snapshot. */
interface RecoveryDatabaseApplier {
    /** One Room transaction: it either fully applies or leaves the database exactly as it was. Throws on failure. */
    suspend fun applyRecovery(backup: WavdropBackup): WavdropBackupImportApplyResult
}

/** Concise, read-only consequences of choosing Recovery for a given backup (shown before the user confirms). */
data class RecoveryImpact(
    val matchedTracks: Int,
    val unmatchedTracks: Int,
    val localStatsRowsReplaced: Int,
    val localFavoritesToClear: Int,
    val localPlaylistsToRemove: Int,
    val playlistsToRestore: Int,
    val localEventsNotInBackup: Int,
    val eventsToRestore: Int,
    val localLyricsReplaced: Int,
    val lyricsToRestore: Int,
)

/**
 * Authoritative database Recovery. Everything destructive happens inside ONE `db.withTransaction`, after the pure
 * [RecoveryRestorePlanner] has made every matching decision from the live library read inside the same transaction (the preview
 * can be stale; nothing preview-derived is trusted here).
 *
 * What is cleared is limited to what a Wavdrop backup exports (and so what the safety snapshot contains): track stats,
 * exported-source listen events, lyrics overrides, import baselines, WavDrop playlists and the desktopOverlay extension.
 * NEVER touched: the songs table / MediaStore / audio files, device-local TrackIdentity, listen events from non-exported
 * sources, and the pending/quarantine tables (their contents are not exported, so deleting them could not be undone from the
 * snapshot). Unmatched backup history is added to the quarantine with the same origin-key dedup Merge uses.
 */
@Singleton
class RecoveryRestoreRepository @Inject constructor(
    private val db: WavdropDatabase,
    private val songDao: SongDao,
    private val trackStatsDao: TrackStatsDao,
    private val lyricsOverrideDao: LyricsOverrideDao,
    private val importBaselineDao: ImportBaselineDao,
    private val playlistDao: PlaylistDao,
    private val trackListenEventDao: TrackListenEventDao,
    private val pendingTrackDao: PendingTrackDao,
    private val pendingBackupExtensionDao: PendingBackupExtensionDao,
) : RecoveryDatabaseApplier {

    /** Test seam only: invoked between write stages inside the transaction so a test can prove a mid-way failure rolls back. */
    internal var stageHook: ((String) -> Unit)? = null

    override suspend fun applyRecovery(backup: WavdropBackup): WavdropBackupImportApplyResult =
        db.withTransaction {
            // ── 1. Read + decide. No writes yet. ──────────────────────────────────────────────────────────────────
            val currentSongs = loadCurrentSongs()
            val plan = RecoveryRestorePlanner.plan(backup, currentSongs)
            val fp = QuarantinePlanner.backupFingerprint(backup)
            val quarantinePlan = QuarantinePlanner.plan(
                backup                = backup,
                backupFingerprint     = fp,
                resolvedBackupSongIds = plan.resolvedBySongId.keys,
                existingOriginKeys    = pendingTrackDao.getAllOriginKeys().toHashSet(),
            )
            val restoredFavoriteIds = plan.stats.filter { it.isFavorite }.mapTo(HashSet()) { it.songId }
            val favoritesCleared = trackStatsDao.getAllStatsSnapshot()
                .count { it.isFavorite && it.songId !in restoredFavoriteIds }
            val localPlaylistCount = playlistDao.getAllPlaylistsSnapshot().size
            stage("planned")

            // ── 2. Authoritative clear (scoped to exported state). ────────────────────────────────────────────────
            trackStatsDao.deleteAllTrackStatsForRecovery()
            stage("statsCleared")
            val eventsRemoved = trackListenEventDao.deleteExportedEventsForRecovery(EXPORTED_EVENT_SOURCES)
            stage("eventsCleared")
            lyricsOverrideDao.deleteAllLyricsForRecovery()
            importBaselineDao.deleteAllBaselinesForRecovery()
            stage("lyricsAndBaselinesCleared")
            playlistDao.clearWavdropPlaylistsForRecovery()
            pendingBackupExtensionDao.deleteByRootNameForRecovery(DESKTOP_OVERLAY_ROOT)
            stage("playlistsCleared")

            // ── 3. Write the backup-authoritative state. ──────────────────────────────────────────────────────────
            if (plan.stats.isNotEmpty()) trackStatsDao.insertAllForRecovery(plan.stats)
            stage("statsWritten")
            if (plan.eventPlan.toInsert.isNotEmpty()) trackListenEventDao.insertAll(plan.eventPlan.toInsert)
            stage("eventsWritten")
            plan.lyrics.forEach { lyricsOverrideDao.upsert(it) }
            plan.baselinePlan.toUpsert.forEach { importBaselineDao.upsertBaseline(it) }
            stage("lyricsAndBaselinesWritten")

            val now = System.currentTimeMillis()
            for (p in plan.playlists) {
                val playlistId = playlistDao.insertPlaylist(
                    PlaylistEntity(name = p.name, createdAt = p.createdAt, updatedAt = p.updatedAt),
                )
                if (p.songIds.isNotEmpty()) {
                    playlistDao.insertSongs(
                        p.songIds.mapIndexed { index, songId ->
                            PlaylistSongEntity(playlistId = playlistId, songId = songId, position = index)
                        },
                    )
                }
            }
            stage("playlistsWritten")

            plan.desktopOverlayRawJson?.let { raw ->
                pendingBackupExtensionDao.upsert(
                    PendingBackupExtensionEntity(rootName = DESKTOP_OVERLAY_ROOT, rawJson = raw, importedAt = now),
                )
            }

            val quarantined = writeQuarantine(quarantinePlan, fp, now)
            stage("quarantineWritten")

            WavdropBackupImportApplyResult(
                matchedTracks            = plan.matchedRows.size,
                unmatchedTracks          = plan.unmatchedStatsRows,
                matchDiagnostics         = plan.diagnostics,
                statsUpdated             = plan.stats.size,
                lyricsRestored           = plan.lyrics.size,
                lyricsInBackup           = plan.lyricsInBackup,
                lyricsUnmatched          = plan.lyricsUnmatched,
                favoritesRestored        = plan.favoritesRestored,
                favoritesInBackup        = backup.trackStats.count { it.isFavorite },
                favoritesUnmatched       = backup.trackStats.count { it.isFavorite } - plan.favoritesRestored,
                playlistsRestored        = plan.playlists.size,
                playlistsInBackup        = backup.playlists.size,
                playlistSongsRestored    = plan.playlistEntriesRestored,
                playlistEntriesInBackup  = plan.playlistEntriesInBackup,
                playlistEntriesUnmatched = plan.playlistEntriesUnmatched,
                eventsRestored           = plan.eventPlan.restored,
                eventsSkipped            = plan.eventPlan.skippedTotal,
                eventsSkippedDuplicate   = plan.eventPlan.skippedDuplicate,
                eventsSkippedUnmatched   = plan.eventPlan.skippedUnmatched,
                currentMonthEventsRestored = plan.eventPlan.currentMonthRestored,
                baselinesRestored        = plan.baselinePlan.restored,
                pendingTracksPreserved   = quarantined.tracks,
                pendingEventsPreserved   = quarantined.events,
                pendingPlaylistEntriesPreserved = quarantined.playlistEntries,
                restoreMode              = BackupRestoreMode.RECOVERY,
                recovery                 = RecoveryRestoreSummary(
                    matchedTracks              = plan.matchedRows.size,
                    unmatchedTracksPreserved   = quarantined.tracks,
                    statsRestored              = plan.stats.size,
                    favoritesRestored          = plan.favoritesRestored,
                    favoritesCleared           = favoritesCleared,
                    eventsRestored             = plan.eventPlan.restored,
                    localEventsReplaced        = eventsRemoved,
                    playlistsRestored          = plan.playlists.size,
                    localPlaylistsRemoved      = localPlaylistCount,
                    playlistEntriesRestored    = plan.playlistEntriesRestored,
                    playlistEntriesPreserved   = quarantined.playlistEntries,
                    lyricsRestored             = plan.lyrics.size,
                    lyricsPreservedUnmatched   = plan.lyricsUnmatched,
                    baselinesRestored          = plan.baselinePlan.restored,
                    desktopOverlayStored       = plan.desktopOverlayRawJson != null,
                ),
            )
        }

    /** Read-only impact summary for the preview. Uses the same planner as [applyRecovery]; writes nothing. */
    suspend fun previewImpact(backup: WavdropBackup): RecoveryImpact {
        val plan = RecoveryRestorePlanner.plan(backup, loadCurrentSongs())
        val restoredFavoriteIds = plan.stats.filter { it.isFavorite }.mapTo(HashSet()) { it.songId }
        val localStats = trackStatsDao.getAllStatsSnapshot()
        val plannedFingerprints = plan.eventPlan.toInsert
            .mapTo(HashSet()) { "${it.songId}|${it.occurredAt}|${it.eventType}|${it.listenedMs}" }
        val plannedIds = plan.eventPlan.toInsert.mapNotNullTo(HashSet()) { it.eventId }
        val localOnlyEvents = trackListenEventDao.getAllSnapshot()
            .filter { BackupEventExportRules.shouldExport(it.source) }
            .count { e ->
                !(e.eventId != null && e.eventId in plannedIds) &&
                    "${e.songId}|${e.occurredAt}|${e.eventType}|${e.listenedMs}" !in plannedFingerprints
            }
        val restoredPlaylistNames = plan.playlists.mapTo(HashSet()) { it.name.lowercase() }
        val localPlaylistsRemoved = playlistDao.getAllPlaylistsSnapshot()
            .count { it.name.trim().lowercase() !in restoredPlaylistNames }
        return RecoveryImpact(
            matchedTracks          = plan.matchedRows.size,
            unmatchedTracks        = plan.unmatchedStatsRows,
            localStatsRowsReplaced = localStats.size,
            localFavoritesToClear  = localStats.count { it.isFavorite && it.songId !in restoredFavoriteIds },
            localPlaylistsToRemove = localPlaylistsRemoved,
            playlistsToRestore     = plan.playlists.size,
            localEventsNotInBackup = localOnlyEvents,
            eventsToRestore        = plan.eventPlan.restored,
            localLyricsReplaced    = lyricsOverrideDao.getAllSnapshot().size,
            lyricsToRestore        = plan.lyrics.size,
        )
    }

    private suspend fun loadCurrentSongs(): List<Song> = songDao.getAllSongsSnapshot().map { e ->
        Song(
            id = e.id, title = e.title, artist = e.artist, album = e.album, albumId = e.albumId,
            duration = e.duration, uri = e.uri, dateAdded = e.dateAdded, trackNumber = e.trackNumber,
            year = e.year, folderPath = e.folderPath, folderName = e.folderName,
        )
    }

    private fun stage(name: String) {
        stageHook?.invoke(name)
    }

    private data class QuarantineCounts(val tracks: Int, val events: Int, val playlistEntries: Int)

    /** Same two-pass pending write Merge performs (track first, then its sub-records), with the same origin-key idempotence. */
    private suspend fun writeQuarantine(plan: QuarantinePlanner.Plan, fp: String, now: Long): QuarantineCounts {
        var tracks = 0
        var events = 0
        var playlistEntries = 0
        for (candidate in plan.newCandidates) {
            val rowId = pendingTrackDao.insertTrack(
                PendingTrackEntity(
                    originKey         = candidate.originKey,
                    backupFingerprint = fp,
                    backupSongId      = candidate.backupSongId.toString(),
                    title             = candidate.title,
                    artist            = candidate.artist,
                    album             = candidate.album,
                    duration          = candidate.duration,
                    trackNumber       = candidate.trackNumber,
                    year              = candidate.year,
                    sourceUri         = candidate.sourceUri,
                    sourceFolderPath  = candidate.sourceFolderPath,
                    restoredAt        = now,
                ),
            )
            val pendingId = if (rowId != -1L) rowId else pendingTrackDao.getPendingId(candidate.originKey) ?: continue
            tracks++
            candidate.stats?.let { s ->
                pendingTrackDao.insertStats(
                    PendingTrackStatsEntity(
                        pendingId            = pendingId,
                        playCount            = s.playCount,
                        skipCount            = s.skipCount,
                        lastPlayedAt         = s.lastPlayedAt,
                        totalListeningTimeMs = s.totalListeningTimeMs,
                        isFavorite           = s.isFavorite,
                        lastListenedAt       = s.lastListenedAt,
                    ),
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
                    ),
                )
                events++
            }
            candidate.lyrics?.let { (override, fingerprint) ->
                pendingTrackDao.insertLyrics(
                    PendingLyricsOverrideEntity(
                        pendingId         = pendingId,
                        lyrics            = override.lyrics,
                        updatedAt         = override.updatedAt,
                        originFingerprint = fingerprint,
                    ),
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
                    ),
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
                    ),
                )
                playlistEntries++
            }
        }
        return QuarantineCounts(tracks, events, playlistEntries)
    }

    private companion object {
        /** Exactly the sources [BackupEventExportRules.shouldExport] accepts: what the safety snapshot contains. */
        val EXPORTED_EVENT_SOURCES = listOf(
            TrackListenEventEntity.SOURCE_WAVDROP_PLAYBACK,
            TrackListenEventEntity.SOURCE_MANUAL_RESTORE,
            TrackListenEventEntity.SOURCE_DESKTOP_PLAYBACK,
        )
    }
}
