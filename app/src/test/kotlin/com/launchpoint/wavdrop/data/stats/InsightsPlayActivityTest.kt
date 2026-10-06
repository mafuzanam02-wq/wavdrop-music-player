package com.launchpoint.wavdrop.data.stats

import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WC-09: the bounded streaming play-activity summary must equal the OLD full-history timestamp implementation
 * (InsightsSummaryBuilder.*FromPlayTimestamps over most-recent-first input) for every zone, DST edge, tie and streak case. The
 * expectations always come from Java ZoneId semantics, never from SQLite.
 */
class InsightsPlayActivityTest {

    private val zones = listOf("UTC", "Africa/Johannesburg", "America/Los_Angeles", "Pacific/Auckland", "Asia/Kolkata", "Australia/Lord_Howe").map(ZoneId::of)

    private fun at(zone: ZoneId, y: Int, m: Int, d: Int, h: Int = 12, min: Int = 0): Long =
        LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant().toEpochMilli()

    private fun reduce(ts: List<Long>, zone: ZoneId, today: LocalDate): InsightsPlayActivity =
        InsightsPlayActivityReader.reduce(ts.asSequence(), zone, today)

    /** The pre-WC-09 production inputs: PLAY timestamps most recent first. */
    private fun old(ts: List<Long>) = ts.sortedDescending()

    private fun assertSame(label: String, ts: List<Long>, zone: ZoneId, today: LocalDate) {
        val o = old(ts)
        val n = reduce(ts, zone, today)
        assertEquals("$label day", InsightsSummaryBuilder.mostActiveDayOfWeekFromPlayTimestamps(o, zone), n.mostActiveDayOfWeek)
        assertEquals("$label hour", InsightsSummaryBuilder.mostActiveHourFromPlayTimestamps(o, zone), n.mostActiveHour)
        assertEquals("$label streak", InsightsSummaryBuilder.currentStreakDaysFromPlayTimestamps(o, zone, today), n.currentStreakDays(today))
    }

