package com.launchpoint.wavdrop.data.backup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Merge-mode strategy for Wavdrop backup (P1-B): MAX(local, backup) per field.
 * Stats can only increase; local values that are already higher are retained.
 * The DB write is [TrackStatsDao.mergeMaxStats]; these tests pin the counting logic.
 */
class StatsRestoreStrategyTest {

    @Test
    fun `backup higher than local — anyIncreased true, anyRetainedLocal false`() {
        val effect = StatsRestoreStrategy.computeEffect(
            currentPlayCount = 3, currentSkipCount = 1,
            currentListeningTimeMs = 1_000L, currentLastPlayedAt = 100L,
            backupPlayCount = 50, backupSkipCount = 7,
            backupListeningTimeMs = 900_000L, backupLastPlayedAt = 5_000L,
        )
        assertTrue(effect.anyIncreased)
        assertFalse(effect.anyRetainedLocal)
        assertTrue(effect.anyChanged)
    }

    @Test
    fun `local higher than backup — anyRetainedLocal true, anyIncreased false`() {
        val effect = StatsRestoreStrategy.computeEffect(
            currentPlayCount = 99, currentSkipCount = 20,
            currentListeningTimeMs = 9_999_999L, currentLastPlayedAt = 999_999L,
            backupPlayCount = 50, backupSkipCount = 7,
            backupListeningTimeMs = 900_000L, backupLastPlayedAt = 5_000L,
        )
        assertFalse(effect.anyIncreased)
        assertTrue(effect.anyRetainedLocal)
        assertTrue(effect.anyChanged)
    }

    @Test
    fun `merging the same backup twice is idempotent — no change on second merge`() {
        val second = StatsRestoreStrategy.computeEffect(
            currentPlayCount = 50, currentSkipCount = 7,
            currentListeningTimeMs = 900_000L, currentLastPlayedAt = 5_000L,
            backupPlayCount = 50, backupSkipCount = 7,
            backupListeningTimeMs = 900_000L, backupLastPlayedAt = 5_000L,
        )
        assertFalse(second.anyIncreased)
        assertFalse(second.anyRetainedLocal)
        assertFalse(second.anyChanged)
    }

    @Test
    fun `totalListeningTimeMs backup higher detected in anyIncreased`() {
        val effect = StatsRestoreStrategy.computeEffect(
            currentPlayCount = 50, currentSkipCount = 7,
            currentListeningTimeMs = 899_999L, currentLastPlayedAt = 5_000L,
            backupPlayCount = 50, backupSkipCount = 7,
            backupListeningTimeMs = 900_000L, backupLastPlayedAt = 5_000L,
        )
        assertTrue(effect.anyIncreased)
        assertFalse(effect.anyRetainedLocal)
    }

    @Test
    fun `lastPlayedAt backup higher detected in anyIncreased`() {
        val effect = StatsRestoreStrategy.computeEffect(
            currentPlayCount = 50, currentSkipCount = 7,
            currentListeningTimeMs = 900_000L, currentLastPlayedAt = 4_999L,
            backupPlayCount = 50, backupSkipCount = 7,
            backupListeningTimeMs = 900_000L, backupLastPlayedAt = 5_000L,
        )
        assertTrue(effect.anyIncreased)
        assertFalse(effect.anyRetainedLocal)
    }

    @Test
    fun `fresh install with empty local stats takes backup values`() {
        val effect = StatsRestoreStrategy.computeEffect(
            currentPlayCount = 0, currentSkipCount = 0,
            currentListeningTimeMs = 0L, currentLastPlayedAt = 0L,
            backupPlayCount = 12, backupSkipCount = 0,
            backupListeningTimeMs = 60_000L, backupLastPlayedAt = 1_000L,
        )
        assertTrue(effect.anyIncreased)
        assertFalse(effect.anyRetainedLocal)
    }

