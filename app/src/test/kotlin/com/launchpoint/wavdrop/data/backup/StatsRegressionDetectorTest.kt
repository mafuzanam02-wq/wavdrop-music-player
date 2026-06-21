package com.launchpoint.wavdrop.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatsRegressionDetectorTest {

    private fun pair(
        backupPlay: Int = 10, backupSkip: Int = 2, backupMs: Long = 3600_000L, backupLast: Long = 1_000_000L,
        localPlay: Int = 10,  localSkip: Int = 2,  localMs: Long = 3600_000L,  localLast: Long = 1_000_000L,
    ) = StatsRegressionDetector.MatchedStatsPair(
        backupPlayCount        = backupPlay,
        backupSkipCount        = backupSkip,
        backupListeningTimeMs  = backupMs,
        backupLastPlayedAt     = backupLast,
        localPlayCount         = localPlay,
        localSkipCount         = localSkip,
        localListeningTimeMs   = localMs,
        localLastPlayedAt      = localLast,
    )

    // ── affectedSongs count ───────────────────────────────────────────────────

    @Test
    fun `zero affected songs when stats are equal`() {
        assertEquals(0, StatsRegressionDetector.detect(listOf(pair())).affectedSongs)
    }

    @Test
    fun `zero affected songs when backup stats are higher than local`() {
        assertEquals(0, StatsRegressionDetector.detect(listOf(pair(backupPlay = 20, localPlay = 10))).affectedSongs)
    }

    @Test
    fun `zero affected songs when list is empty`() {
        assertEquals(0, StatsRegressionDetector.detect(emptyList()).affectedSongs)
    }

    @Test
    fun `one affected song when local playCount exceeds backup`() {
        assertEquals(1, StatsRegressionDetector.detect(listOf(pair(backupPlay = 5, localPlay = 10))).affectedSongs)
    }

    @Test
    fun `one affected song when local skipCount exceeds backup`() {
        assertEquals(1, StatsRegressionDetector.detect(listOf(pair(backupSkip = 1, localSkip = 3))).affectedSongs)
    }

    @Test
    fun `one affected song when local listeningTime exceeds backup`() {
        assertEquals(1, StatsRegressionDetector.detect(listOf(pair(backupMs = 1_000L, localMs = 5_000L))).affectedSongs)
    }

    @Test
    fun `one affected song when local lastPlayedAt is newer than backup`() {
        assertEquals(1, StatsRegressionDetector.detect(listOf(pair(backupLast = 1_000_000L, localLast = 2_000_000L))).affectedSongs)
    }

    @Test
    fun `counts only affected songs not all songs in list`() {
        val equal     = pair()
        val affected1 = pair(backupPlay = 3, localPlay = 7)
        val affected2 = pair(backupSkip = 0, localSkip = 2)
        assertEquals(2, StatsRegressionDetector.detect(listOf(equal, affected1, affected2)).affectedSongs)
    }

    @Test
    fun `song with multiple newer stats counts as one affected song`() {
        val p = pair(backupPlay = 1, localPlay = 5, backupSkip = 0, localSkip = 3)
        assertEquals(1, StatsRegressionDetector.detect(listOf(p)).affectedSongs)
    }

    // ── hasRegression flag ────────────────────────────────────────────────────

    @Test
    fun `hasRegression is false when affectedSongs is zero`() {
        assertFalse(StatsRegressionDetector.detect(listOf(pair())).hasRegression)
    }

    @Test
    fun `hasRegression is true when at least one song is affected`() {
        assertTrue(StatsRegressionDetector.detect(listOf(pair(backupPlay = 1, localPlay = 5))).hasRegression)
    }

    // ── warning text ──────────────────────────────────────────────────────────

    @Test
    fun `regressionWarning is null when no songs affected`() {
        assertNull(StatsRegressionDetector.detect(emptyList()).regressionWarning())
    }

    @Test
    fun `regressionWarning uses singular form for one affected song`() {
        val summary = StatsRegressionDetector.detect(listOf(pair(backupPlay = 1, localPlay = 5)))
        assertEquals(
            "This backup may overwrite newer listening activity for 1 song already on this device.",
            summary.regressionWarning(),
        )
    }

    @Test
    fun `regressionWarning uses plural form for multiple affected songs`() {
        val pairs = listOf(
            pair(backupPlay = 1, localPlay = 5),
            pair(backupMs = 100L, localMs = 999L),
        )
        val summary = StatsRegressionDetector.detect(pairs)
        assertEquals(
            "This backup may overwrite newer listening activity for 2 songs already on this device.",
            summary.regressionWarning(),
        )
    }
}
