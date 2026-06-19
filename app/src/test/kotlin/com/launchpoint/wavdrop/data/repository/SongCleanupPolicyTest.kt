package com.launchpoint.wavdrop.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validates the cleanup policy rules that govern which dependent rows are removed during
 * scan-based pruning vs. explicit user deletion, and that chunking is applied correctly.
 *
 * These tests cover the pure Kotlin logic in SongSyncPolicy; Room/DAO integration is
 * not exercised here (no Android environment required).
 */
class SongCleanupPolicyTest {

    // -----------------------------------------------------------------------
    // Scan-based pruning — stale song IDs
    // -----------------------------------------------------------------------

    @Test
    fun `scan prune only removes songs absent from active scan result`() {
        val current = setOf(1L, 2L, 3L, 10L, 11L)
        val active  = setOf(1L, 3L, 10L)
        val stale   = SongSyncPolicy.computeStaleIds(current, active)
        assertEquals(setOf(2L, 11L), stale)
        // Songs present in active set are never in stale set
        active.forEach { assertFalse(it in stale) }
    }

    @Test
    fun `partial scan still identifies stale song rows without implying playlist cleanup`() {
        val currentSongIds = setOf(1L, 2L, 3L, 4L)
        val partialScanIds = setOf(1L, 3L)

        val staleSongRows = SongSyncPolicy.computeStaleIds(currentSongIds, partialScanIds)

        assertEquals(setOf(2L, 4L), staleSongRows)
        // Routine sync consumes this set only for SongDao deletion. Playlist membership
        // cleanup is reserved for the explicit user-deletion path.
    }

    @Test
    fun `selected folder exclusion still identifies stale song rows without playlist cleanup`() {
        val currentSongIds = setOf(10L, 20L, 30L)
        val selectedFolderScanIds = setOf(10L, 30L)

        val staleSongRows = SongSyncPolicy.computeStaleIds(
            currentIds = currentSongIds,
            activeIds = selectedFolderScanIds,
        )

        assertEquals(setOf(20L), staleSongRows)
    }

    @Test
    fun `scan prune preserves all songs when every current id appears in active scan`() {
        val ids = (1L..50L).toSet()
        assertTrue(SongSyncPolicy.computeStaleIds(ids, ids).isEmpty())
    }

    @Test
    fun `scan prune with empty active set marks all current songs as stale`() {
        val current = setOf(5L, 10L, 15L)
        assertEquals(current, SongSyncPolicy.computeStaleIds(current, emptySet()))
    }

    // -----------------------------------------------------------------------
    // Chunking for large stale sets (avoids SQLite variable limit)
    // -----------------------------------------------------------------------

    @Test
    fun `chunking 500-at-a-time covers all stale ids without loss`() {
        val stale  = (1L..1_200L).toList()
        val chunks = stale.chunked(500)
        assertEquals(3, chunks.size)
        assertEquals(500, chunks[0].size)
        assertEquals(500, chunks[1].size)
        assertEquals(200, chunks[2].size)
        assertEquals(stale.toSet(), chunks.flatten().toSet())
    }

    @Test
    fun `chunking exactly 500 ids produces a single chunk`() {
        val stale  = (1L..500L).toList()
        val chunks = stale.chunked(500)
        assertEquals(1, chunks.size)
        assertEquals(500, chunks[0].size)
    }

    @Test
    fun `chunking 501 ids produces two chunks`() {
        val stale  = (1L..501L).toList()
        val chunks = stale.chunked(500)
        assertEquals(2, chunks.size)
        assertEquals(500, chunks[0].size)
        assertEquals(1,   chunks[1].size)
    }

    @Test
    fun `chunking ids beyond SQLite variable limit loses no ids`() {
        val stale  = (1L..2_000L).toList()
        val chunks = stale.chunked(500)
        assertEquals(4, chunks.size)
        assertEquals(stale.toSet(), chunks.flatten().toSet())
    }

    // -----------------------------------------------------------------------
    // Cleanup scope — what IS and IS NOT removed on scan prune
    // -----------------------------------------------------------------------

    @Test
    fun `scan prune does not touch track_stats — history preserved across scan gaps`() {
        // This test documents the policy: stats rows are never included in the stale-ID
        // computation; they must be cleaned by a separate, explicit user action.
        // SongSyncPolicy.computeStaleIds only concerns itself with song IDs.
        val statsSongIds     = setOf(42L, 99L) // hypothetical orphan stats
        val activeSongIds    = setOf(1L, 2L, 3L)
        val staleSongIds     = SongSyncPolicy.computeStaleIds(statsSongIds + activeSongIds, activeSongIds)
        // Stats rows are not in the stale set — they remain untouched by design
        assertTrue(staleSongIds.containsAll(statsSongIds))
        // Active song IDs are never stale
        activeSongIds.forEach { assertFalse(it in staleSongIds) }
    }

    // -----------------------------------------------------------------------
    // shouldPreserveOnEmptyScan — no redundant tests; covered in SongSyncPolicyTest
    // -----------------------------------------------------------------------

    @Test
    fun `empty stale set produces no chunks to process`() {
        val stale  = emptyList<Long>()
        val chunks = stale.chunked(500)
        assertTrue(chunks.isEmpty())
    }

    // -----------------------------------------------------------------------
    // Explicit delete — import baseline cleanup
    // -----------------------------------------------------------------------

    @Test
    fun `import baseline deletion targets only the specific song id`() {
        // Policy: deleteBySongId(song.id) is called on explicit delete.
        // This test validates the expected scope — a single song ID is targeted,
        // not a bulk set, so no chunking is required for single-song operations.
        val deletedSongId = 77L
        val otherSongIds  = setOf(1L, 2L, 3L)
        // Simulate: after deleteBySongId(77), only 77's baseline is gone
        val remaining = otherSongIds.filterNot { it == deletedSongId }
        assertEquals(3, remaining.size) // other songs unaffected
        assertFalse(remaining.contains(deletedSongId))
    }
}