    @Test
    fun `mixed fields — local higher in some backup higher in others — both flags true`() {
        // playCount local=50, backup=30 → retained local
        // skipCount local=5, backup=20  → increased from backup
        val effect = StatsRestoreStrategy.computeEffect(
            currentPlayCount = 50, currentSkipCount = 5,
            currentListeningTimeMs = 0L, currentLastPlayedAt = 0L,
            backupPlayCount = 30, backupSkipCount = 20,
            backupListeningTimeMs = 0L, backupLastPlayedAt = 0L,
        )
        assertTrue(effect.anyIncreased)
        assertTrue(effect.anyRetainedLocal)
    }

    // ── lastListenedAt-specific tests (P1-D2) ────────────────────────────────

    @Test
    fun `v2 backup lastListenedAt higher than local — anyIncreased true`() {
        val effect = StatsRestoreStrategy.computeEffect(
            currentPlayCount = 10, currentSkipCount = 0,
            currentListeningTimeMs = 0L, currentLastPlayedAt = 5_000L,
            currentLastListenedAt = 1_000L,
            backupPlayCount = 10, backupSkipCount = 0,
            backupListeningTimeMs = 0L, backupLastPlayedAt = 5_000L,
            effectiveBackupLastListenedAt = 9_000L,
        )
        assertTrue(effect.anyIncreased)
        assertFalse(effect.anyRetainedLocal)
    }

    @Test
    fun `local lastListenedAt higher than backup — anyRetainedLocal true`() {
        val effect = StatsRestoreStrategy.computeEffect(
            currentPlayCount = 10, currentSkipCount = 0,
            currentListeningTimeMs = 0L, currentLastPlayedAt = 5_000L,
            currentLastListenedAt = 9_000L,
            backupPlayCount = 10, backupSkipCount = 0,
            backupListeningTimeMs = 0L, backupLastPlayedAt = 5_000L,
            effectiveBackupLastListenedAt = 1_000L,
        )
        assertFalse(effect.anyIncreased)
        assertTrue(effect.anyRetainedLocal)
    }

    @Test
    fun `v2 lastListenedAt=0 and local=0 produces no change`() {
        val effect = StatsRestoreStrategy.computeEffect(
            currentPlayCount = 10, currentSkipCount = 0,
            currentListeningTimeMs = 0L, currentLastPlayedAt = 5_000L,
            currentLastListenedAt = 0L,
            backupPlayCount = 10, backupSkipCount = 0,
            backupListeningTimeMs = 0L, backupLastPlayedAt = 5_000L,
            effectiveBackupLastListenedAt = 0L,
        )
        assertFalse(effect.anyIncreased)
        assertFalse(effect.anyRetainedLocal)
        assertFalse(effect.anyChanged)
    }

    @Test
    fun `omitting lastListenedAt params defaults to 0 — existing callers unaffected`() {
        // Verify default param values keep old call-sites working unchanged.
        val effect = StatsRestoreStrategy.computeEffect(
            currentPlayCount = 10, currentSkipCount = 0,
            currentListeningTimeMs = 0L, currentLastPlayedAt = 5_000L,
            backupPlayCount = 10, backupSkipCount = 0,
            backupListeningTimeMs = 0L, backupLastPlayedAt = 5_000L,
        )
        // Both lastListenedAt fields default to 0 — no change detected for that field.
        assertFalse(effect.anyChanged)
    }

    @Test
    fun `BlackPlayer import strategy still uses MAX and never reduces local stats`() {
        // Guard against the merge-mode change leaking into the BlackPlayer import path.
        val effect = StatsImportMerger.computeEffect(
            currentPlayCount = 99, currentSkipCount = 20,
            currentListeningTimeMs = 9_999L,
            importedPlayCount = 50, importedSkipCount = 7,
            importedListeningTimeMs = 1_000L,
        )
        assertFalse(effect.anyUpdated)
        assertTrue(effect.playDelta == 0L && effect.skipDelta == 0L && effect.listeningTimeDelta == 0L)
    }
}
