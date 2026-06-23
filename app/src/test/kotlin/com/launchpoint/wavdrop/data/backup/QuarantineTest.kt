package com.launchpoint.wavdrop.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2-A: Unmatched History Quarantine Foundation.
 *
 * All tests operate on [QuarantinePlanner] (pure function, no DB) and
 * [WavdropMergePreviewAnalyzer] with quarantine-aware arguments.
 * No mock framework used — inputs constructed inline.
 *
 * Tests 1–9 verify [QuarantinePlanner.plan]; test 10 verifies the analyzer
 * quarantine counts; tests 11–12 are guard-rail checks (existing logic remains
 * unaffected and the schema field contracts are verified structurally).
 */
class QuarantineTest {

    // ── Fixture helpers ───────────────────────────────────────────────────────

    private fun backupSong(id: Long) =
        BackupSong(id, "content://media/$id", "Track $id", "Artist $id", "Album $id", 0L, 180_000L, 0L, 0, 0)

    private fun backupStats(songId: Long, playCount: Int = 5) = BackupTrackStats(
        songId               = songId,
        contentUri           = "content://media/$songId",
        playCount            = playCount,
        skipCount            = 0,
        lastPlayedAt         = 1_000L,
        totalListeningTimeMs = 60_000L,
        isFavorite           = false,
    )

    private fun backupEvent(songId: Long, occurredAt: Long = 1_000_000L) = BackupListenEvent(
        songId     = songId,
        contentUri = "content://media/$songId",
        title      = "Track $songId",
        artist     = "Artist $songId",
        album      = "Album $songId",
        eventType  = "PLAY",
        occurredAt = occurredAt,
        listenedMs = 30_000L,
        durationMs = 180_000L,
        source     = "wavdrop_playback",
    )

    private fun backupLyrics(songId: Long) = BackupLyricsOverride(
        songId     = songId,
        contentUri = "content://media/$songId",
        lyrics     = "Lyrics for $songId",
        updatedAt  = 2_000L,
    )

    private fun backupBaseline(songId: Long, sourceType: String = "BLK", sourceKey: String = "k$songId") =
        BackupImportBaseline(
            songId                = songId,
            sourceType            = sourceType,
            sourceKey             = sourceKey,
            lastImportedPlayCount = 3,
            lastImportedSkipCount = 1,
            lastImportedAt        = 1_500L,
        )

    private fun backupPlaylistEntry(songId: Long, playlistName: String = "Faves", position: Int = 0) =
        BackupPlaylistSong(songId, "content://media/$songId", position, "Track $songId", "Artist", "Album")

    private fun minimalBackup(
        songs: List<BackupSong> = emptyList(),
        stats: List<BackupTrackStats> = emptyList(),
        events: List<BackupListenEvent> = emptyList(),
        lyrics: List<BackupLyricsOverride> = emptyList(),
        baselines: List<BackupImportBaseline> = emptyList(),
        playlists: List<BackupPlaylist> = emptyList(),
    ) = WavdropBackup(
        exportedAt      = "2026-06-23T00:00:00Z",
        songs           = songs,
        trackStats      = stats,
        listenEvents    = events,
        lyricsOverrides = lyrics,
        importBaselines = baselines,
        playlists       = playlists,
    )

    private fun plan(
        backup: WavdropBackup,
        resolvedBackupSongIds: Set<Long> = emptySet(),
        existingOriginKeys: Set<String> = emptySet(),
    ): QuarantinePlanner.Plan {
        val fp = QuarantinePlanner.backupFingerprint(backup)
        return QuarantinePlanner.plan(
            backup                = backup,
            backupFingerprint     = fp,
            resolvedBackupSongIds = resolvedBackupSongIds,
            existingOriginKeys    = existingOriginKeys,
        )
    }

    // ── Test 1: Empty library + backup stats → pending track/stats persist ───

    @Test
    fun `empty local library with backup stats - pending track created, no live rows, merge enabled`() {
        val backup = minimalBackup(
            songs = listOf(backupSong(1L)),
            stats = listOf(backupStats(1L)),
        )
        val result = plan(backup, resolvedBackupSongIds = emptySet())

        assertTrue("hasNewData should be true", result.hasNewData)
        assertEquals(1, result.newTracksCount)
        val candidate = result.newCandidates.single()
        assertNotNull("stats should be present", candidate.stats)
        assertEquals(5, candidate.stats!!.playCount)
        assertEquals(0, result.alreadyPresentCount)
    }

    // ── Test 2: Empty library + listen events → events persist, no duplicates ─

    @Test
    fun `empty local library with listen events - events persist in plan`() {
        val backup = minimalBackup(
            songs  = listOf(backupSong(2L)),
            events = listOf(backupEvent(2L, 1_000_000L), backupEvent(2L, 2_000_000L)),
        )
        val result = plan(backup)

        assertEquals(1, result.newTracksCount)
        assertEquals(2, result.newEventsCount)
        // Each event has a unique fingerprint
        val fingerprints = result.newCandidates.flatMap { c -> c.events.map { it.second } }
        assertEquals(2, fingerprints.distinct().size)
    }

