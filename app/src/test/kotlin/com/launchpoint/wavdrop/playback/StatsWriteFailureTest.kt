package com.launchpoint.wavdrop.playback

import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.repository.PlayEventWriter
import com.launchpoint.wavdrop.data.repository.PlayOccurrence
import com.launchpoint.wavdrop.data.repository.StatsEventConflictException
import com.launchpoint.wavdrop.data.repository.StatsWriteResult
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P5-STATS-19C - tracker-level behaviour of the statistics write path. Supersedes the 19A `DEFECT` / `CHARACTERISATION` tests in this
 * file, whose defects (no retry, no serialization, guard flags suppressing a skip after a failed play, silent cancellation) are now
 * either fixed or covered by an explicit delivery state.
 *
 * Deterministic: the scope is [Dispatchers.Unconfined] and the retry delay is injected ([StatsTracker.retryDelayer]) so no test sleeps
 * or depends on real time. The delayer also advances the wall clock, which proves retries reuse the ORIGINAL captured timestamp.
 * Idempotency of the real writer is proven separately against Room (StatsIdempotencyRoomTest / StatsDeliveryRoomTest).
 */
class StatsWriteFailureTest {

    private var clockMs = 0L
    private var wallMs = 1_000_000L
    private var idSeq = 0
    private val delays = mutableListOf<Long>()
    private val outcomes = mutableListOf<StatsDeliveryOutcome>()

    private fun song(id: Long) = Song(
        id = id, title = "Song $id", artist = "A", album = "B", albumId = 0L, duration = 120_000L,
        uri = "content://media/$id", dateAdded = 0L, trackNumber = 0, year = 2020,
    )

    private fun tracker(
        writer: PlayEventWriter,
        job: Job = Job(),
        policy: StatsDeliveryPolicy = StatsDeliveryPolicy(),
        delayer: suspend (Long) -> Unit = { delays += it; wallMs += it },
    ) = StatsTracker(writer).also {
        it.scope = CoroutineScope(job + Dispatchers.Unconfined)
        it.clock = { clockMs }
        it.wallClock = { wallMs }
        it.newEventId = { "e${++idSeq}" }
        it.deliveryPolicy = policy
        it.retryDelayer = delayer
        it.deliveryListener = { o -> outcomes += o }
    }

    private fun StatsTracker.playFor(ms: Long) { onPlaybackStarted(); clockMs += ms; wallMs += ms; onPlaybackPaused() }

    /** Records every attempt; [failure] decides whether attempt `n` (1-based, per key) of a task throws. Idempotent like the real writer. */
    private class Script(var failure: (key: String, attempt: Int) -> Throwable? = { _, _ -> null }) : PlayEventWriter {
        val attempts = mutableListOf<String>()
        val committed = mutableListOf<String>()
        val lastOccurrence = HashMap<String, PlayOccurrence>()
        val listenTimes = mutableListOf<Long>()
        var gate: CompletableDeferred<Unit>? = null
        private val perKey = HashMap<String, Int>()

        private suspend fun run(key: String): StatsWriteResult {
            attempts += key
            gate?.await()
            val n = (perKey[key] ?: 0) + 1
            perKey[key] = n
            failure(key, n)?.let { throw it }
            if (key in committed) return StatsWriteResult.ALREADY_APPLIED
            committed += key
            return StatsWriteResult.APPLIED
        }

        override suspend fun recordPlayOccurrence(occurrence: PlayOccurrence, songId: Long, contentUri: String, listenedMs: Long, durationMs: Long): StatsWriteResult {
            lastOccurrence["PLAY:${occurrence.eventId}"] = occurrence
            return run("PLAY:${occurrence.eventId}")
        }

        override suspend fun recordSkipOccurrence(occurrence: PlayOccurrence, songId: Long, contentUri: String, durationMs: Long): StatsWriteResult {
            lastOccurrence["SKIP:${occurrence.eventId}"] = occurrence
            return run("SKIP:${occurrence.eventId}")
        }

        override suspend fun recordListenStartAt(occurredAtMs: Long, songId: Long, contentUri: String) {
            val key = "LISTEN:$songId@$occurredAtMs"
            run(key)
            listenTimes += occurredAtMs
        }
    }

    private fun count(t: StatsTracker, s: StatsDeliveryState) = t.deliveryCount(s)

    // ── 1: PLAY fails transiently, then succeeds ──────────────────────────────

