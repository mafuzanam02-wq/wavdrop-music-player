package com.launchpoint.wavdrop.data.stats

import com.launchpoint.wavdrop.data.local.entity.TrackListenEventEntity
import com.launchpoint.wavdrop.data.local.entity.TrackStatsEntity
import com.launchpoint.wavdrop.data.model.ListeningPeriodRange
import com.launchpoint.wavdrop.data.model.MonthYear
import com.launchpoint.wavdrop.data.model.MostPlayedDisplayLimit
import com.launchpoint.wavdrop.data.model.MostPlayedPeriod
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.model.WrappedPeriod
import com.launchpoint.wavdrop.data.model.WrappedScope
import com.launchpoint.wavdrop.ui.screen.monthlyreports.MonthlyReportsUiState
import com.launchpoint.wavdrop.ui.screen.monthlyreports.monthlyReportsFlow
import com.launchpoint.wavdrop.ui.screen.settings.InsightsHubUiState
import com.launchpoint.wavdrop.ui.screen.settings.insightsHubFlow
import com.launchpoint.wavdrop.ui.screen.settings.libraryCountsFlow
import com.launchpoint.wavdrop.ui.screen.smart.mostPlayedSummariesFlow
import com.launchpoint.wavdrop.ui.screen.statistics.statisticsInsightsFlow
import com.launchpoint.wavdrop.ui.screen.wrapped.WrappedSelectionRequest
import com.launchpoint.wavdrop.ui.screen.wrapped.WrappedUiState
import com.launchpoint.wavdrop.ui.screen.wrapped.WrappedVisualPreferences
import com.launchpoint.wavdrop.ui.screen.wrapped.wrappedStateFlow
import com.launchpoint.wavdrop.data.settings.WrappedBackgroundIntensity
import com.launchpoint.wavdrop.data.settings.WrappedFallbackTheme
import com.launchpoint.wavdrop.data.settings.WrappedVisualStyle
import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WC-02: the analytics screens no longer subscribe to the full TrackListenEventEntity history. These tests drive the extracted
 * flow functions from an in-memory "event table" that models the new DAO reads (count, PLAY+SKIP timestamps, PLAY timestamps most
 * recent first, inclusive-range entities) and records which range subscriptions are live, then compare against the entity-based
 * builders fed the whole table (the old behaviour). The SQL itself is pinned by a contract test on the DAO source; there is no JVM
 * Room harness (real DAO tests exist only under androidTest).
 */
class AnalyticsEventFlowDecouplingTest {

    private val utc: ZoneId = ZoneOffset.UTC

    // ── fixtures ─────────────────────────────────────────────────────────────────────────────────────────────────────────

    private fun at(year: Int, month: Int, day: Int, hour: Int = 12, zone: ZoneId = utc): Long =
        LocalDateTime.of(year, month, day, hour, 0).atZone(zone).toInstant().toEpochMilli()

    private fun ev(song: Long, type: String, at: Long) = TrackListenEventEntity(
        songId = song, eventType = type, occurredAt = at, listenedMs = if (type == "PLAY") 60_000L else 0L, durationMs = 200_000L,
        source = TrackListenEventEntity.SOURCE_WAVDROP_PLAYBACK,
    )

    private fun play(song: Long, at: Long) = ev(song, TrackListenEventEntity.TYPE_PLAY, at)
    private fun skip(song: Long, at: Long) = ev(song, TrackListenEventEntity.TYPE_SKIP, at)

    private val songs = (1L..4L).map {
        Song(id = it, title = "S$it", artist = "A$it", album = "B", albumId = 0L, duration = 200_000L,
            uri = "content://media/$it", dateAdded = 0L, trackNumber = 0, year = 2020)
    }
    private val stats = listOf(TrackStatsEntity(songId = 1L, contentUri = "content://media/1", playCount = 9, skipCount = 2, totalListeningTimeMs = 5_000L))

    /** The in-memory event table with the shape of the new DAO reads and live range-subscription accounting. */
    private class Table(initial: List<TrackListenEventEntity>) {
        val rows = MutableStateFlow(initial.sortedByDescending { it.occurredAt })
        val rangesRequested = mutableListOf<Pair<Long, Long>>()
        var activeRangeSubscriptions = 0
        var fullEntityReads = 0 // must stay 0: nothing here exposes allListenEvents

        fun insert(e: TrackListenEventEntity) { rows.value = (rows.value + e).sortedByDescending { it.occurredAt } }

