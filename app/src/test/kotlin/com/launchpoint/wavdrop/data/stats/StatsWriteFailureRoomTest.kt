package com.launchpoint.wavdrop.data.stats

import androidx.room.Room
import com.launchpoint.wavdrop.data.local.WavdropDatabase
import com.launchpoint.wavdrop.data.local.dao.TrackListenEventDao
import com.launchpoint.wavdrop.data.local.entity.TrackListenEventEntity
import com.launchpoint.wavdrop.data.local.entity.TrackStatsEntity
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.repository.PlayEventWriter
import com.launchpoint.wavdrop.data.repository.PlayOccurrence
import com.launchpoint.wavdrop.data.repository.StatsRepository
import com.launchpoint.wavdrop.data.repository.StatsWriteResult
import com.launchpoint.wavdrop.playback.StatsTracker
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * P5-STATS-19A - Room-backed evidence for the statistics write path. TEST-ONLY.
 *
 * `CORRECT` tests pin behaviour that is already safe and must stay safe. `DEFECT` / `CHARACTERISATION` tests pin current behaviour
 * that makes a naive retry unsafe (see StatsWriteFailureTest for the naming convention).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StatsWriteFailureRoomTest {

    private lateinit var db: WavdropDatabase
    private lateinit var realEvents: TrackListenEventDao

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), WavdropDatabase::class.java)
            .allowMainThreadQueries().build()
        realEvents = db.trackListenEventDao()
    }

    @After fun tearDown() = db.close()

    private fun repo(events: TrackListenEventDao = realEvents) =
        StatsRepository(db, db.trackStatsDao(), db.importBaselineDao(), events)

    private fun count(table: String): Int =
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); it.getInt(0) }

    private fun eventRows(): List<Triple<String, String?, Long>> =
        db.openHelper.readableDatabase.query("SELECT eventType, eventId, listenedMs FROM track_listen_events ORDER BY id").use { c ->
            buildList { while (c.moveToNext()) add(Triple(c.getString(0), c.getString(1), c.getLong(2))) }
        }

    /** A real event DAO whose `insert` is replaced; everything else delegates. */
    private fun eventsWithInsert(onInsert: suspend (TrackListenEventEntity) -> Unit): TrackListenEventDao =
        object : TrackListenEventDao by realEvents {
            override suspend fun insert(event: TrackListenEventEntity) = onInsert(event)
        }

    private fun assertNothingPersisted() {
        assertEquals("no aggregate row", 0, count("track_stats"))
        assertEquals("no event row", 0, count("track_listen_events"))
    }

    // ── 1 / 10: the transaction fails after the aggregate update, before the event insert ──

    @Test fun `CORRECT - recordPlay failing after the aggregate update rolls the aggregate back`() = runBlocking {
        val failing = eventsWithInsert { throw IOException("disk full") }
        try { repo(failing).recordPlay(1L, "content://media/1", 30_000L, 120_000L); fail("expected IOException") }
        catch (_: IOException) { }
        assertNothingPersisted()           // insertIfAbsent AND incrementPlayCount were both undone
    }

    @Test fun `CORRECT - recordSkip failing after the aggregate update rolls the aggregate back`() = runBlocking {
        val failing = eventsWithInsert { throw IOException("disk full") }
        try { repo(failing).recordSkip(1L, "content://media/1", 120_000L); fail("expected IOException") }
        catch (_: IOException) { }
        assertNothingPersisted()
    }

    @Test fun `CORRECT - a failure leaves earlier committed statistics untouched`() = runBlocking {
        repo().recordPlay(1L, "content://media/1", 30_000L, 120_000L)
        val failing = eventsWithInsert { throw IOException("disk full") }
        try { repo(failing).recordPlay(1L, "content://media/1", 30_000L, 120_000L); fail("expected IOException") }
        catch (_: IOException) { }
        assertEquals(1, db.trackStatsDao().getStatsBySongId(1L)!!.playCount)
        assertEquals(1, count("track_listen_events"))
    }

    // ── 9: cancellation while the write is inside its transaction ─────────────

    @Test fun `CORRECT - cancelling a write that is inside its transaction rolls everything back`() = runBlocking {
        val reached = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Unit>()
        val blocking = eventsWithInsert { reached.complete(Unit); never.await() }   // suspends AFTER the aggregate update
        val job = async(Dispatchers.IO) { repo(blocking).recordPlay(1L, "content://media/1", 30_000L, 120_000L) }
        withTimeout(10_000) { reached.await() }
        withTimeout(10_000) { job.cancelAndJoin() }
        assertNothingPersisted()
    }

    // ── 8: the writer commits but the caller never learns it ──────────────────

    // P5-STATS-19B: corrected. The tracker still cannot tell committed from failed (retry scheduling is out of scope), but a repeat of the
    // SAME occurrence id is now recognised and applied once.
    @Test fun `CORRECT - a committed-but-unacknowledged play is applied once when the same occurrence is repeated`() = runBlocking {
        val real = repo()
        val ackLost = object : PlayEventWriter {
            override suspend fun recordPlay(songId: Long, contentUri: String, listenedMs: Long, durationMs: Long) = Unit
            override suspend fun recordPlayOccurrence(
                occurrence: PlayOccurrence, songId: Long, contentUri: String, listenedMs: Long, durationMs: Long,
            ): StatsWriteResult {
                real.recordPlayOccurrence(occurrence, songId, contentUri, listenedMs, durationMs)   // COMMITS
                throw IOException("acknowledgement lost")                                           // caller sees a failure
            }
            override suspend fun recordSkip(songId: Long, contentUri: String, durationMs: Long) = real.recordSkip(songId, contentUri, durationMs)
            override suspend fun recordListenStart(songId: Long, contentUri: String) = real.recordListenStart(songId, contentUri)
        }
        var clock = 0L
        val tracker = StatsTracker(ackLost).also {
            it.scope = CoroutineScope(Dispatchers.Unconfined); it.clock = { clock }
            it.wallClock = { 5_000L }; it.newEventId = { "occ-1" }
        }
        val song = Song(id = 1L, title = "t", artist = "a", album = "b", albumId = 0L, duration = 120_000L, uri = "content://media/1",
            dateAdded = 0L, trackNumber = 0, year = 2020)
        tracker.onSongSelected(song); tracker.onPlaybackStarted(); clock += 30_000L; tracker.onPlaybackPaused()

        assertEquals("the row IS committed although the tracker saw a failure", 1, db.trackStatsDao().getStatsBySongId(1L)!!.playCount)

        // A repeat of the SAME occurrence (same id and captured time) is recognised:
        val repeat = real.recordPlayOccurrence(PlayOccurrence("occ-1", 5_000L), 1L, "content://media/1", 30_000L, 120_000L)
        assertEquals(StatsWriteResult.ALREADY_APPLIED, repeat)
        assertEquals("counted once", 1, db.trackStatsDao().getStatsBySongId(1L)!!.playCount)
        assertEquals(listOf(Triple("PLAY", "occ-1", 30_000L)), eventRows())
    }

    // ── Idempotency support in the schema ─────────────────────────────────────

    @Test fun `CHARACTERISATION - the database has no uniqueness on eventId, so it cannot reject a duplicate occurrence`() = runBlocking {
        fun e() = TrackListenEventEntity(songId = 1L, eventType = "PLAY", occurredAt = 1_000L, listenedMs = 30_000L, durationMs = 120_000L,
            source = TrackListenEventEntity.SOURCE_WAVDROP_PLAYBACK, eventId = "same-id")
        realEvents.insert(e()); realEvents.insert(e())
        assertEquals(2, count("track_listen_events"))
        val unique = db.openHelper.readableDatabase.query("PRAGMA index_list('track_listen_events')").use { c ->
            buildList { while (c.moveToNext()) if (c.getInt(c.getColumnIndexOrThrow("unique")) == 1) add(c.getString(c.getColumnIndexOrThrow("name"))) }
        }
        assertTrue("only the rowid primary key is unique: $unique", unique.isEmpty())
    }

    // ── Ordering: aggregate timestamps are monotonic (P5-STATS-19B) ───────────

    @Test fun `CORRECT - an older write applied later no longer moves lastPlayedAt or lastListenedAt backwards`() = runBlocking {
        val dao = db.trackStatsDao()
        dao.insertIfAbsent(TrackStatsEntity(songId = 1L, contentUri = "content://media/1"))
        dao.incrementPlayCount(1L, nowMs = 2_000L, listenedMs = 30_000L)    // the later play commits first
        dao.incrementPlayCount(1L, nowMs = 1_000L, listenedMs = 30_000L)    // the earlier play commits second
        dao.updateLastListenedAt(1L, 2_000L)
        dao.updateLastListenedAt(1L, 1_000L)
        val s = dao.getStatsBySongId(1L)!!
        assertEquals(2, s.playCount)
        assertEquals("lastPlayedAt keeps the later time", 2_000L, s.lastPlayedAt)
        assertEquals("lastListenedAt keeps the later time", 2_000L, s.lastListenedAt)
    }
}
