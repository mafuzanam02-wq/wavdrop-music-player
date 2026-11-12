package com.launchpoint.wavdrop.data.stats

import androidx.room.Room
import com.launchpoint.wavdrop.data.local.WavdropDatabase
import com.launchpoint.wavdrop.data.local.dao.TrackListenEventDao
import com.launchpoint.wavdrop.data.local.entity.TrackListenEventEntity
import com.launchpoint.wavdrop.data.repository.PlayOccurrence
import com.launchpoint.wavdrop.data.repository.StatsEventConflictException
import com.launchpoint.wavdrop.data.repository.StatsRepository
import com.launchpoint.wavdrop.data.repository.StatsWriteResult
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * P5-STATS-19B - Room-backed proof that PLAY and SKIP persistence is idempotent per occurrence id, atomic, safe against competing
 * writers, and monotonic for the aggregate timestamps. Schema 13: no new index or migration.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StatsIdempotencyRoomTest {

    private lateinit var db: WavdropDatabase
    private lateinit var realEvents: TrackListenEventDao
    private lateinit var repo: StatsRepository

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), WavdropDatabase::class.java)
            .allowMainThreadQueries().build()
        realEvents = db.trackListenEventDao()
        repo = StatsRepository(db, db.trackStatsDao(), db.importBaselineDao(), realEvents)
    }

    @After fun tearDown() = db.close()

    private val uri = "content://media/1"

    private suspend fun play(id: String, at: Long, song: Long = 1L, listened: Long = 30_000L, duration: Long = 120_000L, r: StatsRepository = repo) =
        r.recordPlayOccurrence(PlayOccurrence(id, at), song, "content://media/$song", listened, duration)

    private suspend fun skip(id: String, at: Long, song: Long = 1L, duration: Long = 120_000L, r: StatsRepository = repo) =
        r.recordSkipOccurrence(PlayOccurrence(id, at), song, "content://media/$song", duration)

    /** Every event row, all columns, in insertion order. */
    private fun events(): List<String> =
        db.openHelper.readableDatabase
            .query("SELECT songId, eventType, occurredAt, listenedMs, durationMs, source, eventId FROM track_listen_events ORDER BY id").use { c ->
                buildList {
                    while (c.moveToNext()) add((0 until 7).joinToString("|") { c.getString(it) ?: "null" })
                }
            }

    private suspend fun snapshot() = db.trackStatsDao().getAllStatsSnapshot() to events()

    private suspend fun stats(song: Long = 1L) = db.trackStatsDao().getStatsBySongId(song)!!

    private suspend fun assertConflict(block: suspend () -> Unit) {
        try { block(); fail("expected StatsEventConflictException") } catch (_: StatsEventConflictException) { }
    }

    // ── PLAY ──────────────────────────────────────────────────────────────────

    @Test fun `first PLAY is applied once with the supplied id and the captured time`() = runBlocking {
        assertEquals(StatsWriteResult.APPLIED, play("e1", at = 10_000L))
        val s = stats()
        assertEquals(1, s.playCount); assertEquals(0, s.skipCount)
        assertEquals(10_000L, s.lastPlayedAt); assertEquals(30_000L, s.totalListeningTimeMs)
        assertEquals(listOf("1|PLAY|10000|30000|120000|wavdrop_playback|e1"), events())
    }

    @Test fun `repeating the identical PLAY id changes nothing`() = runBlocking {
        play("e1", at = 10_000L)
        val before = snapshot()
        assertEquals(StatsWriteResult.ALREADY_APPLIED, play("e1", at = 10_000L))
        assertEquals(StatsWriteResult.ALREADY_APPLIED, play("e1", at = 10_000L))
        assertEquals(before, snapshot())
    }

    @Test fun `the same id with a contradictory payload is rejected and nothing is modified`() = runBlocking {
        play("e1", at = 10_000L)
        val before = snapshot()
        assertConflict { play("e1", at = 10_000L, listened = 31_000L) }       // listened differs
        assertConflict { play("e1", at = 10_000L, duration = 99_000L) }       // duration differs
        assertConflict { play("e1", at = 10_001L) }                           // time differs
        assertConflict { play("e1", at = 10_000L, song = 2L) }                // song differs
        assertConflict { skip("e1", at = 10_000L) }                           // type differs
        assertEquals("no row and no aggregate was touched, and song 2 got no stats row", before, snapshot())
    }

    // ── SKIP ──────────────────────────────────────────────────────────────────

    @Test fun `first SKIP is applied once and leaves the play fields alone`() = runBlocking {
        assertEquals(StatsWriteResult.APPLIED, skip("s1", at = 20_000L))
        val s = stats()
        assertEquals(1, s.skipCount); assertEquals(0, s.playCount)
        assertEquals(0L, s.lastPlayedAt); assertEquals(0L, s.totalListeningTimeMs)
        assertEquals(listOf("1|SKIP|20000|0|120000|wavdrop_playback|s1"), events())
    }

    @Test fun `repeating the identical SKIP id changes nothing and a contradictory one is rejected`() = runBlocking {
        skip("s1", at = 20_000L)
        val before = snapshot()
        assertEquals(StatsWriteResult.ALREADY_APPLIED, skip("s1", at = 20_000L))
        assertConflict { skip("s1", at = 20_000L, duration = 1L) }
        assertConflict { play("s1", at = 20_000L, listened = 0L) }
        assertEquals(before, snapshot())
    }

    // ── Competing writers ─────────────────────────────────────────────────────

    @Test fun `sixteen concurrent writers of the same PLAY id apply it exactly once`() = runBlocking {
        val results = (1..16).map { async(Dispatchers.IO) { play("e1", at = 10_000L) } }.awaitAll()
        assertEquals(1, results.count { it == StatsWriteResult.APPLIED })
        assertEquals(15, results.count { it == StatsWriteResult.ALREADY_APPLIED })
        assertEquals(1, stats().playCount)
        assertEquals(1, events().size)
    }

    @Test fun `sixteen concurrent writers of the same SKIP id apply it exactly once`() = runBlocking {
        val results = (1..16).map { async(Dispatchers.IO) { skip("s1", at = 20_000L) } }.awaitAll()
        assertEquals(1, results.count { it == StatsWriteResult.APPLIED })
        assertEquals(1, stats().skipCount)
        assertEquals(1, events().size)
    }

    @Test fun `concurrent writers of different ids lose no increments`() = runBlocking {
        (1..16).map { i -> async(Dispatchers.IO) { play("p$i", at = 1_000L * i) } }.awaitAll()
        (1..8).map { i -> async(Dispatchers.IO) { skip("k$i", at = 50_000L + i) } }.awaitAll()
        val s = stats()
        assertEquals(16, s.playCount); assertEquals(8, s.skipCount)
        assertEquals(16L * 30_000L, s.totalListeningTimeMs)
        assertEquals(24, events().size)
        assertEquals(16_000L, s.lastPlayedAt)
    }

    // ── Atomicity and uncertain results ───────────────────────────────────────

    @Test fun `a failure after the aggregate update rolls back, and repeating the same occurrence then applies it once`() = runBlocking {
        var failNext = true
        val flaky = object : TrackListenEventDao by realEvents {
            override suspend fun insert(event: TrackListenEventEntity) {
                if (failNext) { failNext = false; throw IOException("disk full") }
                realEvents.insert(event)
            }
        }
        val flakyRepo = StatsRepository(db, db.trackStatsDao(), db.importBaselineDao(), flaky)
        try { play("e1", at = 10_000L, r = flakyRepo); fail("expected IOException") } catch (_: IOException) { }
        assertEquals("aggregate AND stats row rolled back", 0, db.trackStatsDao().getAllStatsSnapshot().size)
        assertEquals(emptyList<String>(), events())

        assertEquals(StatsWriteResult.APPLIED, play("e1", at = 10_000L, r = flakyRepo))
        assertEquals(StatsWriteResult.ALREADY_APPLIED, play("e1", at = 10_000L, r = flakyRepo))
        assertEquals(1, stats().playCount)
        assertEquals(1, events().size)
    }

    // ── Separate occurrences ──────────────────────────────────────────────────

    @Test fun `separate legitimate occurrences both count, even with identical payload and time`() = runBlocking {
        play("e1", at = 10_000L); play("e2", at = 10_000L)       // same song, same ms, different ids
        skip("s1", at = 20_000L); skip("s2", at = 20_000L)
        val s = stats()
        assertEquals(2, s.playCount); assertEquals(2, s.skipCount)
        assertEquals(4, events().size)
    }

    // ── Timestamp monotonicity ────────────────────────────────────────────────

    @Test fun `delayed older occurrences keep their own event time but never move the aggregate timestamps backwards`() = runBlocking {
        play("late", at = 2_000L)
        play("early", at = 1_000L)                                // applied second, occurred first
        repo.recordListenStartAt(5_000L, 1L, uri)
        repo.recordListenStartAt(3_000L, 1L, uri)
        repo.recordListenStartAt(5_000L, 1L, uri)                 // repeating is harmless
        val s = stats()
        assertEquals(2, s.playCount)
        assertEquals(2_000L, s.lastPlayedAt)
        assertEquals(5_000L, s.lastListenedAt)
        assertEquals(listOf(2_000L, 1_000L), events().map { it.split("|")[2].toLong() })
    }

    // ── Legacy and restored events ────────────────────────────────────────────

    @Test fun `legacy null-id rows are never matched or altered, and a restored id is matched regardless of source`() = runBlocking {
        val legacy = TrackListenEventEntity(songId = 1L, eventType = "PLAY", occurredAt = 10_000L, listenedMs = 30_000L, durationMs = 120_000L,
            source = TrackListenEventEntity.SOURCE_WAVDROP_PLAYBACK, eventId = null)
        val restored = TrackListenEventEntity(songId = 1L, eventType = "PLAY", occurredAt = 11_000L, listenedMs = 30_000L, durationMs = 120_000L,
            source = TrackListenEventEntity.SOURCE_MANUAL_RESTORE, eventId = "r1")
        realEvents.insert(legacy); realEvents.insert(restored)
        val seeded = events()

        // Same payload as the null-id legacy row, but a new id: a new occurrence. Legacy rows are never deduplicated.
        assertEquals(StatsWriteResult.APPLIED, play("n1", at = 10_000L))
        // The restored id with an equivalent payload is recognised even though its source differs.
        assertEquals(StatsWriteResult.ALREADY_APPLIED, play("r1", at = 11_000L))
        assertConflict { play("r1", at = 11_000L, listened = 1L) }

        assertEquals(seeded + "1|PLAY|10000|30000|120000|wavdrop_playback|n1", events())
        assertEquals("only the n1 write counted", 1, stats().playCount)
    }


    // ── Schema-13 lookup cost ─────────────────────────────────────────────────

    @Test fun `CHARACTERISATION - the eventId lookup is a full table scan on schema 13`() {
        val plan = db.openHelper.readableDatabase
            .query("EXPLAIN QUERY PLAN SELECT * FROM track_listen_events WHERE eventId = 'x'").use { c ->
                buildList { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("detail"))) }
            }.joinToString(" / ")
        assertTrue("expected a SCAN, was: $plan", plan.contains("SCAN"))
        assertFalse("no index on eventId is expected on schema 13, was: $plan", plan.contains("USING INDEX") || plan.contains("USING COVERING INDEX"))
    }
}
