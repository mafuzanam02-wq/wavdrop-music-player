package com.launchpoint.wavdrop.data.stats

import androidx.room.Room
import androidx.sqlite.db.SimpleSQLiteQuery
import com.launchpoint.wavdrop.data.local.WavdropDatabase
import com.launchpoint.wavdrop.data.local.entity.TrackListenEventEntity
import com.launchpoint.wavdrop.data.repository.StatsRepository
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** WC-09 on real Room/SQLite: the cursor path, SKIP exclusion, reactive recompute and the query plan. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class InsightsPlayActivityRoomTest {

    private lateinit var db: WavdropDatabase
    private lateinit var repo: StatsRepository
    private val utc = ZoneId.of("UTC")

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), WavdropDatabase::class.java).allowMainThreadQueries().build()
        repo = StatsRepository(db, db.trackStatsDao(), db.importBaselineDao(), db.trackListenEventDao())
    }

    @After
    fun tearDown() = db.close()

    private fun ms(y: Int, m: Int, d: Int, h: Int = 12) = LocalDateTime.of(y, m, d, h, 0).atZone(utc).toInstant().toEpochMilli()

    private fun ev(type: String, at: Long, song: Long = 1L) = TrackListenEventEntity(
        songId = song, eventType = type, occurredAt = at, listenedMs = if (type == "PLAY") 60_000L else 0L, durationMs = 200_000L,
        source = TrackListenEventEntity.SOURCE_WAVDROP_PLAYBACK,
    )

    private suspend fun activity(today: LocalDate = LocalDate.of(2026, 6, 15)): InsightsPlayActivity =
        db.trackListenEventDao().playTimestampCursor().use { InsightsPlayActivityReader.reduce(it, utc, today) }

    @Test fun anEmptyOrAllSkipDatabaseHasNoDayHourOrStreak() = runBlocking {
        assertNull(activity().mostActiveDayOfWeek)
        db.trackListenEventDao().insertAll(listOf(ev("SKIP", ms(2026, 6, 15)), ev("SKIP", ms(2026, 6, 14)), ev("PAUSE", ms(2026, 6, 13))))
        val a = activity()
        assertNull(a.mostActiveDayOfWeek); assertNull(a.mostActiveHour)
        assertEquals(0, a.currentStreakDays(LocalDate.of(2026, 6, 15)))
    }

    @Test fun onlyPlayRowsCountAndTheCursorMatchesTheOldTimestampList() = runBlocking {
        val events = listOf(
            ev("PLAY", ms(2026, 6, 15, 9)), ev("PLAY", ms(2026, 6, 14, 22)), ev("SKIP", ms(2026, 6, 13, 9)), ev("PLAY", ms(2026, 6, 13, 3)),
            ev("PLAY", ms(2025, 12, 30, 9)), ev("FUTURE", ms(2026, 6, 12, 9)), ev("PLAY", ms(2024, 2, 1, 9)),
        )
        db.trackListenEventDao().insertAll(events)
        val plays = events.filter { it.eventType == "PLAY" }.map { it.occurredAt }.sortedDescending()
        val today = LocalDate.of(2026, 6, 15)
        val a = activity(today)
        assertEquals(InsightsSummaryBuilder.mostActiveDayOfWeekFromPlayTimestamps(plays, utc), a.mostActiveDayOfWeek)
        assertEquals(InsightsSummaryBuilder.mostActiveHourFromPlayTimestamps(plays, utc), a.mostActiveHour)
        assertEquals(InsightsSummaryBuilder.currentStreakDaysFromPlayTimestamps(plays, utc, today), a.currentStreakDays(today))
        assertEquals(3, a.currentStreakDays(today)) // 13, 14, 15; the SKIP day did not add a day, the PLAY at 03:00 on the 13th did
    }

    @Test fun anInsertedPlayAndARestoredHistoricalPlayBothChangeTheSummary() = runBlocking {
        val dao = db.trackListenEventDao()
        dao.insert(ev("PLAY", ms(2026, 6, 15, 9))) // Monday 09
        assertEquals(DayOfWeek.MONDAY, activity().mostActiveDayOfWeek)
        dao.insert(ev("PLAY", ms(2026, 6, 16, 10))) // Tuesday 10 -> tie, latest wins
        assertEquals(DayOfWeek.TUESDAY, activity().mostActiveDayOfWeek)
        dao.insert(ev("PLAY", ms(2019, 3, 4, 9))) // restored OLD Monday play -> Monday now has more plays
        val a = activity()
        assertEquals(DayOfWeek.MONDAY, a.mostActiveDayOfWeek); assertEquals(9, a.mostActiveHour)
    }

    @Test fun theRepositoryFlowRecomputesOnNewAndHistoricalEventsAndIgnoresNothingBySkip() = runBlocking {
        val dao = db.trackListenEventDao()
        dao.insert(ev("PLAY", ms(2026, 6, 15, 9)))
        val flow = repo.playActivity(utc)
        assertEquals(DayOfWeek.MONDAY, withTimeout(10_000) { flow.first().mostActiveDayOfWeek })

        val sawTuesday = async { flow.first { it.mostActiveDayOfWeek == DayOfWeek.TUESDAY } }
        dao.insert(ev("PLAY", ms(2026, 6, 16, 10)))
        assertEquals(10, withTimeout(10_000) { sawTuesday.await() }.mostActiveHour)

        val sawMonday = async { flow.first { it.mostActiveDayOfWeek == DayOfWeek.MONDAY && it.mostActiveHour == 9 } }
        dao.insert(ev("PLAY", ms(2018, 1, 1, 9))) // far older than the newest event: MAX(occurredAt) would not change
        assertTrue(withTimeout(10_000) { sawMonday.await() }.currentYearPlayDays.size <= 366)
    }

    @Test fun theQueryScansThePlayRowsWithoutBuildingAnyEntityOrSort() {
        val plan = db.query(SimpleSQLiteQuery("EXPLAIN QUERY PLAN SELECT occurredAt FROM track_listen_events WHERE eventType = 'PLAY'"), null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("detail"))) }
        }
        println("WC09 query plan: $plan")
        // No index leads with eventType, so this is a table scan (O(N) rows, no row materialization, no sort step).
        assertTrue(plan.joinToString(), plan.any { it.contains("SCAN") })
        assertTrue(plan.joinToString(), plan.none { it.contains("TEMP B-TREE") })
    }
}
