package com.launchpoint.wavdrop.data.backup

import com.launchpoint.wavdrop.BuildConfig
import com.launchpoint.wavdrop.data.local.entity.ImportBaselineEntity
import com.launchpoint.wavdrop.data.local.entity.TrackStatsEntity
import com.launchpoint.wavdrop.data.model.Song

/**
 * Pure mergeability assessment for Android Wavdrop backup merge preview (P1-B.1).
 *
 * Given the backup and read-only snapshots of current DB state, determines whether
 * any data in the backup can actually be merged — i.e., would produce a write.
 * Returns a [Result] that drives the Apply button enabled state and any no-op explanation.
 *
 * Rules:
 *  - Preferences alone are never mergeable (settings are not applied in Merge Restore).
 *  - Matched stats, deduplicated events, new baselines, newer lyrics, new playlists
 *    or entries are all individually mergeable.
 *  - If every data category produces zero writes, the backup is a no-op for this library.
 *  - Does not write to the database. All inputs are snapshots captured by the caller.
 */
object WavdropMergePreviewAnalyzer {

    data class Result(
        val hasMergeableData: Boolean,
        /** Non-null when the backup is a no-op; explains why in plain language. */
        val noOpReason: String?,
        /** Unmatched backup tracks that will be preserved in the quarantine on apply. */
        val newPendingTrackCount: Int = 0,
        /** Unmatched backup tracks already present in the quarantine from a prior import. */
        val alreadyPendingTrackCount: Int = 0,
    )

    /**
     * Lyrics keyed by the resolved local song ID and content URI.
     * Value is the existing [LyricsOverrideEntity.updatedAt] timestamp.
     */
    data class ExistingLyricsKey(val songId: Long, val contentUri: String)