    @Test fun `a transient PLAY failure is retried with the same id and then applied once`() {
        val w = Script { k, n -> if (k == "PLAY:e1" && n == 1) IOException("disk hiccup") else null }
        val t = tracker(w)
        t.onSongSelected(song(1))
        val detectedAt = wallMs + 30_000L
        t.playFor(30_000L)

        assertEquals(listOf("PLAY:e1", "PLAY:e1"), w.attempts.filter { it.startsWith("PLAY") })
        assertEquals(listOf("PLAY:e1"), w.committed.filter { it.startsWith("PLAY") })
        assertEquals(listOf(250L), delays)
        assertEquals(1, count(t, StatsDeliveryState.RETRYING))
        assertEquals("the retried attempt carries the ORIGINAL detection time, not the time the retry ran", detectedAt, w.lastOccurrence.getValue("PLAY:e1").occurredAtMs)
    }

    // ── 2: SKIP fails transiently, then succeeds ──────────────────────────────

    @Test fun `a transient SKIP failure is retried with the same id and then applied once`() {
        val w = Script { k, n -> if (k == "SKIP:e1" && n == 1) IOException("disk hiccup") else null }
        val t = tracker(w)
        t.onSongSelected(song(1)); t.playFor(2_000L)
        t.onSongSelected(song(2))                       // skip of song 1

        assertEquals(listOf("SKIP:e1", "SKIP:e1"), w.attempts)
        assertEquals(listOf("SKIP:e1"), w.committed)
        assertEquals(1, count(t, StatsDeliveryState.RETRYING))
        assertEquals(1, count(t, StatsDeliveryState.APPLIED))
    }

    // ── 3 / 11: listen start fails transiently; the retry keeps the original timestamp ──

    @Test fun `a transient listen-start failure is retried with the original timestamp`() {
        val w = Script { k, n -> if (k.startsWith("LISTEN:") && n == 1) IOException("disk hiccup") else null }
        val t = tracker(w)
        t.onSongSelected(song(1))
        val detectedAt = wallMs + 5_000L
        t.playFor(5_000L)

        assertEquals(listOf("LISTEN:1@$detectedAt", "LISTEN:1@$detectedAt"), w.attempts)
        assertEquals("retried at a later wall-clock time but wrote the original one", listOf(detectedAt), w.listenTimes)
        assertTrue("the delayer advanced the clock past the detection time", wallMs > detectedAt)
    }

    // ── 5: non-retryable failures are attempted exactly once ──────────────────

    @Test fun `non-retryable failures are not retried and do not block later events`() {
        val w = Script { k, _ ->
            when (k) {
                "PLAY:e1" -> StatsEventConflictException("payload differs")
                "SKIP:e2" -> IllegalArgumentException("bad argument")
                else -> null
            }
        }
        val t = tracker(w)
        t.onSongSelected(song(1)); t.playFor(30_000L)   // PLAY e1 -> conflict
        t.onSongSelected(song(2)); t.playFor(2_000L)    // song 2 abandoned early -> SKIP e2
        t.onSongSelected(song(3)); t.playFor(30_000L)   // PLAY e3 -> fine (this select is what skips song 2)

        assertEquals(1, w.attempts.count { it == "PLAY:e1" })
        assertEquals(1, w.attempts.count { it == "SKIP:e2" })
        assertTrue("no backoff was ever scheduled", delays.isEmpty())
        assertEquals(2, count(t, StatsDeliveryState.FAILED))
        assertTrue("a later play is still delivered", "PLAY:e3" in w.committed)
    }

    // ── 6: cancellation ───────────────────────────────────────────────────────

    @Test fun `cancelling the scope during a backoff stops the retries and later events are rejected visibly`() = runBlocking {
        val inBackoff = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Unit>()
        var cancellationSeen = false
        val w = Script { _, _ -> IOException("always") }
        val job = Job()
        val t = tracker(w, job, delayer = { inBackoff.complete(Unit); try { never.await() } catch (e: kotlinx.coroutines.CancellationException) { cancellationSeen = true; throw e } })
        t.onSongSelected(song(1)); t.playFor(30_000L)    // attempt 1 fails, consumer parks in the backoff
        inBackoff.await()
        val attemptsBefore = w.attempts.size

        job.cancel()
        t.onSongSelected(song(2)); t.playFor(30_000L)    // detected after shutdown

        assertTrue("the backoff was cancelled, not swallowed", cancellationSeen)
        assertEquals("no attempt after cancellation", attemptsBefore, w.attempts.size)
        assertTrue("the in-flight task is reported as abandoned", count(t, StatsDeliveryState.ABANDONED) >= 1)
        assertTrue("later events are visibly rejected rather than silently dropped", count(t, StatsDeliveryState.REJECTED) >= 1)
    }

