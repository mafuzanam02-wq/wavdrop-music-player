package com.launchpoint.wavdrop.data.backup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Restore-mode semantics for Wavdrop backup restore: the backup is the source of
 * truth and matched tracks take the exact backup values. These tests pin the
 * reporting contract; the DB write itself (TrackStatsDao.restoreExactStats) is a
 * plain SET of the same values.
 */
class StatsRestoreStrategyTest {

    @Test
    fun `restore reports change when backup differs from local`() {
        val effect = StatsRestoreStrategy.computeEffect(
            currentPlayCount = 3, currentSkipCount = 1,
            currentListeningTimeMs = 1_000L, currentLastPlayedAt = 100L,
            backupPlayCount = 50, backupSkipCount = 7,
            backupListeningTimeMs = 900_000L, backupLastPlayedAt = 5_000L,
        )
        assertTrue(effect.anyChanged)
    }

    @Test
    fun `restoring the same backup twice is idempotent`() {
        // After the first restore, local == backup; the second restore reports no change.
        val second = StatsRestoreStrategy.computeEffect(
            currentPlayCount = 50, currentSkipCount = 7,
            currentListeningTimeMs = 900_000L, currentLastPlayedAt = 5_000L,
            backupPlayCount = 50, backupSkipCount = 7,
            backupListeningTimeMs = 900_000L, backupLastPlayedAt = 5_000L,
        )
        assertFalse(second.anyChanged)
    }

    @Test
    fun `local stats higher than backup are still replaced in restore mode`() {
        // MAX semantics would report no change here; restore mode must report a change
        // because the row will be set DOWN to the backup values.
        val effect = StatsRestoreStrategy.computeEffect(
            currentPlayCount = 99, currentSkipCount = 20,
            currentListeningTimeMs = 9_999_999L, currentLastPlayedAt = 999_999L,
            backupPlayCount = 50, backupSkipCount = 7,
            backupListeningTimeMs = 900_000L, backupLastPlayedAt = 5_000L,
        )
        assertTrue(effect.anyChanged)
    }

    @Test
    fun `totalListeningTimeMs difference alone is detected`() {
        val effect = StatsRestoreStrategy.computeEffect(
            currentPlayCount = 50, currentSkipCount = 7,
            currentListeningTimeMs = 899_999L, currentLastPlayedAt = 5_000L,
            backupPlayCount = 50, backupSkipCount = 7,
            backupListeningTimeMs = 900_000L, backupLastPlayedAt = 5_000L,
        )
        assertTrue(effect.anyChanged)
    }

    @Test
    fun `lastPlayedAt difference alone is detected`() {
        val effect = StatsRestoreStrategy.computeEffect(
            currentPlayCount = 50, currentSkipCount = 7,
            currentListeningTimeMs = 900_000L, currentLastPlayedAt = 4_999L,
            backupPlayCount = 50, backupSkipCount = 7,
            backupListeningTimeMs = 900_000L, backupLastPlayedAt = 5_000L,
        )
        assertTrue(effect.anyChanged)
    }

    @Test
    fun `fresh install with empty local stats takes backup values`() {
        val effect = StatsRestoreStrategy.computeEffect(
            currentPlayCount = 0, currentSkipCount = 0,
            currentListeningTimeMs = 0L, currentLastPlayedAt = 0L,
            backupPlayCount = 12, backupSkipCount = 0,
            backupListeningTimeMs = 60_000L, backupLastPlayedAt = 1_000L,
        )
        assertTrue(effect.anyChanged)
    }

    @Test
    fun `BlackPlayer import strategy still uses MAX and never reduces local stats`() {
        // Guard against the restore-mode change leaking into the import path:
        // StatsImportMerger (BlackPlayer) must keep MAX semantics.
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
