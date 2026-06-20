package com.launchpoint.wavdrop.data.stats

import com.launchpoint.wavdrop.data.local.entity.TrackListenEventEntity
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class InsightsSummaryBuilderTest {

    private val utc: ZoneId = ZoneOffset.UTC

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun epochMs(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day)
            .atStartOfDay(utc)
            .toInstant()
            .toEpochMilli()

    private fun playEvent(occurredAt: Long) = TrackListenEventEntity(
        songId     = 1L,
        eventType  = TrackListenEventEntity.TYPE_PLAY,
        occurredAt = occurredAt,
        source     = TrackListenEventEntity.SOURCE_WAVDROP_PLAYBACK,
    )

    private fun skipEvent(occurredAt: Long) = TrackListenEventEntity(
        songId     = 1L,
        eventType  = TrackListenEventEntity.TYPE_SKIP,
        occurredAt = occurredAt,
        source     = TrackListenEventEntity.SOURCE_WAVDROP_PLAYBACK,
    )

    // ── Streak from a fixed "today" using a zone pinned to UTC so dates are stable

    private fun streak(events: List<TrackListenEventEntity>, today: LocalDate): Int {
        // We can't pass "today" directly to InsightsSummaryBuilder because LocalDate.now(zone)
        // is called inside the builder. Instead pin the zone to UTC and use dates that are
        // relative to the actual UTC date at test time — or test with a ZoneOffset that makes
        // "today" deterministic. The cleanest approach: call the public API and choose event
        // timestamps relative to actual today in UTC, which the tests below do.
        return InsightsSummaryBuilder.currentStreakDays(events, utc)
    }

    // ── Empty input ───────────────────────────────────────────────────────────

    @Test
    fun `no events returns zero`() {
        assertEquals(0, InsightsSummaryBuilder.currentStreakDays(emptyList(), utc))
    }

    // ── Single-day streaks ────────────────────────────────────────────────────

    @Test
    fun `play event today returns streak of one`() {
        val today = LocalDate.now(utc)
        val events = listOf(playEvent(today.atStartOfDay(utc).toInstant().toEpochMilli()))
        assertEquals(1, InsightsSummaryBuilder.currentStreakDays(events, utc))
    }

    @Test
    fun `play event yesterday returns streak of one`() {
        val yesterday = LocalDate.now(utc).minusDays(1)
        val events = listOf(playEvent(yesterday.atStartOfDay(utc).toInstant().toEpochMilli()))
        assertEquals(1, InsightsSummaryBuilder.currentStreakDays(events, utc))
    }

    @Test
    fun `play event two days ago returns zero — streak broken`() {
        val twoDaysAgo = LocalDate.now(utc).minusDays(2)
        val events = listOf(playEvent(twoDaysAgo.atStartOfDay(utc).toInstant().toEpochMilli()))
        assertEquals(0, InsightsSummaryBuilder.currentStreakDays(events, utc))
    }

    // ── Consecutive streaks ───────────────────────────────────────────────────

    @Test
    fun `consecutive days ending today counts correctly`() {
        val today = LocalDate.now(utc)
        val events = listOf(
            playEvent(today.minusDays(2).atStartOfDay(utc).toInstant().toEpochMilli()),
            playEvent(today.minusDays(1).atStartOfDay(utc).toInstant().toEpochMilli()),
            playEvent(today.atStartOfDay(utc).toInstant().toEpochMilli()),
        )
        assertEquals(3, InsightsSummaryBuilder.currentStreakDays(events, utc))
    }

    @Test
    fun `consecutive days ending yesterday counts correctly`() {
        val yesterday = LocalDate.now(utc).minusDays(1)
        val events = listOf(
            playEvent(yesterday.minusDays(2).atStartOfDay(utc).toInstant().toEpochMilli()),
            playEvent(yesterday.minusDays(1).atStartOfDay(utc).toInstant().toEpochMilli()),
            playEvent(yesterday.atStartOfDay(utc).toInstant().toEpochMilli()),
        )
        assertEquals(3, InsightsSummaryBuilder.currentStreakDays(events, utc))
    }

    // ── Broken streak ─────────────────────────────────────────────────────────

    @Test
    fun `gap in streak resets count from last consecutive run`() {
        val today = LocalDate.now(utc)
        val events = listOf(
            playEvent(today.minusDays(5).atStartOfDay(utc).toInstant().toEpochMilli()),
            playEvent(today.minusDays(4).atStartOfDay(utc).toInstant().toEpochMilli()),
            // gap at -3
            playEvent(today.minusDays(2).atStartOfDay(utc).toInstant().toEpochMilli()),
            playEvent(today.minusDays(1).atStartOfDay(utc).toInstant().toEpochMilli()),
            playEvent(today.atStartOfDay(utc).toInstant().toEpochMilli()),
        )
        assertEquals(3, InsightsSummaryBuilder.currentStreakDays(events, utc))
    }

    // ── Skip events ignored ───────────────────────────────────────────────────

    @Test
    fun `skip events are not counted as play days`() {
        val today = LocalDate.now(utc)
        val events = listOf(
            skipEvent(today.minusDays(1).atStartOfDay(utc).toInstant().toEpochMilli()),
            skipEvent(today.atStartOfDay(utc).toInstant().toEpochMilli()),
        )
        assertEquals(0, InsightsSummaryBuilder.currentStreakDays(events, utc))
    }

    @Test
    fun `streak counts only days with play events not skip events`() {
        val today = LocalDate.now(utc)
        val events = listOf(
            playEvent(today.minusDays(1).atStartOfDay(utc).toInstant().toEpochMilli()),
            skipEvent(today.atStartOfDay(utc).toInstant().toEpochMilli()),
        )
        // yesterday has a play, today has only a skip — streak ends at yesterday
        assertEquals(1, InsightsSummaryBuilder.currentStreakDays(events, utc))
    }

    // ── Multiple events on same day count as one day ──────────────────────────

    @Test
    fun `multiple play events on same day count as one streak day`() {
        val today = LocalDate.now(utc)
        val events = listOf(
            playEvent(today.atStartOfDay(utc).toInstant().toEpochMilli()),
            playEvent(today.atStartOfDay(utc).toInstant().toEpochMilli() + 3_600_000L),
            playEvent(today.atStartOfDay(utc).toInstant().toEpochMilli() + 7_200_000L),
        )
        assertEquals(1, InsightsSummaryBuilder.currentStreakDays(events, utc))
    }

    // ── Year boundary ─────────────────────────────────────────────────────────

    @Test
    fun `events from previous calendar year are excluded from streak`() {
        val today = LocalDate.now(utc)
        val lastYear = today.minusYears(1)
        val events = listOf(
            playEvent(lastYear.atStartOfDay(utc).toInstant().toEpochMilli()),
        )
        // Event is in last year — outside the current-year window — streak is 0
        assertEquals(0, InsightsSummaryBuilder.currentStreakDays(events, utc))
    }
}
