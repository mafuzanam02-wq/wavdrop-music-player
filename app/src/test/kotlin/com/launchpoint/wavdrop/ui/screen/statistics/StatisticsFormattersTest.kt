package com.launchpoint.wavdrop.ui.screen.statistics

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StatisticsFormattersTest {

    @Test
    fun `duration summary formats minutes hours and days`() {
        assertEquals("0m", StatisticsFormatters.formatDurationSummary(0L))
        assertEquals("59m", StatisticsFormatters.formatDurationSummary(59 * 60_000L))
        assertEquals("2h 5m", StatisticsFormatters.formatDurationSummary(125 * 60_000L))
        assertEquals("1d 2h", StatisticsFormatters.formatDurationSummary(26 * 60 * 60_000L))
    }

    @Test
    fun `last played formats today yesterday and fallback date`() {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.of(2026, 6, 1)
        val nowMs = today.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val yesterdayMs = today.minusDays(1).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val oldMs = LocalDate.of(2026, 5, 1).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

        assertEquals("Today", StatisticsFormatters.formatLastPlayed(nowMs, nowMs))
        assertEquals("Yesterday", StatisticsFormatters.formatLastPlayed(yesterdayMs, nowMs))
        assertEquals("May 1, 2026", StatisticsFormatters.formatLastPlayed(oldMs, nowMs))
        assertEquals("Never", StatisticsFormatters.formatLastPlayed(null, nowMs))
    }

    @Test
    fun `formatStreakDays returns dash for zero and correct pluralisation`() {
        assertEquals("—", StatisticsFormatters.formatStreakDays(0))
        assertEquals("1 day", StatisticsFormatters.formatStreakDays(1))
        assertEquals("2 days", StatisticsFormatters.formatStreakDays(2))
        assertEquals("30 days", StatisticsFormatters.formatStreakDays(30))
    }

    @Test
    fun `formatSkipRatio returns dash when no plays and percentage otherwise`() {
        assertEquals("—", StatisticsFormatters.formatSkipRatio(totalPlays = 0, totalSkips = 0))
        assertEquals("0%", StatisticsFormatters.formatSkipRatio(totalPlays = 10, totalSkips = 0))
        assertEquals("25%", StatisticsFormatters.formatSkipRatio(totalPlays = 4, totalSkips = 1))
        assertEquals("100%", StatisticsFormatters.formatSkipRatio(totalPlays = 3, totalSkips = 3))
    }

    @Test
    fun `formatDayOfWeekShort returns dash for null and non-empty string for known day`() {
        assertEquals("—", StatisticsFormatters.formatDayOfWeekShort(null))
        assertTrue(StatisticsFormatters.formatDayOfWeekShort(DayOfWeek.MONDAY).isNotEmpty())
        assertTrue(StatisticsFormatters.formatDayOfWeekShort(DayOfWeek.FRIDAY).isNotEmpty())
    }
}
