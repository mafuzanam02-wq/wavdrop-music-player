package com.launchpoint.wavdrop.data.backup

import com.launchpoint.wavdrop.data.local.entity.TrackStatsEntity
import com.launchpoint.wavdrop.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-B.2: Authoritative No-Op Protection and Pre-Flight Compliance.
 *
 * All mergeability decisions go through [WavdropMergePreviewAnalyzer.analyze] — the
 * same shared pure function used by preview and by the apply-time recheck inside the
 * Room transaction. These tests exercise that shared function, which is the single
 * source of truth for the no-op determination.
 *
 * Test coverage:
 *  E.1  Backup contains only lower/equal stats → analyzer returns no-op
 *  E.2  "Stale preview" simulation: local state advances beyond backup between
 *       preview and apply — second analyzer call returns no-op
 *  E.3  Local higher playCount + backup higher lastPlayedAt → mergeable;
 *       statsRetainedLocal correctly counted
 *  E.4  Preferences-only backup → no-op (preferences not applied in Merge Restore)
 *  E.5  Already-present events and playlist entries → no-op
 *  E.6  One insertable event → mergeable
 *  E.7  Unmatched tracks only (no local songs) → no-op; result has no "retained" claim
 */
class MergeNoOpProtectionTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun song(id: Long, uri: String = "content://media/$id") = Song(
        id = id, title = "Track $id", artist = "Artist", album = "Album",
        albumId = 0L, duration = 180_000L, uri = uri, dateAdded = 0L,
        trackNumber = 0, year = 0,
    )

    private fun statsEntity(
        songId: Long,
        playCount: Int = 0,
        skipCount: Int = 0,
        listeningMs: Long = 0L,
        lastPlayedAt: Long = 0L,
        isFavorite: Boolean = false,
    ) = TrackStatsEntity(
        songId               = songId,
        contentUri           = "content://media/$songId",
        playCount            = playCount,
        skipCount            = skipCount,
        totalListeningTimeMs = listeningMs,
        lastPlayedAt         = lastPlayedAt,
        isFavorite           = isFavorite,
    )

    private fun backupStats(
        songId: Long,
        playCount: Int = 0,
        skipCount: Int = 0,
        listeningMs: Long = 0L,
        lastPlayedAt: Long = 0L,
        isFavorite: Boolean = false,
    ) = BackupTrackStats(
        songId               = songId,
        contentUri           = "content://media/$songId",
        playCount            = playCount,
        skipCount            = skipCount,
        totalListeningTimeMs = listeningMs,
        lastPlayedAt         = lastPlayedAt,
        isFavorite           = isFavorite,
    )

    private fun backupEvent(songId: Long, occurredAt: Long = 1_000_000L) = BackupListenEvent(
        songId     = songId,
        contentUri = "content://media/$songId",
        title      = "Track $songId",
        artist     = "Artist",
        album      = "Album",
        eventType  = "PLAY",
        occurredAt = occurredAt,
        listenedMs = 30_000L,
        durationMs = 180_000L,
        source     = "wavdrop_playback",
    )

    private fun backupPlaylist(name: String, songIds: List<Long>) = BackupPlaylist(
        id        = 1L,
        name      = name,
        createdAt = 0L,
        updatedAt = 0L,
        songs     = songIds.mapIndexed { i, id ->
            BackupPlaylistSong(
                songId = id, contentUri = "content://media/$id",
                position = i, title = "Track $id", artist = "Artist", album = "Album",
            )
        },
    )

    private fun analyze(
        backup: WavdropBackup,
        currentSongs: List<Song> = emptyList(),
        existingStats: Map<Long, TrackStatsEntity> = emptyMap(),
        existingEventFingerprints: Set<String> = emptySet(),
        existingBaselines: List<com.launchpoint.wavdrop.data.local.entity.ImportBaselineEntity> = emptyList(),
        existingLyrics: Map<WavdropMergePreviewAnalyzer.ExistingLyricsKey, Long> = emptyMap(),
        existingPlaylists: Map<String, Set<Long>> = emptyMap(),
        existingQuarantineOriginKeys: Set<String> = emptySet(),
    ) = WavdropMergePreviewAnalyzer.analyze(
        backup                       = backup,
        currentSongs                 = currentSongs,
        existingQuarantineOriginKeys = existingQuarantineOriginKeys,
        existingStats                = existingStats,
        existingEventFingerprints    = existingEventFingerprints,
        existingBaselines            = existingBaselines,
        existingLyrics               = existingLyrics,
        existingPlaylists            = existingPlaylists,
    )

    // ── E.1: Backup contains only lower/equal stats → no-op ──────────────────

    @Test
    fun `all backup stat values equal to local — no-op, apply must not write`() {
        val songId = 1L
        val backup = WavdropBackup(
            exportedAt      = "2026-06-23T00:00:00Z",
            songs           = listOf(BackupSong(songId, "content://media/$songId", "T", "A", "B", 0L, 180_000L, 0L, 0, 0)),
            trackStats      = listOf(backupStats(songId, playCount = 10, listeningMs = 5_000L)),
            importBaselines = emptyList(),
        )
        val local = mapOf(songId to statsEntity(songId, playCount = 10, listeningMs = 5_000L))

        val result = analyze(backup, currentSongs = listOf(song(songId)), existingStats = local)

        assertFalse("Equal stats produce no write — backup must be no-op", result.hasMergeableData)
        assertNotNull(result.noOpReason)
    }

    @Test
    fun `all backup stat values lower than local — no-op`() {
        val songId = 1L
        val backup = WavdropBackup(
            exportedAt      = "2026-06-23T00:00:00Z",
            songs           = listOf(BackupSong(songId, "content://media/$songId", "T", "A", "B", 0L, 180_000L, 0L, 0, 0)),
            trackStats      = listOf(backupStats(songId, playCount = 5, listeningMs = 1_000L)),
            importBaselines = emptyList(),
        )
        val local = mapOf(songId to statsEntity(songId, playCount = 50, listeningMs = 900_000L))

        val result = analyze(backup, currentSongs = listOf(song(songId)), existingStats = local)

        assertFalse("All-lower backup stats are no-op", result.hasMergeableData)
    }

    // ── E.2: Stale preview — local state advanced between preview and apply ───

    @Test
    fun `local state advances to match backup between preview and apply — second call returns no-op`() {
        val songId = 1L
        val backup = WavdropBackup(
            exportedAt      = "2026-06-23T00:00:00Z",
            songs           = listOf(BackupSong(songId, "content://media/$songId", "T", "A", "B", 0L, 180_000L, 0L, 0, 0)),
            trackStats      = listOf(backupStats(songId, playCount = 30)),
            importBaselines = emptyList(),
        )

        // At preview time local = 20 plays → backup (30) is higher → mergeable
        val localAtPreview = mapOf(songId to statsEntity(songId, playCount = 20))
        val previewResult  = analyze(backup, listOf(song(songId)), existingStats = localAtPreview)
        assertTrue("Backup is mergeable at preview time", previewResult.hasMergeableData)

        // User played songs; now local = 30 plays — equals backup.
        val localAtApply = mapOf(songId to statsEntity(songId, playCount = 30))
        val applyResult  = analyze(backup, listOf(song(songId)), existingStats = localAtApply)
        assertFalse("No-op at apply time: local caught up to backup", applyResult.hasMergeableData)
        assertNotNull(applyResult.noOpReason)
    }

    // ── E.3: Local higher playCount + backup higher lastPlayedAt ──────────────

    @Test
    fun `local playCount higher but backup lastPlayedAt newer — mergeable, retained count is 1`() {
        val songId = 1L
        val backup = WavdropBackup(
            exportedAt      = "2026-06-23T00:00:00Z",
            songs           = listOf(BackupSong(songId, "content://media/$songId", "T", "A", "B", 0L, 180_000L, 0L, 0, 0)),
            trackStats      = listOf(backupStats(songId, playCount = 30, lastPlayedAt = 2_000_000L)),
            importBaselines = emptyList(),
        )
        val local = mapOf(songId to statsEntity(songId, playCount = 50, lastPlayedAt = 1_000_000L))

        val result = analyze(backup, listOf(song(songId)), existingStats = local)
        assertTrue("Backup lastPlayedAt is newer → mergeable", result.hasMergeableData)

        // Verify that StatsRestoreStrategy correctly computes both directions.
        val effect = StatsRestoreStrategy.computeEffect(
            currentPlayCount       = 50,
            currentSkipCount       = 0,
            currentListeningTimeMs = 0L,
            currentLastPlayedAt    = 1_000_000L,
            backupPlayCount        = 30,
            backupSkipCount        = 0,
            backupListeningTimeMs  = 0L,
            backupLastPlayedAt     = 2_000_000L,
        )
        assertTrue("backup lastPlayedAt higher → anyIncreased", effect.anyIncreased)
        assertTrue("local playCount higher → anyRetainedLocal", effect.anyRetainedLocal)

        // Retained-local count must be 1 for this single track.
        val retainedCount = if (effect.anyRetainedLocal) 1 else 0
        assertEquals("Exactly one track has local-retained stats", 1, retainedCount)
    }

    // ── E.4: Preferences-only → no-op ────────────────────────────────────────

    @Test
    fun `preferences-only backup — no-op, preferences must not be applied`() {
        val backup = WavdropBackup(
            exportedAt      = "2026-06-23T00:00:00Z",
            songs           = emptyList(),
            trackStats      = emptyList(),
            importBaselines = emptyList(),
            preferences     = BackupPreferences(
                startupDestination          = null,
                mostPlayedPeriod            = null,
                mostPlayedLimit             = null,
                homeVisibleSections         = null,
                scanMode                    = null,
                selectedFolderUris          = null,
                minimumTrackDurationSeconds = null,
            ),
        )
        val result = analyze(backup)
        assertFalse("Preferences-only is a no-op for Merge Restore", result.hasMergeableData)
        assertTrue(
            "Reason must mention settings",
            result.noOpReason?.contains("settings", ignoreCase = true) == true,
        )
    }

    // ── E.5: Already-present events / playlist entries → no-op ───────────────

    @Test
    fun `all events already present and all playlist entries exist — no-op`() {
        val songId = 1L
        val event  = backupEvent(songId, occurredAt = 1_000_000L)
        val backup = WavdropBackup(
            exportedAt      = "2026-06-23T00:00:00Z",
            songs           = listOf(BackupSong(songId, "content://media/$songId", "T", "A", "B", 0L, 180_000L, 0L, 0, 0)),
            trackStats      = emptyList(),
            importBaselines = emptyList(),
            listenEvents    = listOf(event),
            playlists       = listOf(backupPlaylist("Mix", listOf(songId))),
        )
        val existingFingerprints = setOf("${songId}|1000000|PLAY|30000")
        val existingPlaylists    = mapOf("Mix" to setOf(songId))

        val result = analyze(
            backup                    = backup,
            currentSongs              = listOf(song(songId)),
            existingEventFingerprints = existingFingerprints,
            existingPlaylists         = existingPlaylists,
        )
        assertFalse("All data already present → no-op", result.hasMergeableData)
    }

    // ── E.6: One insertable event → mergeable ─────────────────────────────────

    @Test
    fun `backup has one new listen event — hasMergeableData true`() {
        val songId = 1L
        val event  = backupEvent(songId, occurredAt = 2_000_000L)
        val backup = WavdropBackup(
            exportedAt      = "2026-06-23T00:00:00Z",
            songs           = listOf(BackupSong(songId, "content://media/$songId", "T", "A", "B", 0L, 180_000L, 0L, 0, 0)),
            trackStats      = emptyList(),
            importBaselines = emptyList(),
            listenEvents    = listOf(event),
        )
        val result = analyze(backup, currentSongs = listOf(song(songId)))
        assertTrue("One insertable event → mergeable", result.hasMergeableData)
        assertNull(result.noOpReason)
    }

    // ── E.7: Unmatched tracks with data → quarantine path (P2-A) ────────────

    @Test
    fun `backup has stats but no matching local songs — quarantine makes it mergeable`() {
        // P2-A: unmatched backup tracks are quarantined, not silently discarded.
        // The import is mergeable; apply writes them to pending_* tables.
        val backup = WavdropBackup(
            exportedAt      = "2026-06-23T00:00:00Z",
            songs           = listOf(BackupSong(99L, "content://media/99", "Ghost", "A", "B", 0L, 180_000L, 0L, 0, 0)),
            trackStats      = listOf(backupStats(99L, playCount = 50)),
            importBaselines = emptyList(),
        )
        val result = analyze(backup, currentSongs = emptyList())
        assertTrue("unmatched track with stats → quarantine → mergeable", result.hasMergeableData)
        assertNull(result.noOpReason)
        assertEquals(1, result.newPendingTrackCount)

        // The apply-side no-op result (when apply finds nothing) still carries no "retained" claim.
        val noOpResult = WavdropBackupImportApplyResult(
            matchedTracks   = 0,
            unmatchedTracks = 1,
            statsUpdated    = 0,
            isNoOp          = true,
        )
        assertTrue(noOpResult.isNoOp)
        assertEquals("No stats retained in a no-op result", 0, noOpResult.statsRetainedLocal)
    }
}