        val count: Flow<Int> get() = rows.map { it.size }
        val analyticsTimestamps: Flow<List<Long>> get() = rows.map { l -> l.filter { it.eventType == "PLAY" || it.eventType == "SKIP" }.map { it.occurredAt } }
        fun playActivity(zone: ZoneId, today: LocalDate): Flow<InsightsPlayActivity> =
            rows.map { l -> InsightsPlayActivityReader.reduce(l.filter { it.eventType == "PLAY" }.map { it.occurredAt }.asSequence(), zone, today) }

        fun inRange(from: Long, to: Long): Flow<List<TrackListenEventEntity>> =
            rows.map { l -> l.filter { it.occurredAt in from..to } }
                .onStart { rangesRequested += from to to; activeRangeSubscriptions++ }
                .onCompletion { activeRangeSubscriptions-- }
    }

    /** Collects [flow] on an unconfined scope, runs [block], then cancels. [latest] holds the newest value. */
    private fun <T> withCollected(flow: Flow<T>, block: suspend (latest: () -> T?, all: List<T>) -> Unit) = runBlocking {
        val seen = mutableListOf<T>()
        val job: Job = CoroutineScope(Dispatchers.Unconfined).launch { flow.collect { seen += it } }
        try { block({ seen.lastOrNull() }, seen) } finally { job.cancel() }
    }

    private suspend fun awaitTrue(what: String, cond: () -> Boolean) {
        withTimeout(5_000) { while (!cond()) delay(5) }
    }