    @Test fun `a cancelled worker is restarted by the next event while the app is still active`() {
        val w = Script()
        val job = Job()
        val t = tracker(w, job)
        t.onSongSelected(song(1)); t.playFor(30_000L)
        assertEquals(listOf("PLAY:e1"), w.committed.filter { it.startsWith("PLAY") })

        job.children.forEach { it.cancel() }             // kill ONLY the consumer; the scope stays alive
        t.onSongSelected(song(2)); t.playFor(30_000L)

        assertEquals(listOf("PLAY:e1", "PLAY:e2"), w.committed.filter { it.startsWith("PLAY") })
    }

    // ── 7: one failing event never starves the queue ──────────────────────────

    @Test fun `a task that exhausts its retries is parked, later events still flow, and it is redelivered once the database works`() {
        val w = Script { k, n -> if (k == "PLAY:e1" && n <= 5) IOException("busy") else null }
        val t = tracker(w)
        t.onSongSelected(song(1)); t.playFor(30_000L)
        assertEquals(listOf(250L, 1_000L, 3_000L), delays)
        // 4 bounded in-call attempts, parked, then the success of the queued listen-start triggers redelivery attempt 5 (still failing).
        assertEquals(5, w.attempts.count { it == "PLAY:e1" })
        assertEquals("parked once, then re-parked after the failed redelivery", 2, count(t, StatsDeliveryState.UNRESOLVED))
        assertEquals(1, t.deliveryParkedCount)
        assertTrue("the queue was not blocked by it: the listen start went through", w.committed.any { it.startsWith("LISTEN:1@") })

        t.onSongSelected(song(2)); t.playFor(30_000L)    // delivered immediately; its success triggers redelivery of e1 (attempt 6 works)

        assertEquals(listOf("PLAY:e1", "PLAY:e2"), w.committed.filter { it.startsWith("PLAY") }.sorted())
        assertEquals(0, t.deliveryParkedCount)
        assertEquals(6, w.attempts.count { it == "PLAY:e1" })
    }

    @Test fun `a task that stays unresolved is abandoned after its rounds, visibly, and does not loop forever`() {
        val w = Script { k, _ -> if (k == "PLAY:e1") IOException("busy") else null }
        val t = tracker(w)
        t.onSongSelected(song(1)); t.playFor(30_000L)
        t.onSongSelected(song(2)); t.playFor(30_000L)
        t.onSongSelected(song(3)); t.playFor(30_000L)
        t.onSongSelected(song(4)); t.playFor(30_000L)

        assertTrue("the others were all delivered", listOf("PLAY:e2", "PLAY:e3", "PLAY:e4").all { it in w.committed })
        assertEquals(1, count(t, StatsDeliveryState.ABANDONED))
        assertEquals(0, t.deliveryParkedCount)
        assertEquals("4 in-call attempts + 2 redeliveries (rounds 2 and 3), then no more", 6, w.attempts.count { it == "PLAY:e1" })
    }

    // ── 8 / 9: switching tracks and repeat-one preserve outstanding occurrences ──

    @Test fun `track switches and repeat-one boundaries preserve pending occurrences, in order and distinct`() = runBlocking {
        val w = Script().also { it.gate = CompletableDeferred() }
        val t = tracker(w)
        t.onSongSelected(song(1)); t.playFor(30_000L)    // e1 reaches the writer and waits there
        t.onSongSelected(song(2)); t.playFor(30_000L)    // e2 queued behind it
        t.onSongSelected(song(2)); t.playFor(30_000L)    // repeat-one boundary: e3, same song
        t.onSongSelected(song(3)); t.playFor(2_000L)
        t.onSongSelected(song(4))                        // skip of song 3: e4
        assertTrue("nothing completed yet", w.committed.isEmpty())

        w.gate!!.complete(Unit)

        assertEquals(listOf("PLAY:e1", "PLAY:e2", "PLAY:e3", "SKIP:e4"), w.committed.filter { !it.startsWith("LISTEN") })
        assertEquals("every occurrence is distinct", 4, w.committed.filter { !it.startsWith("LISTEN") }.toSet().size)
    }

    // ── 11: event timestamps are detection times, whatever the delivery order ──

    @Test fun `delivery delays never change the captured detection times`() {
        val w = Script { k, n -> if (k == "PLAY:e1" && n <= 2) IOException("busy") else null }
        val t = tracker(w)
        t.onSongSelected(song(1))
        val t1 = wallMs + 30_000L; t.playFor(30_000L)
        t.onSongSelected(song(2))
        val t2 = wallMs + 30_000L; t.playFor(30_000L)

        assertEquals(t1, w.lastOccurrence.getValue("PLAY:e1").occurredAtMs)
        assertEquals(t2, w.lastOccurrence.getValue("PLAY:e2").occurredAtMs)
        assertTrue("e1 was retried (backoff advanced the clock) yet still keeps the earlier time", t1 < t2 && delays == listOf(250L, 1_000L))
    }
}