    // ── empty / basic ────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun emptyHistoryHasNoDayHourOrStreak() {
        val a = reduce(emptyList(), ZoneId.of("UTC"), LocalDate.of(2026, 6, 15))
        assertNull(a.mostActiveDayOfWeek); assertNull(a.mostActiveHour)
        assertEquals(0, a.currentStreakDays(LocalDate.of(2026, 6, 15)))
        assertTrue(a.currentYearPlayDays.isEmpty())
    }

    @Test fun aSinglePlayGivesItsLocalWeekdayAndHour() {
        val zone = ZoneId.of("Pacific/Auckland")
        val a = reduce(listOf(at(zone, 2026, 6, 15, 23, 30)), zone, LocalDate.of(2026, 6, 15)) // Monday 23:30 local
        assertEquals(DayOfWeek.MONDAY, a.mostActiveDayOfWeek); assertEquals(23, a.mostActiveHour)
    }

    @Test fun localMidnightCasesDifferFromUtcGrouping() {
        // 2026-06-15 00:30 in Auckland (UTC+12) is still 2026-06-14 (Sunday) 12:30 UTC.
        val akl = ZoneId.of("Pacific/Auckland"); val utc = ZoneId.of("UTC")
        val ts = listOf(at(akl, 2026, 6, 15, 0, 30))
        assertEquals(DayOfWeek.MONDAY, reduce(ts, akl, LocalDate.of(2026, 6, 15)).mostActiveDayOfWeek)
        assertEquals(DayOfWeek.SUNDAY, reduce(ts, utc, LocalDate.of(2026, 6, 15)).mostActiveDayOfWeek)
        assertEquals(0, reduce(ts, akl, LocalDate.of(2026, 6, 15)).mostActiveHour)
        assertEquals(12, reduce(ts, utc, LocalDate.of(2026, 6, 15)).mostActiveHour)
        assertSame("midnight akl", ts, akl, LocalDate.of(2026, 6, 15))
    }

    @Test fun hourBoundariesZeroAnd23AndHalfHourZones() {
        for (zone in zones) {
            val ts = listOf(at(zone, 2026, 3, 3, 0, 0), at(zone, 2026, 3, 3, 23, 59), at(zone, 2026, 3, 4, 23, 30))
            assertSame("edges ${zone.id}", ts, zone, LocalDate.of(2026, 3, 4))
            assertEquals(23, reduce(ts, zone, LocalDate.of(2026, 3, 4)).mostActiveHour) // 2 plays at 23:xx beat 1 at 00:xx
        }
        // +05:30 shifts the hour relative to UTC
        val kol = ZoneId.of("Asia/Kolkata")
        assertEquals(5, reduce(listOf(at(ZoneId.of("UTC"), 2026, 3, 3, 0, 0)), kol, LocalDate.of(2026, 3, 3)).mostActiveHour)
    }

    // ── explicit tie rule ────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun tiedCountsResolveToTheBucketWithTheLatestPlayRegardlessOfInputOrder() {
        val utc = ZoneId.of("UTC")
        // Monday x2, Tuesday x2: Tuesday has the latest play -> Tuesday; same for hours 9 vs 10.
        val mon1 = at(utc, 2026, 6, 1, 9); val mon2 = at(utc, 2026, 6, 8, 9)
        val tue1 = at(utc, 2026, 6, 2, 10); val tue2 = at(utc, 2026, 6, 9, 10)
        val ts = listOf(mon1, mon2, tue1, tue2)
        for (order in listOf(ts, ts.reversed(), ts.shuffled(Random(3)))) {
            val a = reduce(order, utc, LocalDate.of(2026, 6, 10))
            assertEquals(DayOfWeek.TUESDAY, a.mostActiveDayOfWeek); assertEquals(10, a.mostActiveHour)
        }
        assertSame("tie", ts, utc, LocalDate.of(2026, 6, 10))
        // a restored historical Monday PLAY (older than everything) breaks the tie by count, not recency
        val restored = ts + at(utc, 2020, 1, 6, 9) // Monday, hour 9
        assertEquals(DayOfWeek.MONDAY, reduce(restored, utc, LocalDate.of(2026, 6, 10)).mostActiveDayOfWeek)
        assertEquals(9, reduce(restored, utc, LocalDate.of(2026, 6, 10)).mostActiveHour)
        assertSame("restored", restored, utc, LocalDate.of(2026, 6, 10))
    }

    // ── DST / zone matrix against the old implementation ────────────────────────────────────────────────────────────────

    @Test fun dstTransitionsAndRepeatedHoursMatchTheOldImplementation() {
        val edges = mapOf(
            "America/Los_Angeles" to listOf(LocalDate.of(2026, 3, 8), LocalDate.of(2026, 11, 1)),
            "Pacific/Auckland" to listOf(LocalDate.of(2026, 9, 27), LocalDate.of(2026, 4, 5)),
            "Australia/Lord_Howe" to listOf(LocalDate.of(2026, 10, 4), LocalDate.of(2026, 4, 5)), // 30-minute DST shift
            "Africa/Johannesburg" to listOf(LocalDate.of(2026, 3, 8)), // no DST
            "Asia/Kolkata" to listOf(LocalDate.of(2026, 3, 8)),
            "UTC" to listOf(LocalDate.of(2026, 3, 8)),
        )
        for ((id, dates) in edges) {
            val zone = ZoneId.of(id)
            for (date in dates) {
                // every 15 minutes across the transition day +-1, including the repeated local hour on fall-back days
                val start = date.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                val ts = (0 until 4 * 24 * 3).map { start + it * 15L * 60_000L }
                assertSame("$id $date", ts, zone, date)
                assertSame("$id $date sparse", ts.filterIndexed { i, _ -> i % 7 == 0 }, zone, date.plusDays(1))
            }
        }
    }

    @Test fun yearBoundaryStreaksMatchTheOldImplementation() {
        for (zone in zones) {
            val ts = listOf(at(zone, 2025, 12, 31, 23, 50), at(zone, 2026, 1, 1, 0, 10), at(zone, 2026, 1, 2, 8))
            for (today in listOf(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 2), LocalDate.of(2026, 1, 3), LocalDate.of(2026, 1, 4))) {
                assertSame("${zone.id} $today", ts, zone, today)
            }
        }
        // previous calendar year only -> excluded
        assertEquals(0, reduce(listOf(at(ZoneId.of("UTC"), 2025, 12, 31)), ZoneId.of("UTC"), LocalDate.of(2026, 1, 1)).currentStreakDays(LocalDate.of(2026, 1, 1)))
    }

    // ── streak cases ─────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun streakCasesMatchTheOldDefinition() {
        val utc = ZoneId.of("UTC"); val today = LocalDate.of(2026, 6, 15)
        fun s(vararg days: Long) = reduce(days.map { at(utc, 2026, 6, it.toInt(), 10) }, utc, today).currentStreakDays(today)
        assertEquals(1, s(15)); assertEquals(1, s(14)); assertEquals(0, s(13))
        assertEquals(3, s(13, 14, 15)); assertEquals(3, s(12, 13, 14)) // ending yesterday
        assertEquals(2, s(9, 10, 14, 15)) // a gap breaks it
        assertEquals(1, reduce(listOf(at(utc, 2026, 6, 15, 1), at(utc, 2026, 6, 15, 9), at(utc, 2026, 6, 15, 23)), utc, today).currentStreakDays(today)) // same date counts once
        // staleness: summary built on Dec 31 read on Jan 1 behaves like the old recompute (no plays in the new year -> 0)
        val built = reduce(listOf(at(utc, 2026, 12, 31)), utc, LocalDate.of(2026, 12, 31))
        assertEquals(1, built.currentStreakDays(LocalDate.of(2026, 12, 31)))
        assertEquals(0, built.currentStreakDays(LocalDate.of(2027, 1, 1)))
    }

    // ── randomized equivalence incl. order independence ──────────────────────────────────────────────────────────────────

    @Test fun randomizedHistoriesMatchTheOldImplementationInEveryZoneAndAnyOrder() {
        val rnd = Random(2026)
        repeat(300) { n ->
            val zone = zones[n % zones.size]
            val ts = (0 until 1 + rnd.nextInt(60)).map {
                val base = at(zone, 2024 + rnd.nextInt(3), 1 + rnd.nextInt(12), 1 + rnd.nextInt(28), rnd.nextInt(24), rnd.nextInt(60))
                if (rnd.nextInt(5) == 0) base else base - rnd.nextInt(3) * 3_600_000L // clusters create ties
            }
            val today = LocalDate.of(2026, 1 + rnd.nextInt(12), 1 + rnd.nextInt(28))
            assertSame("random#$n ${zone.id}", ts, zone, today)
            assertEquals("order independence", reduce(ts, zone, today), reduce(ts.shuffled(rnd), zone, today))
        }
    }

    // ── bounded state ────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun halfAMillionPlaysAreReducedIntoBoundedState() {
        val zone = ZoneId.of("America/Los_Angeles"); val today = LocalDate.of(2026, 6, 15)
        val acc = InsightsPlayActivityAccumulator(zone, today)
        val start = at(zone, 2020, 1, 1, 0)
        val span = at(zone, 2026, 6, 15, 23) - start
        var t = start
        val step = span / 500_000L
        repeat(500_000) { acc.add(t); t += step }
        assertEquals(500_000L, acc.playsSeen)
        assertTrue("retained slots ${acc.retainedSlots()} must be <= 7 + 24 + 366", acc.retainedSlots() <= 7 + 24 + 366)
        val a = acc.build()
        assertTrue(a.currentYearPlayDays.size <= 366)
        // the sequence entry point also streams without a list
        val viaSequence = InsightsPlayActivityReader.reduce(generateSequence(start) { it + step }.take(500_000), zone, today)
        assertEquals(a, viaSequence)
    }

    // ── guards ───────────────────────────────────────────────────────────────────────────────────────────────────────────

    private fun read(path: String) = File("src/main/kotlin/com/launchpoint/wavdrop/$path").readText().replace("\r\n", "\n")
    private fun code(path: String) = read(path).lines().filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") || it.trimStart().startsWith("/**") }.joinToString("\n")

    @Test fun noScreenMaterializesThePlayTimestampHistoryAnyMore() {
        for (vm in listOf("ui/screen/settings/InsightsViewModel.kt", "ui/screen/statistics/StatisticsViewModel.kt")) {
            val c = code(vm)
            assertFalse("$vm", c.contains("playEventTimestamps"))
            assertFalse("$vm", c.contains("allListenEvents("))
            assertTrue("$vm uses the bounded summary", c.contains("playActivity("))
        }
        assertFalse("Insights must not subscribe to all analytics timestamps just to invalidate the month", code("ui/screen/settings/InsightsViewModel.kt").contains("analyticsEventTimestamps"))
        assertTrue(code("ui/screen/settings/InsightsViewModel.kt").contains("listenEventCount()"))
        val dao = code("data/local/dao/TrackListenEventDao.kt"); val repo = code("data/repository/StatsRepository.kt")
        assertFalse(dao.contains("observePlayEventTimestamps")); assertFalse(repo.contains("playEventTimestamps"))
        val playQuery = dao.substringAfter("fun playTimestampCursor").let { dao.substringBefore("fun playTimestampCursor").takeLast(160) }
        assertFalse("zone grouping must stay in Kotlin, not SQLite", playQuery.contains("strftime") || dao.contains("strftime"))
    }
}