    fun analyze(
        backup: WavdropBackup,
        currentSongs: List<Song>,
        existingQuarantineOriginKeys: Set<String>,
        /**
         * Current [TrackStatsEntity] rows keyed by local song id.
         * Must be supplied explicitly by every caller — there is no safe default.
         * Pass the result of [TrackStatsDao.getAllStatsSnapshot] associated by songId.
         * A song absent from this map is treated as having all-zero stats (which
         * matches the DAO: [TrackStatsDao.insertIfAbsent] creates a blank row before
         * [TrackStatsDao.mergeMaxStats] writes the backup values).
         */
        existingStats: Map<Long, TrackStatsEntity>,
        existingEventFingerprints: Set<String>,
        /** Existing local non-null eventIds — keeps preview event-dedup aligned with apply (P2-B1). */
        existingEventIds: Set<String> = emptySet(),
        existingBaselines: List<ImportBaselineEntity>,
        existingLyrics: Map<ExistingLyricsKey, Long>,
        existingPlaylists: Map<String, Set<Long>>,
        precomputedDesktopOverlayPlan: DesktopOverlayRestorePlan? = null,
    ): Result {
        // Empty backup: nothing at all (no data, no preferences).
        val hasAnyContent = backup.trackStats.isNotEmpty() ||
            backup.listenEvents.isNotEmpty() ||
            backup.importBaselines.isNotEmpty() ||
            backup.lyricsOverrides.isNotEmpty() ||
            backup.playlists.isNotEmpty() ||
            backup.preferences != null ||
            backup.desktopOverlay != null
        if (!hasAnyContent) {
            return noOp("This backup contains no data to import.")
        }

        // Preferences-only: has prefs but zero library data.
        val hasLibraryData = backup.trackStats.isNotEmpty() ||
            backup.listenEvents.isNotEmpty() ||
            backup.importBaselines.isNotEmpty() ||
            backup.lyricsOverrides.isNotEmpty() ||
            backup.playlists.isNotEmpty() ||
            backup.desktopOverlay != null
        if (!hasLibraryData) {
            return noOp(
                "This backup contains settings, but settings are not changed during a merge. " +
                    "There is no compatible library data to merge.",
            )
        }

        // For all remaining checks, build the shared song resolver once.
        val resolvedBySongId = WavdropBackupStatsMatcher.resolveBackupSongIds(backup, currentSongs)
        val linkResolver     = BackupSongLinkResolver(currentSongs, resolvedBySongId)

        // ── Stats: any matched track where backup value is strictly greater,
        //          or backup marks a track as favourite that local does not ───
        if (backup.trackStats.isNotEmpty()) {
            val match = WavdropBackupStatsMatcher.match(backup, currentSongs)
            for ((song, backupStat) in match.matchedRows) {
                val local = existingStats[song.id]
                val effectiveLastListenedAt = when (backup.sourceVersion) {
                    BackupFormatVersion.V2 -> backupStat.lastListenedAt!! // non-null for v2
                    BackupFormatVersion.V1 -> backupStat.lastPlayedAt
                }
                val effect = StatsRestoreStrategy.computeEffect(
                    currentPlayCount              = local?.playCount ?: 0,
                    currentSkipCount              = local?.skipCount ?: 0,
                    currentListeningTimeMs        = local?.totalListeningTimeMs ?: 0L,
                    currentLastPlayedAt           = local?.lastPlayedAt ?: 0L,
                    currentLastListenedAt         = local?.lastListenedAt ?: 0L,
                    backupPlayCount               = backupStat.playCount,
                    backupSkipCount               = backupStat.skipCount,
                    backupListeningTimeMs         = backupStat.totalListeningTimeMs,
                    backupLastPlayedAt            = backupStat.lastPlayedAt,
                    effectiveBackupLastListenedAt = effectiveLastListenedAt,
                )
                if (effect.anyIncreased) return mergeable()
                // Backup favourite + local not favourite = a real state change.
                if (backupStat.isFavorite && local?.isFavorite != true) return mergeable()
            }
        }

        // ── Listen events: any non-duplicate event ────────────────────────────
        if (backup.listenEvents.isNotEmpty()) {
            val plan = ListenEventRestorePlanner.plan(
                events               = backup.listenEvents,
                resolveSong          = { event ->
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
            if (plan.restored > 0) return mergeable()
        }

        backup.desktopOverlay?.let { overlay ->
            val overlayPlan = precomputedDesktopOverlayPlan ?: run {
                val planStart = System.currentTimeMillis()
                if (BuildConfig.DEBUG) android.util.Log.d("WavdropPreview",
                    "analyze: overlay planner start trackStats=${overlay.trackStats.size} " +
                        "listenEvents=${overlay.listenEvents.size} currentSongs=${currentSongs.size}")
                DesktopOverlayRestorePlanner.plan(
                    overlay = overlay,
                    currentSongs = currentSongs,
                    currentStats = existingStats,
                    existingEventFingerprints = existingEventFingerprints,
                    existingEventIds = existingEventIds,
                ).also { plan ->
                    if (BuildConfig.DEBUG) android.util.Log.d("WavdropPreview",
                        "analyze: overlay planner done in ${System.currentTimeMillis() - planStart}ms " +
                            "matched=${plan.matchedStats.size} " +
                            "unresolved=${plan.unresolvedStats.size} " +
                            "hasWrites=${plan.hasWrites} hasPreserved=${plan.hasPreservedOverlayRows}")
                }
            }
            if (overlayPlan.hasWrites || overlayPlan.hasPreservedOverlayRows) {
                return mergeable()
            }
        }

        // ── Import baselines: any new or newer baseline ───────────────────────
        if (backup.importBaselines.isNotEmpty()) {
            val plan = ImportBaselineRestorePlanner.plan(
                baselines     = backup.importBaselines,
                resolveSongId = { backupSongId -> resolvedBySongId[backupSongId]?.id },
                existing      = existingBaselines,
            )
            if (plan.restored > 0) return mergeable()
        }

        // ── Lyrics overrides: any newer backup override ───────────────────────
        val backupSongById = backup.songs.associateBy { it.id }
        for (override in backup.lyricsOverrides) {
            val backupSong = backupSongById[override.songId]
            val song = linkResolver.resolve(
                backupSongId = override.songId,
                contentUri   = override.contentUri,
                title        = backupSong?.title,
                artist       = backupSong?.artist,
                album        = backupSong?.album,
            ) ?: continue
            val existingUpdatedAt = existingLyrics[ExistingLyricsKey(song.id, song.uri)]
            if (existingUpdatedAt == null || override.updatedAt > existingUpdatedAt) {
                return mergeable()
            }
        }

        // ── Playlists: any new playlist or any new entry in an existing one ───
        for (backupPlaylist in backup.playlists) {
            val name = backupPlaylist.name.trim()
            if (name.isBlank()) continue
            val existingEntries = existingPlaylists[name]
            if (existingEntries == null) {
                // Playlist doesn't exist locally yet; creating it is mergeable.
                return mergeable()
            }
            val plan = PlaylistEntryRestorePlanner.plan(
                entries         = backupPlaylist.songs,
                resolve         = { entry ->
                    linkResolver.resolve(
                        backupSongId = entry.songId,
                        contentUri   = entry.contentUri,
                        title        = entry.title,
                        artist       = entry.artist,
                        album        = entry.album,
                    )
                },
                existingSongIds = existingEntries,
                nextPosition    = existingEntries.size,
            )
            if (plan.restored > 0) return mergeable()
        }

        // ── Quarantine: unmatched backup tracks with history to preserve ──────
        val fp = QuarantinePlanner.backupFingerprint(backup)
        val quarantinePlan = QuarantinePlanner.plan(
            backup                = backup,
            backupFingerprint     = fp,
            resolvedBackupSongIds = resolvedBySongId.keys,
            existingOriginKeys    = existingQuarantineOriginKeys,
        )
        if (quarantinePlan.hasNewData) {
            return Result(
                hasMergeableData      = true,
                noOpReason            = null,
                newPendingTrackCount  = quarantinePlan.newTracksCount,
                alreadyPendingTrackCount = quarantinePlan.alreadyPresentCount,
            )
        }

        // Nothing produced a write.
        return noOp(
            "All data in this backup is already up to date in your library.",
            alreadyPendingTrackCount = quarantinePlan.alreadyPresentCount,
        )
    }

    private fun mergeable() = Result(hasMergeableData = true, noOpReason = null)

    private fun noOp(reason: String, alreadyPendingTrackCount: Int = 0) =
        Result(hasMergeableData = false, noOpReason = reason, alreadyPendingTrackCount = alreadyPendingTrackCount)
}
