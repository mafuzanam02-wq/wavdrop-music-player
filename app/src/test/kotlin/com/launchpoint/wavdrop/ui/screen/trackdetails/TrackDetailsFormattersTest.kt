package com.launchpoint.wavdrop.ui.screen.trackdetails

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TrackDetailsFormattersTest {

    // ── formatDuration ────────────────────────────────────────────────────────

    @Test
    fun `duration 0 ms formats as 0 colon 00`() {
        assertEquals("0:00", TrackDetailsFormatters.formatDuration(0L))
    }

    @Test
    fun `duration 3 min 45 sec formats as mm colon ss`() {
        assertEquals("3:45", TrackDetailsFormatters.formatDuration(225_000L))
    }

    @Test
    fun `duration under 1 hour has no hours segment`() {
        val result = TrackDetailsFormatters.formatDuration(3_599_000L)
        assertEquals("59:59", result)
    }

    @Test
    fun `duration exactly 1 hour formats as h colon mm colon ss`() {
        assertEquals("1:00:00", TrackDetailsFormatters.formatDuration(3_600_000L))
    }

    @Test
    fun `duration 1 h 23 min 45 sec formats correctly`() {
        assertEquals("1:23:45", TrackDetailsFormatters.formatDuration(5_025_000L))
    }

    @Test
    fun `duration negative is clamped to 0 colon 00`() {
        assertEquals("0:00", TrackDetailsFormatters.formatDuration(-1_000L))
    }

    // ── formatLastPlayed ──────────────────────────────────────────────────────

    @Test
    fun `lastPlayed 0 returns Never`() {
        assertEquals("Never", TrackDetailsFormatters.formatLastPlayed(0L))
    }

    @Test
    fun `lastPlayed non-zero returns non-empty non-Never string`() {
        val result = TrackDetailsFormatters.formatLastPlayed(1_700_000_000_000L)
        assertNotEquals("Never", result)
        assert(result.isNotBlank())
    }

    // ── formatListeningTime ───────────────────────────────────────────────────

    @Test
    fun `listening time 0 returns 0 min`() {
        assertEquals("0 min", TrackDetailsFormatters.formatListeningTime(0L))
    }

    @Test
    fun `listening time negative returns 0 min`() {
        assertEquals("0 min", TrackDetailsFormatters.formatListeningTime(-5_000L))
    }

    @Test
    fun `listening time less than 1 minute returns less than 1 min`() {
        assertEquals("< 1 min", TrackDetailsFormatters.formatListeningTime(30_000L))
    }

    @Test
    fun `listening time 45 minutes formats as 45m`() {
        assertEquals("45m", TrackDetailsFormatters.formatListeningTime(45 * 60_000L))
    }

    @Test
    fun `listening time exactly 2 hours formats as 2h`() {
        assertEquals("2h", TrackDetailsFormatters.formatListeningTime(120 * 60_000L))
    }

    @Test
    fun `listening time 1h 5m formats correctly`() {
        assertEquals("1h 5m", TrackDetailsFormatters.formatListeningTime(65 * 60_000L))
    }

    @Test
    fun `listening time 3h 30m formats correctly`() {
        assertEquals("3h 30m", TrackDetailsFormatters.formatListeningTime(210 * 60_000L))
    }
}