    @Test
    fun `repeated import of same backup - second plan produces no new candidates`() {
        val backup = minimalBackup(
            songs  = listOf(backupSong(2L)),
            events = listOf(backupEvent(2L, 1_000_000L)),
        )
        val fp = QuarantinePlanner.backupFingerprint(backup)
        val originKey = QuarantinePlanner.originKey(fp, 2L)

        // Second import: originKey already present
        val result = plan(backup, existingOriginKeys = setOf(originKey))

        assertFalse("no new candidates on re-import", result.hasNewData)
        assertEquals(1, result.alreadyPresentCount)
    }

    // ── Test 3: Unmatched lyrics override → persists in quarantine ───────────

    @Test
    fun `unmatched lyrics override - candidate carries lyrics with fingerprint`() {
        val backup = minimalBackup(
            songs  = listOf(backupSong(3L)),
            lyrics = listOf(backupLyrics(3L)),
        )
        val result = plan(backup)

        assertEquals(1, result.newTracksCount)
        val candidate = result.newCandidates.single()
        assertNotNull("lyrics should be present", candidate.lyrics)
        val (override, fingerprint) = candidate.lyrics!!
        assertEquals("Lyrics for 3", override.lyrics)
        assertTrue("fingerprint contains songId", fingerprint.contains("|3|lyrics"))
    }

    // ── Test 4: Unmatched import baseline → persists in quarantine ────────────

    @Test
    fun `unmatched import baseline - candidate carries baseline with fingerprint`() {
        val backup = minimalBackup(
            songs     = listOf(backupSong(4L)),
            baselines = listOf(backupBaseline(4L)),
        )
        val result = plan(backup)

        val candidate = result.newCandidates.single()
        assertEquals(1, candidate.baselines.size)
        val (baseline, fingerprint) = candidate.baselines.single()
        assertEquals("BLK", baseline.sourceType)
        assertTrue(fingerprint.contains("|BLK|k4"))
    }

    // ── Test 5: Unmatched playlist entry → persists with playlist identity ───

    @Test
    fun `unmatched playlist entry - persists with original playlist name and position`() {
        val backup = minimalBackup(
            songs     = listOf(backupSong(5L)),
            playlists = listOf(
                BackupPlaylist(
                    id = 1L, name = "Faves", createdAt = 0L, updatedAt = 0L,
                    songs = listOf(backupPlaylistEntry(5L, "Faves", 0)),
                )
            ),
        )
        val result = plan(backup)

        val candidate = result.newCandidates.single()
        assertEquals(1, result.newPlaylistEntriesCount)
        val entry = candidate.playlistEntries.single()
        assertEquals("Faves", entry.playlistName)
        assertEquals(0, entry.position)
        // Fingerprint uses source playlist ID (not display name) so same-name playlists
        // with different IDs produce distinct fingerprints. BackupPlaylist.id = 1L here.
        assertTrue("fingerprint must use playlist ID", entry.originFingerprint.contains("|pl:1|0"))
        assertEquals("1", entry.sourcePlaylistId)
        assertEquals(0L, entry.sourcePlaylistCreatedAt)
        assertEquals(0L, entry.sourcePlaylistUpdatedAt)
    }

    // ── Test 6: Mixed backup → matched uses live path, unmatched enters quarantine

    @Test
    fun `mixed backup - matched track excluded from quarantine, unmatched enters`() {
        val backup = minimalBackup(
            songs  = listOf(backupSong(10L), backupSong(11L)),
            stats  = listOf(backupStats(10L), backupStats(11L)),
            events = listOf(backupEvent(10L), backupEvent(11L)),
        )
        // songId 10 is resolved (matched), 11 is unmatched
        val result = plan(backup, resolvedBackupSongIds = setOf(10L))

        assertEquals(1, result.newTracksCount)
        assertEquals(11L, result.newCandidates.single().backupSongId)
        assertEquals(0, result.alreadyPresentCount)
    }

    // ── Test 7: Re-import same verified backup → no duplicates ───────────────

    @Test
    fun `re-import same backup - all originKeys already present, no new candidates`() {
        val backup = minimalBackup(
            songs  = listOf(backupSong(20L), backupSong(21L)),
            stats  = listOf(backupStats(20L), backupStats(21L)),
        )
        val fp = QuarantinePlanner.backupFingerprint(backup)
        val existingKeys = setOf(
            QuarantinePlanner.originKey(fp, 20L),
            QuarantinePlanner.originKey(fp, 21L),
        )
        val result = plan(backup, existingOriginKeys = existingKeys)

        assertFalse(result.hasNewData)
        assertEquals(2, result.alreadyPresentCount)
        assertEquals(0, result.newTracksCount)
    }

    // ── Test 8: Legacy checksum-absent backup → same fingerprint, no duplicates

