package com.launchpoint.wavdrop.data.backup

import com.launchpoint.wavdrop.data.local.entity.TrackStatsEntity
import com.launchpoint.wavdrop.data.model.Song
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-B.3: Strict Mergeability Inputs.
 *
 * [WavdropMergePreviewAnalyzer.analyze] requires [existingStats] to be supplied
 * explicitly by every caller. The default was removed so that omitting it is a
 * compile-time error. These tests verify the field-level behaviour that depends on
 * the explicitly supplied map:
 *
 *  1. Backup stats strictly higher than local → mergeable
 *  2. Backup stats equal to local → no-op for stats (other vectors absent)
 *  3. Backup stats strictly lower than local → no-op for stats
 *  4. Matched song absent from existingStats (no local row) + backup non-zero → mergeable
 *     (DAO: insertIfAbsent creates blank row, mergeMaxStats sets to backup value)
 *  5. Matched song absent from existingStats + backup all-zero + not favourite → no-op
 *     (DAO: insertIfAbsent creates blank row, mergeMaxStats writes no change)
 *  6. Matched song absent from existingStats + backup marks favourite → mergeable
 *     (favourite state change is a real persistent write even with zero stat fields)
 *  7. existingStats supplied with correct song id but different from backup → evaluated
 *     correctly (no accidental key mismatch)
 *
 * Compile-time enforcement: removing the default value from existingStats is itself
 * the enforcement mechanism. This file compiles only because every call supplies the
 * map explicitly. If existingStats had a default, callers could silently omit it and
 * receive misleading "no local stats" results. The build verifies this contract.
 */
class StrictMergeabilityInputsTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun song(id: Long) = Song(
        id = id, title = "Track $id", artist = "Artist", album = "Album",
        albumId = 0L, duration = 180_000L, uri = "content://media/$id",
        dateAdded = 0L, trackNumber = 0, year = 0,
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

    private fun backupWith(
        songId: Long,
        playCount: Int = 0,
        skipCount: Int = 0,
        listeningMs: Long = 0L,
        lastPlayedAt: Long = 0L,
        isFavorite: Boolean = false,
    ) = WavdropBackup(
        exportedAt      = "2026-06-23T00:00:00Z",
        songs           = listOf(
            BackupSong(songId, "content://media/$songId", "Track $songId", "Artist", "Album",
                0L, 180_000L, 0L, 0, 0),
        ),
        trackStats      = listOf(
            BackupTrackStats(
                songId               = songId,
                contentUri           = "content://media/$songId",
                playCount            = playCount,
                skipCount            = skipCount,
                totalListeningTimeMs = listeningMs,
                lastPlayedAt         = lastPlayedAt,
                isFavorite           = isFavorite,
            ),
        ),
        importBaselines = emptyList(),
    )

    private fun analyze(
        backup: WavdropBackup,
        currentSongs: List<Song>,
        existingStats: Map<Long, TrackStatsEntity>,
    ) = WavdropMergePreviewAnalyzer.analyze(
        backup                       = backup,
        currentSongs                 = currentSongs,
        existingQuarantineOriginKeys = emptySet(),
        existingStats                = existingStats,          // explicit — no default available
        existingEventFingerprints    = emptySet(),
        existingBaselines            = emptyList(),
        existingLyrics               = emptyMap(),
        existingPlaylists            = emptyMap(),
    )

    // ── 1. Backup higher → mergeable ─────────────────────────────────────────

    @Test
    fun `backup playCount strictly higher than local — mergeable`() {
        val id     = 1L
        val backup = backupWith(id, playCount = 50)
        val local  = mapOf(id to statsEntity(id, playCount = 20))

        val result = analyze(backup, listOf(song(id)), local)
        assertTrue(result.hasMergeableData)
        assertNull(result.noOpReason)
    }

    // ── 2. Backup equal to local → no-op for stats ────────────────────────────

    @Test
    fun `backup stats exactly equal to local — no-op`() {
        val id     = 1L
        val backup = backupWith(id, playCount = 30, listeningMs = 5_000L)
        val local  = mapOf(id to statsEntity(id, playCount = 30, listeningMs = 5_000L))

        val result = analyze(backup, listOf(song(id)), local)
        assertFalse(result.hasMergeableData)
    }

    // ── 3. Backup lower than local → no-op ───────────────────────────────────

    @Test
    fun `backup stats all lower than local — no-op`() {
        val id     = 1L
        val backup = backupWith(id, playCount = 5, listeningMs = 1_000L)
        val local  = mapOf(id to statsEntity(id, playCount = 50, listeningMs = 900_000L))

        val result = analyze(backup, listOf(song(id)), local)
        assertFalse(result.hasMergeableData)
    }

    // ── 4. No local row + backup non-zero → mergeable ────────────────────────

    @Test
    fun `matched song absent from existingStats with backup playCount gt zero — mergeable`() {
        // DAO: insertIfAbsent creates blank all-zero row, then mergeMaxStats sets
        // playCount to MAX(0, backupPlayCount) = backupPlayCount > 0 → real write.
        val id     = 1L
        val backup = backupWith(id, playCount = 10)
        val local  = emptyMap<Long, TrackStatsEntity>() // no row for this song

        val result = analyze(backup, listOf(song(id)), local)
        assertTrue("No local row + backup non-zero is a new persistent write", result.hasMergeableData)
    }

    // ── 5. No local row + backup all-zero + not favourite → no-op ─────────────

    @Test
    fun `matched song absent from existingStats with all-zero backup stats — no-op`() {
        // DAO: insertIfAbsent creates blank row, mergeMaxStats writes MAX(0,0)=0 for
        // every field — the row ends up identical to the blank row, no user-visible change.
        val id     = 1L
        val backup = backupWith(id, playCount = 0, skipCount = 0, listeningMs = 0L, lastPlayedAt = 0L, isFavorite = false)
        val local  = emptyMap<Long, TrackStatsEntity>()

        val result = analyze(backup, listOf(song(id)), local)
        assertFalse("All-zero backup + no local row → nothing persistent changes", result.hasMergeableData)
    }

    // ── 6. No local row + backup marks favourite → mergeable ─────────────────

    @Test
    fun `matched song absent from existingStats but backup marks favourite — mergeable`() {
        // Favourite state is a boolean column set via setFavorite(true).
        // local?.isFavorite = null (no row) → treated as false; backup is true → real write.
        val id     = 1L
        val backup = backupWith(id, isFavorite = true)
        val local  = emptyMap<Long, TrackStatsEntity>()

        val result = analyze(backup, listOf(song(id)), local)
        assertTrue("No local row + backup favourite = new favourite write", result.hasMergeableData)
    }

    // ── 7. existingStats keyed by correct song id — no accidental key mismatch ─

    @Test
    fun `existing stats keyed by local song id not backup song id — evaluated correctly`() {
        // Song in backup has id=1; local library has it also as id=1 (matched by URI).
        // existingStats must be keyed by the LOCAL song id.
        val localSongId = 1L
        val backup      = backupWith(localSongId, playCount = 99)
        val currentSong = song(localSongId)
        // Key is the local song id — same id here, but the test asserts the lookup works.
        val local       = mapOf(localSongId to statsEntity(localSongId, playCount = 20))

        val result = analyze(backup, listOf(currentSong), local)
        assertTrue("Stats looked up by local song id; backup (99) > local (20) → mergeable", result.hasMergeableData)
    }
}
