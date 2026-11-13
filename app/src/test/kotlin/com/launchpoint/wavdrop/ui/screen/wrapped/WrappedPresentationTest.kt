package com.launchpoint.wavdrop.ui.screen.wrapped

import com.launchpoint.wavdrop.data.model.ListeningAnalyticsEmptyReason
import com.launchpoint.wavdrop.data.model.ListeningAnalyticsEmptyState
import com.launchpoint.wavdrop.data.model.MonthYear
import com.launchpoint.wavdrop.data.model.WrappedPeriod
import com.launchpoint.wavdrop.data.model.WrappedScope
import com.launchpoint.wavdrop.data.model.WrappedSummary
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P5-WR-UX-01A. Pure-function checks of the Wrapped presentation helpers that the composables call. They prove the
 * helper outputs, not Compose rendering (no JVM Compose rendering exists in this project).
 */
class WrappedPresentationTest {

    private fun summary(
        plays: Int = 0,
        days: Int = 0,
        uniqueSongs: Int = 0,
        listeningMs: Long = 0L,
    ) = WrappedSummary(
        period = WrappedPeriod.year(2025, ZoneOffset.UTC),
        totalPlayCount = plays,
        totalSkipCount = 0,
        totalListeningTimeMs = listeningMs,
        uniqueSongsPlayedCount = uniqueSongs,
        uniqueArtistsPlayedCount = 0,
        uniqueAlbumsPlayedCount = 0,
        listeningDaysCount = days,
        busiestDay = null,
        busiestDayPlayCount = 0,
        averagePlaysPerActiveDay = 0.0,
        topSongs = emptyList(),
        topArtists = emptyList(),
        topAlbums = emptyList(),
        mostPlayedSong = null,
        mostPlayedArtist = null,
        mostPlayedAlbum = null,
        mostSkippedTrack = null,
        recentlyPlayed = emptyList(),
        emptyState = ListeningAnalyticsEmptyState(ListeningAnalyticsEmptyReason.HAS_ACTIVITY, true, true),
        longestStreak = 0,
        currentStreak = 0,
        mostActiveDayOfWeek = null,
        mostActiveHour = null,
        averageListeningTimePerActiveDayMs = 0L,
        mostReplayedTrack = null,
    )

    // ── Overview rows ─────────────────────────────────────────────────────────

    @Test
    fun `all time keeps only aggregate rows and hides unavailable per-day rows`() {
        val rows = wrappedOverviewRows(WrappedScope.ALL_TIME)
        assertEquals(listOf(WrappedOverviewRow.UNIQUE_TRACKS, WrappedOverviewRow.UNIQUE_ARTISTS), rows)
        assertFalse(WrappedOverviewRow.LISTENING_DAYS in rows)
        assertFalse(WrappedOverviewRow.BUSIEST_DAY in rows)
        assertFalse(WrappedOverviewRow.AVG_PLAYS_PER_LISTENING_DAY in rows)
    }

    @Test
    fun `monthly and yearly keep all five rows in the original order`() {
        val expected = listOf(
            WrappedOverviewRow.LISTENING_DAYS,
            WrappedOverviewRow.UNIQUE_TRACKS,
            WrappedOverviewRow.UNIQUE_ARTISTS,
            WrappedOverviewRow.BUSIEST_DAY,
            WrappedOverviewRow.AVG_PLAYS_PER_LISTENING_DAY,
        )
        assertEquals(expected, wrappedOverviewRows(WrappedScope.MONTHLY))
        assertEquals(expected, wrappedOverviewRows(WrappedScope.YEARLY))
    }

    @Test
    fun `row labels keep existing text and the average label is clarified`() {
        assertEquals("Listening Days", WrappedOverviewRow.LISTENING_DAYS.label)
        assertEquals("Unique Tracks", WrappedOverviewRow.UNIQUE_TRACKS.label)
        assertEquals("Unique Artists", WrappedOverviewRow.UNIQUE_ARTISTS.label)
        assertEquals("Busiest Day", WrappedOverviewRow.BUSIEST_DAY.label)
        assertEquals("Avg plays per listening day", WrappedOverviewRow.AVG_PLAYS_PER_LISTENING_DAY.label)
    }

    @Test
    fun `period scope drives the row choice for real period objects`() {
        assertEquals(WrappedScope.ALL_TIME, WrappedPeriod.AllTime.scope)
        assertEquals(WrappedScope.MONTHLY, WrappedPeriod.month(MonthYear(2025, 3), ZoneOffset.UTC).scope)
        assertEquals(WrappedScope.YEARLY, WrappedPeriod.year(2025, ZoneOffset.UTC).scope)
    }

    // ── Ranked rows ───────────────────────────────────────────────────────────

    @Test
    fun `artist subtitle covers song and album singular and plural combinations`() {
        assertEquals("1 song · 1 album", wrappedArtistSubtitle(1, 1))
        assertEquals("1 song · 2 albums", wrappedArtistSubtitle(1, 2))
        assertEquals("2 songs · 1 album", wrappedArtistSubtitle(2, 1))
        assertEquals("12 songs · 3 albums", wrappedArtistSubtitle(12, 3))
        assertEquals("0 songs · 0 albums", wrappedArtistSubtitle(0, 0))
    }

    @Test
    fun `play and skip labels use singular only for exactly one`() {
        assertEquals("0 plays", com.launchpoint.wavdrop.ui.components.CountLabels.plays(0))
        assertEquals("1 play", com.launchpoint.wavdrop.ui.components.CountLabels.plays(1))
        assertEquals("2 plays", com.launchpoint.wavdrop.ui.components.CountLabels.plays(2))
        assertEquals("0 skips", com.launchpoint.wavdrop.ui.components.CountLabels.skips(0))
        assertEquals("1 skip", com.launchpoint.wavdrop.ui.components.CountLabels.skips(1))
        assertEquals("1234 plays", com.launchpoint.wavdrop.ui.components.CountLabels.plays(1234))
    }

    // ── Busiest day ───────────────────────────────────────────────────────────

    @Test
    fun `busiest day wording is singular for one play and plural otherwise`() {
        val day = LocalDate.of(2025, 3, 9)
        assertEquals("Mar 9 · 1 play", formatBusiestDay(day, 1))
        assertEquals("Mar 9 · 14 plays", formatBusiestDay(day, 14))
        assertEquals("None", formatBusiestDay(null, 0))
    }

    // ── Milestone wording ─────────────────────────────────────────────────────

    @Test
    fun `milestone intro is period aware and has no stray possessive`() {
        assertEquals("Moments from 2025.", wrappedMilestoneIntro("2025"))
        assertFalse(wrappedMilestoneIntro("2025").contains("your 2025"))
    }

    @Test
    fun `milestone details use count labels and keep this-year wording`() {
        val details = computeMilestones(
            summary(plays = 100, days = 7, uniqueSongs = 25, listeningMs = 10L * 3_600_000L),
        ).map { it.detail }
        assertTrue(details.contains("100 plays this year"))
        assertTrue(details.contains("25 different tracks played"))
        assertTrue(details.contains("7 days with music this year"))
        assertEquals(4, details.size)
    }
}