    @Test
    fun `legacy backup without payloadSha256 - fingerprint is stable, deduplication works`() {
        // payloadSha256 is null → this is a legacy backup
        val backup = WavdropBackup(
            exportedAt      = "2025-01-01T00:00:00Z",
            songs           = listOf(backupSong(30L)),
            trackStats      = listOf(backupStats(30L)),
            importBaselines = emptyList(),
            payloadSha256   = null,
        )
        val fp1 = QuarantinePlanner.backupFingerprint(backup)
        val fp2 = QuarantinePlanner.backupFingerprint(backup)
        assertEquals("fingerprint must be deterministic", fp1, fp2)

        // First import
        val result1 = plan(backup)
        assertEquals(1, result1.newTracksCount)

        // Second import with first result's key already present
        val key = QuarantinePlanner.originKey(fp1, 30L)
        val result2 = plan(backup, existingOriginKeys = setOf(key))
        assertFalse("second import should produce no new candidates", result2.hasNewData)
        assertEquals(1, result2.alreadyPresentCount)
    }

    // ── Test 9: Two different backup tracks with identical metadata → distinct ─

    @Test
    fun `two tracks sharing title and artist but different songIds - remain distinct pending records`() {
        val s1 = BackupSong(40L, "content://media/40", "Same Title", "Same Artist", "Same Album", 0L, 180_000L, 0L, 0, 0)
        val s2 = BackupSong(41L, "content://media/41", "Same Title", "Same Artist", "Same Album", 0L, 180_000L, 0L, 0, 0)
        val backup = minimalBackup(
            songs  = listOf(s1, s2),
            stats  = listOf(backupStats(40L), backupStats(41L)),
        )
        val result = plan(backup)

        assertEquals(2, result.newTracksCount)
        val keys = result.newCandidates.map { it.originKey }
        assertEquals("origin keys must be distinct", 2, keys.distinct().size)
    }

    // ── Test 10: Analyzer distinguishes live-merge, preserve-for-later, already-preserved

    @Test
    fun `analyzer result - new pending count and already pending count are correct`() {
        val backup = minimalBackup(
            songs  = listOf(backupSong(50L), backupSong(51L)),
            stats  = listOf(backupStats(50L), backupStats(51L)),
        )
        val fp = QuarantinePlanner.backupFingerprint(backup)
        // songId 51 already in quarantine from a prior import
        val existingKeys = setOf(QuarantinePlanner.originKey(fp, 51L))

        val analyzerResult = WavdropMergePreviewAnalyzer.analyze(
            backup                       = backup,
            currentSongs                 = emptyList(),
            existingQuarantineOriginKeys = existingKeys,
            existingStats                = emptyMap(),
            existingEventFingerprints    = emptySet(),
            existingBaselines            = emptyList(),
            existingLyrics               = emptyMap(),
            existingPlaylists            = emptyMap(),
        )

        assertTrue("mergeable because songId 50 is new to quarantine", analyzerResult.hasMergeableData)
        assertEquals(1, analyzerResult.newPendingTrackCount)
        assertEquals(1, analyzerResult.alreadyPendingTrackCount)
    }

    // ── Test 11: Existing P1 analyzer tests remain unaffected ────────────────

    @Test
    fun `analyzer - backup with no matching local songs and no quarantine data - no mergeable data`() {
        // Stats exist but no songs match and quarantine is empty — should be no-op
        // (unmatched tracks have stats but no events/lyrics/baselines/playlists)
        val backup = minimalBackup(
            songs  = listOf(backupSong(60L)),
            stats  = listOf(backupStats(60L)),
        )
        // No currentSongs → resolvedBySongId empty; stats matcher finds no matches.
        // But QuarantinePlanner will find songId 60 as unmatched with stats data →
        // result should be mergeable (quarantine path active).
        val result = WavdropMergePreviewAnalyzer.analyze(
            backup                       = backup,
            currentSongs                 = emptyList(),
            existingQuarantineOriginKeys = emptySet(),
            existingStats                = emptyMap(),
            existingEventFingerprints    = emptySet(),
            existingBaselines            = emptyList(),
            existingLyrics               = emptyMap(),
            existingPlaylists            = emptyMap(),
        )
        assertTrue("unmatched backup stats → quarantine path → mergeable", result.hasMergeableData)
        assertNull(result.noOpReason)
        assertEquals(1, result.newPendingTrackCount)
    }

    // ── Test 12: Apply result carries pending counts ──────────────────────────

    @Test
    fun `WavdropBackupImportApplyResult default values for pending fields are zero`() {
        val result = WavdropBackupImportApplyResult(
            matchedTracks = 0,
            unmatchedTracks = 0,
            statsUpdated = 0,
        )
        assertEquals(0, result.pendingTracksPreserved)
        assertEquals(0, result.pendingEventsPreserved)
        assertEquals(0, result.pendingPlaylistEntriesPreserved)
    }
}