    // ── Diagnostics ──────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun diagnosticsShowsTheEventCountWithoutLoadingRowsAndTracksChanges() {
        val table = Table(emptyList())
        val flow = libraryCountsFlow(MutableStateFlow(songs), MutableStateFlow(3), table.count)
        withCollected(flow) { latest, _ ->
            awaitTrue("initial") { latest() != null }
            assertEquals(0, latest()!!.listenEventCount)
            assertEquals(4, latest()!!.songCount)
            assertEquals(3, latest()!!.playlistCount)
            table.insert(play(1, 1_000L)); table.insert(skip(2, 2_000L)); table.insert(ev(3, "FUTURE_TYPE", 3_000L))
            awaitTrue("count updates") { latest()!!.listenEventCount == 3 } // COUNT(*) counts every row, as `.size` did
            assertEquals(0, table.fullEntityReads)
        }
    }

    // ── availability parity + timezone ───────────────────────────────────────────────────────────────────────────────────

    private val mixedEvents = listOf(
        play(1, at(2025, 12, 31, 23)), skip(2, at(2026, 1, 1, 0)), play(1, at(2026, 6, 30, 23)), skip(3, at(2026, 7, 1, 1)),
        ev(1, "FUTURE_TYPE", at(2024, 2, 2)), play(4, at(2026, 6, 1, 0)),
    )

    private fun daoAnalyticsTimestamps(events: List<TrackListenEventEntity>) =
        events.filter { it.eventType == "PLAY" || it.eventType == "SKIP" }.map { it.occurredAt }

    @Test fun availableMonthsAndYearsFromTimestampsEqualTheEntityBasedOutputInEveryZone() {
        for (zone in listOf(ZoneOffset.UTC, ZoneId.of("Pacific/Auckland"), ZoneId.of("America/Los_Angeles"), ZoneId.of("Asia/Kolkata"))) {
            val ts = daoAnalyticsTimestamps(mixedEvents)
            assertEquals(zone.toString(), MonthlyReportBuilder.availableMonths(emptyList(), mixedEvents, zone), MonthlyReportBuilder.availableMonthsFromTimestamps(ts, zone))
            assertEquals(zone.toString(), WrappedBuilder.availableMonths(mixedEvents, zone), WrappedBuilder.availableMonthsFromTimestamps(ts, zone))
            assertEquals(zone.toString(), WrappedBuilder.availableYears(mixedEvents, zone), WrappedBuilder.availableYearsFromTimestamps(ts, zone))
        }
        assertFalse("unsupported types contribute nothing", MonthlyReportBuilder.availableMonthsFromTimestamps(daoAnalyticsTimestamps(mixedEvents), utc).contains(MonthYear(2024, 2)))
    }

    @Test fun aTimestampBelongsToTheSameLocalMonthInAvailabilityAndInTheSelectedRange() {
        val auckland = ZoneId.of("Pacific/Auckland") // UTC+12 in June/July
        val ts = at(2026, 6, 30, 23, utc) // June 30 UTC, July 1 local
        assertEquals(listOf(MonthYear(2026, 7)), AnalyticsPeriodIndex.availableMonths(listOf(ts), auckland))
        assertEquals(listOf(MonthYear(2026, 6)), AnalyticsPeriodIndex.availableMonths(listOf(ts), utc))
        assertTrue(ListeningPeriodRange.month(2026, 7, auckland).contains(ts))
        assertFalse(ListeningPeriodRange.month(2026, 6, auckland).contains(ts))
        val newYear = at(2025, 12, 31, 23, utc)
        assertEquals(listOf(2026), AnalyticsPeriodIndex.availableYears(listOf(newYear), auckland))
        assertEquals(listOf(2025), AnalyticsPeriodIndex.availableYears(listOf(newYear), utc))
        assertTrue(ListeningPeriodRange.year(2026, auckland).contains(newYear))
    }

    // ── Monthly Reports ──────────────────────────────────────────────────────────────────────────────────────────────────

    private val eventsAcrossMonths = listOf(
        play(1, at(2026, 6, 3)), play(2, at(2026, 6, 4)), skip(2, at(2026, 6, 5)),
        play(1, at(2026, 5, 10)), skip(3, at(2026, 5, 11)),
        play(3, at(2026, 4, 1)),
    )

    private fun monthly(table: Table, requested: MutableStateFlow<MonthYear?>) =
        monthlyReportsFlow(MutableStateFlow(songs), MutableStateFlow(stats), table.analyticsTimestamps, requested, table::inRange, utc)

    @Test fun monthlyReportsSubscribeOnlyToTheSelectedMonthAndMatchTheFullHistoryReport() {
        val table = Table(eventsAcrossMonths)
        withCollected(monthly(table, MutableStateFlow(null))) { latest, _ ->
            awaitTrue("content") { latest() is MonthlyReportsUiState.Content }
            val content = latest() as MonthlyReportsUiState.Content
            assertEquals(MonthYear(2026, 6), content.selectedMonth) // latest available month by default
            assertEquals(listOf(MonthYear(2026, 6), MonthYear(2026, 5), MonthYear(2026, 4)), content.availableMonths)
            assertEquals(listOf(ListeningPeriodRange.month(2026, 6, utc).let { it.fromMs to it.toMs }), table.rangesRequested)
            assertEquals(MonthlyReportBuilder.build(MonthYear(2026, 6), songs, stats, table.rows.value, utc), content.report) // full history, most recent first, as observeAll supplied
        }
    }

    @Test fun switchingTheMonthSwitchesTheRangeSubscriptionAndReleasesTheOldOne() {
        val table = Table(eventsAcrossMonths)
        val requested = MutableStateFlow<MonthYear?>(null)
        withCollected(monthly(table, requested)) { latest, _ ->
            awaitTrue("june") { (latest() as? MonthlyReportsUiState.Content)?.selectedMonth == MonthYear(2026, 6) }
            requested.value = MonthYear(2026, 5)
            awaitTrue("may") { (latest() as? MonthlyReportsUiState.Content)?.selectedMonth == MonthYear(2026, 5) }
            val may = ListeningPeriodRange.month(2026, 5, utc)
            assertEquals(may.fromMs to may.toMs, table.rangesRequested.last())
            assertEquals("the previous month's subscription is cancelled", 1, table.activeRangeSubscriptions)
            assertEquals(MonthlyReportBuilder.build(MonthYear(2026, 5), songs, stats, table.rows.value, utc), (latest() as MonthlyReportsUiState.Content).report)
            requested.value = MonthYear(2030, 1) // not available: falls back to the latest month
            awaitTrue("fallback") { (latest() as? MonthlyReportsUiState.Content)?.selectedMonth == MonthYear(2026, 6) }
            assertEquals(1, table.activeRangeSubscriptions)
        }
    }

    @Test fun monthlyReportsEmptyStateAndNoRangeSubscriptionWithoutEvents() {
        val table = Table(emptyList())
        withCollected(monthly(table, MutableStateFlow(null))) { latest, _ ->
            awaitTrue("nodata") { latest() == MonthlyReportsUiState.NoData }
            assertTrue(table.rangesRequested.isEmpty())
            assertEquals(0, table.activeRangeSubscriptions)
        }
    }

    @Test fun monthlyReportsReactToInsertsInsideTheSelectedMonthAndIgnoreOtherMonthsWithoutResubscribing() {
        val table = Table(eventsAcrossMonths)
        withCollected(monthly(table, MutableStateFlow(null))) { latest, _ ->
            awaitTrue("content") { latest() is MonthlyReportsUiState.Content }
            val before = (latest() as MonthlyReportsUiState.Content).report.totalPlayCount
            table.insert(play(4, at(2026, 6, 20)))
            awaitTrue("june insert") { (latest() as MonthlyReportsUiState.Content).report.totalPlayCount == before + 1 }
            val subscriptions = table.rangesRequested.size
            table.insert(play(4, at(2026, 4, 20))) // an existing OTHER month: availability unchanged
            delay(50)
            assertEquals("an out-of-selection insert must not change the subscription", subscriptions, table.rangesRequested.size)
            assertEquals(before + 1, (latest() as MonthlyReportsUiState.Content).report.totalPlayCount)
            table.insert(play(4, at(2026, 8, 2))) // a previously absent month appears
            awaitTrue("new month available") { (latest() as MonthlyReportsUiState.Content).availableMonths.first() == MonthYear(2026, 8) }
            assertEquals("the selection stays on the user's month (June was the default, now August is latest)", MonthYear(2026, 8), (latest() as MonthlyReportsUiState.Content).selectedMonth)
        }
    }

    @Test fun skipOnlyMonthsAreAvailableAndReported() {
        val table = Table(listOf(skip(2, at(2026, 3, 3)), skip(2, at(2026, 3, 4))))
        withCollected(monthly(table, MutableStateFlow(null))) { latest, _ ->
            awaitTrue("content") { latest() is MonthlyReportsUiState.Content }
            val c = latest() as MonthlyReportsUiState.Content
            assertEquals(listOf(MonthYear(2026, 3)), c.availableMonths)
            assertEquals(2, c.report.totalSkipCount)
            assertEquals(0, c.report.totalPlayCount)
        }
    }

    // ── Wrapped ──────────────────────────────────────────────────────────────────────────────────────────────────────────

    private val visual = WrappedVisualPreferences(true, WrappedBackgroundIntensity.entries.first(), WrappedFallbackTheme.entries.first(), WrappedVisualStyle.entries.first())

    private fun wrapped(table: Table, request: MutableStateFlow<WrappedSelectionRequest>) = wrappedStateFlow(
        songData = MutableStateFlow(songs to stats), analyticsTimestamps = table.analyticsTimestamps, selection = request,
        showMilestoneCelebrations = MutableStateFlow(true), visualPreferences = MutableStateFlow(visual), eventsInRange = table::inRange, zone = utc,
    )

    @Test fun wrappedMonthlyAndYearlySubscribeOnlyToTheirPeriodAndMatchTheFullHistorySummary() {
        val table = Table(eventsAcrossMonths + play(2, at(2025, 11, 5)))
        val request = MutableStateFlow(WrappedSelectionRequest(WrappedScope.MONTHLY, null, null))
        withCollected(wrapped(table, request)) { latest, _ ->
            awaitTrue("monthly") { latest() is WrappedUiState.Content }
            var c = latest() as WrappedUiState.Content
            assertEquals(WrappedPeriod.month(MonthYear(2026, 6), utc), c.currentPeriod)
            assertEquals(listOf(WrappedPeriod.month(MonthYear(2026, 6), utc).range).map { it.fromMs to it.toMs }, table.rangesRequested)
            assertEquals(WrappedBuilder.buildPeriod(c.currentPeriod, songs, table.rows.value), c.summary)
            assertEquals(listOf(2026, 2025), c.availableYears)
            assertEquals(MonthYear(2025, 11), c.availableMonths.last())

            request.value = WrappedSelectionRequest(WrappedScope.YEARLY, 2025, null)
            awaitTrue("yearly") { (latest() as? WrappedUiState.Content)?.currentPeriod == WrappedPeriod.year(2025, utc) }
            c = latest() as WrappedUiState.Content
            val year = WrappedPeriod.year(2025, utc).range
            assertEquals(year.fromMs to year.toMs, table.rangesRequested.last())
            assertEquals(1, table.activeRangeSubscriptions)
            assertEquals(WrappedBuilder.buildPeriod(c.currentPeriod, songs, table.rows.value), c.summary)
        }
    }

    @Test fun wrappedAllTimeNeedsNoEventStreamAndKeepsCompleteAvailability() {
        val table = Table(eventsAcrossMonths)
        val request = MutableStateFlow(WrappedSelectionRequest(WrappedScope.ALL_TIME, null, null))
        withCollected(wrapped(table, request)) { latest, _ ->
            awaitTrue("alltime") { latest() is WrappedUiState.Content }
            val c = latest() as WrappedUiState.Content
            assertTrue("no event entity subscription for the aggregate summary", table.rangesRequested.isEmpty())
            assertEquals(WrappedPeriod.AllTime, c.currentPeriod)
            assertEquals(WrappedBuilder.buildAllTime(songs, stats), c.summary)
            assertEquals(listOf(2026), c.availableYears)
            assertEquals(3, c.availableMonths.size)
            table.insert(play(1, at(2027, 1, 2)))
            awaitTrue("new year") { (latest() as WrappedUiState.Content).availableYears.first() == 2027 }
            assertTrue(table.rangesRequested.isEmpty())
        }
    }

    @Test fun wrappedWithNoEventsKeepsItsEmptyBehaviourPerScope() {
        val table = Table(emptyList())
        val request = MutableStateFlow(WrappedSelectionRequest(WrappedScope.MONTHLY, null, null))
        withCollected(wrapped(table, request)) { latest, _ ->
            awaitTrue("empty") { latest() == WrappedUiState.Empty }
            request.value = WrappedSelectionRequest(WrappedScope.ALL_TIME, null, null)
            awaitTrue("alltime content") { latest() is WrappedUiState.Content }
            assertEquals(0, (latest() as WrappedUiState.Content).selectedYear)
            assertTrue(table.rangesRequested.isEmpty())
        }
    }

    // ── Most Played ──────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun mostPlayedAllTimeUsesNoEventStreamAndThisMonthUsesOnlyTheCurrentMonth() {
        val table = Table(listOf(play(1, at(2026, 5, 20)), play(2, at(2026, 5, 21)), play(2, at(2026, 6, 2)), play(3, at(2026, 6, 3)), play(3, at(2026, 6, 4))))
        val period = MutableStateFlow(MostPlayedPeriod.ALL_TIME)
        val flow = mostPlayedSummariesFlow(
            MutableStateFlow(songs), MutableStateFlow(stats), period, MutableStateFlow(MostPlayedDisplayLimit.TOP_25),
            table::inRange, table.analyticsTimestamps, utc, nowMs = { at(2026, 6, 15) },
        )
        withCollected(flow) { latest, _ ->
            awaitTrue("alltime") { latest() != null }
            assertEquals(MostPlayedBuilder.build(songs, stats, emptyList(), MostPlayedPeriod.ALL_TIME, zone = utc), latest())
            assertTrue(table.rangesRequested.isEmpty())

            period.value = MostPlayedPeriod.THIS_MONTH
            awaitTrue("month") { table.rangesRequested.isNotEmpty() }
            val june = ListeningPeriodRange.month(2026, 6, utc)
            assertEquals(listOf(june.fromMs to june.toMs), table.rangesRequested)
            awaitTrue("june counts") { latest()?.map { it.song.id to it.playCount }?.toSet() == setOf(3L to 2, 2L to 1) }
            assertEquals(MostPlayedBuilder.build(songs, stats, table.rows.value, MostPlayedPeriod.THIS_MONTH, nowMs = at(2026, 6, 15), zone = utc), latest())

            period.value = MostPlayedPeriod.ALL_TIME
            awaitTrue("released") { table.activeRangeSubscriptions == 0 }
        }
    }

    // ── Statistics / Insights ────────────────────────────────────────────────────────────────────────────────────────────

    /** The pre-WC-02 inline logic, reimplemented as the reference the timestamp versions must match. */
    private fun referenceDay(events: List<TrackListenEventEntity>, zone: ZoneId): DayOfWeek? =
        events.filter { it.eventType == "PLAY" }.groupBy { Instant.ofEpochMilli(it.occurredAt).atZone(zone).dayOfWeek }.maxByOrNull { it.value.size }?.key

    private fun referenceHour(events: List<TrackListenEventEntity>, zone: ZoneId): Int? =
        events.filter { it.eventType == "PLAY" }.groupBy { Instant.ofEpochMilli(it.occurredAt).atZone(zone).hour }.maxByOrNull { it.value.size }?.key

    private fun referenceStreak(events: List<TrackListenEventEntity>, zone: ZoneId, today: LocalDate): Int {
        val days = events.filter { it.eventType == "PLAY" }.map { Instant.ofEpochMilli(it.occurredAt).atZone(zone).toLocalDate() }
            .filter { it.year == today.year }.toSortedSet().toList()
        if (days.isEmpty() || days.last() < today.minusDays(1)) return 0
        var streak = 1
        for (i in days.lastIndex - 1 downTo 0) if (days[i + 1] == days[i].plusDays(1)) streak++ else break
        return streak
    }

    private val today = LocalDate.of(2026, 6, 15)
    private val streakEvents = listOf(
        play(1, at(2026, 6, 15, 9)), play(1, at(2026, 6, 14, 22)), play(2, at(2026, 6, 13, 9)), skip(3, at(2026, 6, 12, 9)),
        play(2, at(2026, 6, 11, 9)), play(3, at(2026, 6, 11, 9)), play(3, at(2026, 1, 5, 3)), play(1, at(2025, 12, 30, 9)),
    ).sortedByDescending { it.occurredAt }

    @Test fun timestampStreakWeekdayAndHourMatchTheReferenceLogicIncludingTies() {
        val plays = streakEvents.filter { it.eventType == "PLAY" }.map { it.occurredAt } // most recent first
        assertEquals(referenceStreak(streakEvents, utc, today), InsightsSummaryBuilder.currentStreakDaysFromPlayTimestamps(plays, utc, today))
        assertEquals(3, InsightsSummaryBuilder.currentStreakDaysFromPlayTimestamps(plays, utc, today)) // 15, 14, 13 (12 had only a SKIP)
        assertEquals(referenceDay(streakEvents, utc), InsightsSummaryBuilder.mostActiveDayOfWeekFromPlayTimestamps(plays, utc))
        assertEquals(referenceHour(streakEvents, utc), InsightsSummaryBuilder.mostActiveHourFromPlayTimestamps(plays, utc))
        // the entity overloads delegate to the same canonical implementation
        assertEquals(InsightsSummaryBuilder.mostActiveHour(streakEvents, utc), InsightsSummaryBuilder.mostActiveHourFromPlayTimestamps(plays, utc))
        assertEquals(null, InsightsSummaryBuilder.mostActiveDayOfWeekFromPlayTimestamps(emptyList(), utc))
        assertEquals(0, InsightsSummaryBuilder.currentStreakDaysFromPlayTimestamps(emptyList(), utc, today))
    }

    @Test fun anAllSkipHistoryProducesNoStreakDayOrHour() {
        val table = Table(listOf(skip(1, at(2026, 6, 15)), skip(2, at(2026, 6, 14))))
        withCollected(statisticsInsightsFlow(table.playActivity(utc, LocalDate.now(utc)), MutableStateFlow(setOf(1L, 2L)), utc)) { latest, _ ->
            awaitTrue("insights") { latest() != null }
            assertEquals(0, latest()!!.currentStreakDays)
            assertEquals(null, latest()!!.mostActiveDayOfWeek)
            assertEquals(2, latest()!!.favoritesCount)
        }
    }

    @Test fun statisticsInsightsUpdateWhenANewPlayArrivesAndIgnoreSkips() {
        val table = Table(emptyList())
        withCollected(statisticsInsightsFlow(table.playActivity(utc, LocalDate.now(utc)), MutableStateFlow(emptySet()), utc)) { latest, _ ->
            awaitTrue("initial") { latest() != null }
            assertEquals(null, latest()!!.mostActiveDayOfWeek)
            table.insert(skip(1, at(2026, 6, 15)))
            delay(30)
            assertEquals(null, latest()!!.mostActiveDayOfWeek)
            table.insert(play(1, at(2026, 6, 15))) // a Monday
            awaitTrue("play seen") { latest()!!.mostActiveDayOfWeek == DayOfWeek.MONDAY }
        }
    }

    @Test fun insightsHubUsesThisMonthEventsForTheSummaryAndTimestampsForStreakDayAndHour() {
        val allEvents = streakEvents + play(2, at(2026, 5, 2, 7)) + play(2, at(2026, 6, 2, 7))
        val table = Table(allEvents)
        val monthRange = ListeningPeriodRange.month(2026, 6, utc)
        val flow = insightsHubFlow(
            songs = MutableStateFlow(songs), stats = MutableStateFlow(stats),
            thisMonthEvents = table.inRange(monthRange.fromMs, monthRange.toMs).map { MonthScopedEvents(MonthYear(2026, 6), it) }, playActivity = table.playActivity(utc, LocalDate.of(2026, 6, 15)),
            collections = MutableStateFlow(emptyList()), zone = utc, now = { LocalDateTime.of(2026, 6, 15, 10, 0) },
        )
        withCollected(flow) { latest, _ ->
            awaitTrue("content") { latest() is InsightsHubUiState.Content }
            val c = latest() as InsightsHubUiState.Content
            val expectedMonth = ListeningAnalyticsBuilder.build(monthRange, songs, stats, table.rows.value)
            assertEquals(expectedMonth.totalPlayCount, c.thisMonthPlayCount)
            assertEquals(expectedMonth.totalListeningTimeMs, c.thisMonthListeningTimeMs)
            assertEquals(referenceStreak(allEvents, utc, today), c.currentStreakDays)
            assertEquals(referenceDay(allEvents.sortedByDescending { it.occurredAt }, utc), c.mostActiveDayOfWeek)
            assertEquals(referenceHour(allEvents.sortedByDescending { it.occurredAt }, utc), c.mostActiveHour)
            assertEquals("only the current month's range was subscribed", listOf(monthRange.fromMs to monthRange.toMs), table.rangesRequested)
        }
    }

    // ── current-month rollover ───────────────────────────────────────────────────────────────────────────────────────────

    private val october = MonthYear(2026, 10)
    private val november = MonthYear(2026, 11)
    private fun range(m: MonthYear, zone: ZoneId = utc) = ListeningPeriodRange.month(m.year, m.month, zone).let { it.fromMs to it.toMs }

    @Test fun currentMonthStartsFromTheClockAndIgnoresSameMonthTriggers() {
        val clock = at(2026, 10, 31, 12)
        val trigger = MutableStateFlow(0)
        withCollected(currentCalendarMonthFlow(trigger, utc) { clock }) { latest, all ->
            awaitTrue("initial") { latest() != null }
            assertEquals(october, latest())
            trigger.value = 1; trigger.value = 2
            delay(30)
            assertEquals("same-month triggers never re-emit", listOf(october), all)
        }
    }

    @Test fun currentMonthSwitchesExactlyOnceOnTheFirstTriggerAfterRollover() {
        var clock = at(2026, 10, 31, 12)
        val trigger = MutableStateFlow(0)
        withCollected(currentCalendarMonthFlow(trigger, utc) { clock }) { latest, all ->
            awaitTrue("initial") { latest() != null }
            clock = at(2026, 11, 1, 0) // time passes; nothing reacts without a trigger
            delay(30)
            assertEquals(listOf(october), all)
            trigger.value = 1
            awaitTrue("november") { latest() == november }
            trigger.value = 2; trigger.value = 3
            delay(30)
            assertEquals(listOf(october, november), all)
        }
    }

    @Test fun aHistoricalEventAfterRolloverCannotMoveTheMonthBackwards() {
        var clock = at(2026, 10, 31, 12)
        val table = Table(emptyList())
        withCollected(currentCalendarMonthFlow(table.analyticsTimestamps, utc) { clock }) { latest, all ->
            awaitTrue("initial") { latest() != null }
            clock = at(2026, 11, 2, 9)
            table.insert(play(1, at(2026, 11, 2, 9)))
            awaitTrue("november") { latest() == november }
            table.insert(play(1, at(2026, 9, 5))) // restored / imported September event arrives later
            table.insert(skip(2, at(2025, 1, 1)))
            delay(30)
            assertEquals(listOf(october, november), all)
        }
    }

    @Test fun theInjectedZoneDecidesTheMonthBoundary() {
        val instant = at(2026, 10, 31, 12) // 2026-10-31T12:00Z == 2026-11-01T01:00 in Auckland (UTC+13)
        runBlocking {
            assertEquals(october, currentCalendarMonthFlow(MutableStateFlow(0), utc) { instant }.first())
            assertEquals(november, currentCalendarMonthFlow(MutableStateFlow(0), ZoneId.of("Pacific/Auckland")) { instant }.first())
            assertEquals(october, currentCalendarMonthFlow(MutableStateFlow(0), ZoneId.of("America/Los_Angeles")) { instant }.first())
        }
    }

    @Test fun insightsSummaryAlwaysPairsTheCurrentMonthWithItsOwnScopedEventsAcrossARollover() {
        var clock = at(2026, 10, 31, 12)
        val table = Table(listOf(play(1, at(2026, 10, 3)), play(1, at(2026, 10, 4)), play(2, at(2026, 9, 9))))
        val flow = insightsHubFlow(
            songs = MutableStateFlow(songs), stats = MutableStateFlow(stats),
            thisMonthEvents = currentMonthEventsFlow(table.analyticsTimestamps, table::inRange, utc) { clock },
            playActivity = table.playActivity(utc, LocalDate.of(2026, 10, 31)), collections = MutableStateFlow(emptyList()), zone = utc,
            now = { Instant.ofEpochMilli(clock).atZone(utc).toLocalDateTime() },
        )
        withCollected(flow) { latest, all ->
            awaitTrue("october") { (latest() as? InsightsHubUiState.Content)?.thisMonthPlayCount == 2 }
            assertEquals(listOf(range(october)), table.rangesRequested)

            clock = at(2026, 11, 1, 8)
            table.insert(play(3, at(2026, 11, 1, 8)))
            awaitTrue("november") { (latest() as? InsightsHubUiState.Content)?.thisMonthPlayCount == 1 }
            assertEquals("november summary is built from november rows only", "S3", (latest() as InsightsHubUiState.Content).thisMonthTopTrackTitle)
            assertEquals(listOf(range(october), range(november)), table.rangesRequested)
            awaitTrue("october range released") { table.activeRangeSubscriptions == 1 }

            // no emission ever combined a november range with october rows (October rows would give 2 plays / S1 as the top song)
            val contents = all.filterIsInstance<InsightsHubUiState.Content>()
            assertTrue(contents.none { it.thisMonthPlayCount == 2 && it.thisMonthTopTrackTitle != "S1" })
            assertTrue(contents.none { it.thisMonthPlayCount == 1 && it.thisMonthTopTrackTitle != "S3" })
        }
    }

    @Test fun mostPlayedThisMonthFollowsTheRolloverAndAllTimeReleasesTheRange() {
        var clock = at(2026, 10, 31, 12)
        val table = Table(listOf(play(1, at(2026, 10, 3)), play(2, at(2026, 10, 4)), play(2, at(2026, 10, 5))))
        val period = MutableStateFlow(MostPlayedPeriod.THIS_MONTH)
        val flow = mostPlayedSummariesFlow(
            MutableStateFlow(songs), MutableStateFlow(stats), period, MutableStateFlow(MostPlayedDisplayLimit.TOP_25),
            table::inRange, table.analyticsTimestamps, utc, nowMs = { clock },
        )
        withCollected(flow) { latest, _ ->
            awaitTrue("october") { latest()?.map { it.song.id to it.playCount }?.toSet() == setOf(2L to 2, 1L to 1) }
            assertEquals(listOf(range(october)), table.rangesRequested)

            clock = at(2026, 11, 1, 8)
            table.insert(play(3, at(2026, 11, 1, 8)))
            awaitTrue("november") { latest()?.map { it.song.id to it.playCount } == listOf(3L to 1) }
            assertEquals(listOf(range(october), range(november)), table.rangesRequested)
            awaitTrue("one live range") { table.activeRangeSubscriptions == 1 }

            period.value = MostPlayedPeriod.ALL_TIME
            awaitTrue("released") { table.activeRangeSubscriptions == 0 }

            period.value = MostPlayedPeriod.THIS_MONTH // the then-current month, not the one bound earlier
            awaitTrue("rebound") { table.activeRangeSubscriptions == 1 }
            assertEquals(range(november), table.rangesRequested.last())
            assertEquals(3, table.rangesRequested.size)
            awaitTrue("november again") { latest()?.map { it.song.id to it.playCount } == listOf(3L to 1) }
        }
    }

    // ── source guards ────────────────────────────────────────────────────────────────────────────────────────────────────

    private fun read(path: String) = File("src/main/kotlin/com/launchpoint/wavdrop/$path").readText().replace("\r\n", "\n")

    @Test fun noTargetViewModelSubscribesToTheFullEventHistoryAndEachUsesItsNarrowRead() {
        val expected = mapOf(
            "ui/screen/monthlyreports/MonthlyReportsViewModel.kt" to listOf("analyticsEventTimestamps()", "listenEventsInRange"),
            "ui/screen/settings/InsightsViewModel.kt" to listOf("playActivity()", "listenEventsInRange", "listenEventCount()", "currentMonthEventsFlow"),
            "ui/screen/statistics/StatisticsViewModel.kt" to listOf("playActivity()"),
            "ui/screen/wrapped/WrappedViewModel.kt" to listOf("analyticsEventTimestamps()", "listenEventsInRange"),
            "ui/screen/smart/SmartCollectionDetailsViewModel.kt" to listOf("listenEventsInRange", "analyticsEventTimestamps()", "currentMonthEventsFlow"),
            "ui/screen/settings/SettingsDiagnosticsViewModel.kt" to listOf("listenEventCount()"),
        )
        for ((file, uses) in expected) {
            val code = read(file).lines().filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") }.joinToString("\n")
            assertFalse("$file still calls allListenEvents()", code.contains("allListenEvents("))
            uses.forEach { assertTrue("$file should use $it", code.contains(it)) }
            assertFalse("$file keeps the false fixed-month comment", read(file).contains("resolved each time"))
        }
        val productionCallers = File("src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { f -> f.readLines().filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") }.any { it.contains("allListenEvents()") && !it.contains("fun allListenEvents") } }
            .map { it.name }.toList()
        assertTrue("remaining full-history entity callers: $productionCallers", productionCallers.isEmpty())
    }

    @Test fun theDaoReadsKeepTheOldFilterAndOrderSemantics() {
        val dao = read("data/local/dao/TrackListenEventDao.kt")
        assertTrue(dao.contains("SELECT COUNT(*) FROM track_listen_events"))
        assertTrue(dao.contains("SELECT occurredAt FROM track_listen_events WHERE eventType IN ('PLAY', 'SKIP')"))
        assertTrue("PLAY timestamps are streamed through a cursor (WC-09): PLAY only, no ORDER BY (the tie-break is explicit latest-play, not row order)",
            dao.contains("SELECT occurredAt FROM track_listen_events WHERE eventType = 'PLAY'\"") && !dao.contains("observePlayEventTimestamps"))
        assertFalse("month/year membership must stay in Kotlin ZoneId logic, never SQLite strftime", dao.contains("strftime"))
        assertEquals("TYPE constants still match the literals", "PLAY" to "SKIP", TrackListenEventEntity.TYPE_PLAY to TrackListenEventEntity.TYPE_SKIP)
    }
}
