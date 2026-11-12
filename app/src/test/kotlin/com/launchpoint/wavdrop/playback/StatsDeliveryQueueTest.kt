package com.launchpoint.wavdrop.playback

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteDatabaseLockedException
import android.database.sqlite.SQLiteDiskIOException
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import com.launchpoint.wavdrop.data.repository.PlayEventWriter
import com.launchpoint.wavdrop.data.repository.PlayOccurrence
import com.launchpoint.wavdrop.data.repository.StatsEventConflictException
import com.launchpoint.wavdrop.data.repository.StatsWriteResult
import java.io.IOException
import java.lang.reflect.Modifier
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P5-STATS-19C - the delivery queue on its own: failure classification, bounded capacity and its explicit full-queue policy, and the
 * revised [PlayEventWriter] contract. Deterministic: [Dispatchers.Unconfined] scope, gates instead of sleeps.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StatsDeliveryQueueTest {

    // ── failure classification ────────────────────────────────────────────────

    private fun kind(t: Throwable) = StatsFailureClassifier.classify(t)

    @Test fun `busy and locked contention is BUSY`() {
        assertEquals(StatsFailureKind.BUSY, kind(SQLiteDatabaseLockedException("database is locked")))
        assertEquals(StatsFailureKind.BUSY, kind(SQLiteException("database is locked (code 5 SQLITE_BUSY)")))
    }

    @Test fun `disk I-O and storage-full and IOException are TRANSIENT_IO`() {
        assertEquals(StatsFailureKind.TRANSIENT_IO, kind(SQLiteDiskIOException("disk I/O error")))
        assertEquals(StatsFailureKind.TRANSIENT_IO, kind(SQLiteFullException("database or disk is full")))
        assertEquals(StatsFailureKind.TRANSIENT_IO, kind(IOException("io")))
    }

    @Test fun `constraint corruption and bad arguments are PERMANENT`() {
        assertEquals(StatsFailureKind.PERMANENT, kind(SQLiteConstraintException("UNIQUE")))
        assertEquals(StatsFailureKind.PERMANENT, kind(SQLiteDatabaseCorruptException("malformed")))
        assertEquals(StatsFailureKind.PERMANENT, kind(IllegalArgumentException("bad")))
    }

    @Test fun `an integrity conflict is CONFLICT, never retried`() {
        assertEquals(StatsFailureKind.CONFLICT, kind(StatsEventConflictException("differs")))
    }

    @Test fun `anything unrecognised is UNKNOWN`() {
        assertEquals(StatsFailureKind.UNKNOWN, kind(RuntimeException("?")))
        assertEquals(StatsFailureKind.UNKNOWN, kind(SQLiteException("something odd")))
        assertEquals(StatsFailureKind.UNKNOWN, kind(IllegalStateException("pool closed")))
    }

    // ── bounded capacity and the full-queue policy ────────────────────────────

    private class Gated : PlayEventWriter {
        val gate = CompletableDeferred<Unit>()
        val committed = mutableListOf<String>()
        override suspend fun recordPlayOccurrence(occurrence: PlayOccurrence, songId: Long, contentUri: String, listenedMs: Long, durationMs: Long): StatsWriteResult {
            gate.await(); committed += occurrence.eventId; return StatsWriteResult.APPLIED
        }
        override suspend fun recordSkipOccurrence(occurrence: PlayOccurrence, songId: Long, contentUri: String, durationMs: Long) = StatsWriteResult.APPLIED
        override suspend fun recordListenStartAt(occurredAtMs: Long, songId: Long, contentUri: String) = Unit
    }

    private fun play(id: String) = StatsTask.Play(PlayOccurrence(id, 1_000L), 1L, "content://media/1", 30_000L, 120_000L)

    private val job = Job()
    private val scope: CoroutineScope = CoroutineScope(job + Dispatchers.Unconfined)

    @Test fun `capacity - one in flight plus the queue bound are accepted, the rest are rejected visibly and the accepted ones all arrive in order`() = runBlocking {
        val w = Gated()
        val outcomes = mutableListOf<StatsDeliveryOutcome>()
        val q = StatsDeliveryQueue({ scope }, w, StatsDeliveryPolicy(queueCapacity = 2)) { outcomes += it }

        val accepted = (1..6).map { q.submit(play("p$it")) }      // p1 is taken by the consumer (and blocks); p2,p3 fill the queue

        assertEquals(listOf(true, true, true, false, false, false), accepted)
        assertEquals(3, q.count(StatsDeliveryState.REJECTED))
        assertTrue(outcomes.filter { it.state == StatsDeliveryState.REJECTED }.all { it.detail == "queue-full" })
        assertEquals(listOf("PLAY:p4", "PLAY:p5", "PLAY:p6"), outcomes.filter { it.state == StatsDeliveryState.REJECTED }.map { it.key })

        w.gate.complete(Unit)
        assertEquals("nothing accepted is lost, order is preserved", listOf("p1", "p2", "p3"), w.committed)
        assertEquals(3, q.count(StatsDeliveryState.APPLIED))
    }

    @Test fun `memory stays bounded - a long outage parks at most parkedCapacity tasks and evicts the oldest visibly`() = runBlocking {
        val failing = object : PlayEventWriter {
            override suspend fun recordPlayOccurrence(occurrence: PlayOccurrence, songId: Long, contentUri: String, listenedMs: Long, durationMs: Long): StatsWriteResult = throw IOException("down")
            override suspend fun recordSkipOccurrence(occurrence: PlayOccurrence, songId: Long, contentUri: String, durationMs: Long) = StatsWriteResult.APPLIED
            override suspend fun recordListenStartAt(occurredAtMs: Long, songId: Long, contentUri: String) = Unit
        }
        val outcomes = mutableListOf<StatsDeliveryOutcome>()
        val q = StatsDeliveryQueue({ scope }, failing, StatsDeliveryPolicy(parkedCapacity = 2, retryDelaysMs = emptyList()), { }) { outcomes += it }

        (1..5).forEach { q.submit(play("p$it")) }

        assertEquals("never more than the bound is retained", 2, q.parkedCount)
        val evicted = outcomes.filter { it.state == StatsDeliveryState.ABANDONED }
        assertEquals(listOf("PLAY:p1", "PLAY:p2", "PLAY:p3"), evicted.map { it.key })
        assertTrue(evicted.all { it.detail == "parked-evicted" })
    }

    @Test fun `a listener that throws cannot break delivery`() = runBlocking {
        val w = Gated().also { it.gate.complete(Unit) }
        val q = StatsDeliveryQueue({ scope }, w) { throw IllegalStateException("bad listener") }
        q.submit(play("p1")); q.submit(play("p2"))
        assertEquals(listOf("p1", "p2"), w.committed)
    }

    // ── the interface contract ────────────────────────────────────────────────

    @Test fun `PlayEventWriter has no default implementations, so no writer can inherit a non-idempotent fallback`() {
        val methods = PlayEventWriter::class.java.declaredMethods
        assertTrue(methods.isNotEmpty())
        assertTrue("every method is abstract: ${methods.map { it.name to Modifier.isAbstract(it.modifiers) }}", methods.all { Modifier.isAbstract(it.modifiers) })
        val names = methods.map { it.name }.toSet()
        assertEquals(setOf("recordPlayOccurrence", "recordSkipOccurrence", "recordListenStartAt"), names)
        assertFalse("no Kotlin DefaultImpls holder", runCatching { Class.forName("com.launchpoint.wavdrop.data.repository.PlayEventWriter\$DefaultImpls") }.isSuccess)
    }

    // ── P5-STATS-19C1: accounting on cancellation ─────────────────────────────

    private val outs = java.util.concurrent.CopyOnWriteArrayList<StatsDeliveryOutcome>()
    private val terminal = setOf(StatsDeliveryState.APPLIED, StatsDeliveryState.ALREADY_APPLIED, StatsDeliveryState.FAILED, StatsDeliveryState.ABANDONED)

    private fun terminals(id: String) = outs.filter { it.key == "PLAY:$id" && it.state in terminal }
    private fun assertOneTerminal(id: String, state: StatsDeliveryState, detail: String? = null) {
        val t = terminals(id)
        assertEquals("exactly one terminal outcome for $id: $t", 1, t.size)
        assertEquals("state of $id", state, t[0].state)
        if (detail != null) assertEquals("detail of $id", detail, t[0].detail)
    }

    /** A writer whose behaviour is chosen per event id. */
    private class PerId(val behave: suspend (String) -> Unit) : PlayEventWriter {
        override suspend fun recordPlayOccurrence(occurrence: PlayOccurrence, songId: Long, contentUri: String, listenedMs: Long, durationMs: Long): StatsWriteResult {
            behave(occurrence.eventId); return StatsWriteResult.APPLIED
        }
        override suspend fun recordSkipOccurrence(occurrence: PlayOccurrence, songId: Long, contentUri: String, durationMs: Long) = StatsWriteResult.APPLIED
        override suspend fun recordListenStartAt(occurredAtMs: Long, songId: Long, contentUri: String) = Unit
    }

    private fun queue(w: PlayEventWriter, policy: StatsDeliveryPolicy = StatsDeliveryPolicy(retryDelaysMs = emptyList())) =
        StatsDeliveryQueue({ scope }, w, policy, { }) { outs += it }

    @Test fun `parent cancellation reports the in-flight task and every buffered task exactly once`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val q = queue(PerId { gate.await() })
        (1..4).forEach { assertTrue(q.submit(play("p$it"))) }       // p1 in flight (blocked), p2..p4 buffered

        job.cancel()

        assertOneTerminal("p1", StatsDeliveryState.ABANDONED, "cancelled-in-flight")
        listOf("p2", "p3", "p4").forEach { assertOneTerminal(it, StatsDeliveryState.ABANDONED, "scope-cancelled-buffered") }
        assertEquals(4, q.count(StatsDeliveryState.ABANDONED))
        assertEquals("nothing was delivered", 0, q.count(StatsDeliveryState.APPLIED))
    }

    @Test fun `parent cancellation reports parked tasks exactly once`() = runBlocking {
        val q = queue(PerId { throw IOException("down") })
        q.submit(play("p1")); q.submit(play("p2"))
        assertEquals(2, q.parkedCount)

        job.cancel()

        listOf("p1", "p2").forEach { assertOneTerminal(it, StatsDeliveryState.ABANDONED, "scope-cancelled-parked") }
        assertEquals("parked memory is released", 0, q.parkedCount)
    }

    @Test fun `a mixed queue - applied, parked, in-flight and buffered - gets exactly one terminal outcome each`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val q = queue(PerId { id ->
            when (id) { "bad" -> throw IOException("down"); "stuck" -> gate.await(); else -> Unit }
        })
        q.submit(play("ok")); q.submit(play("bad")); q.submit(play("ok2"))   // ok2's success redelivers "bad" once more (still failing)
        q.submit(play("stuck")); q.submit(play("b1")); q.submit(play("b2"))

        job.cancel()

        assertOneTerminal("ok", StatsDeliveryState.APPLIED)
        assertOneTerminal("ok2", StatsDeliveryState.APPLIED)
        assertOneTerminal("bad", StatsDeliveryState.ABANDONED, "scope-cancelled-parked")
        assertOneTerminal("stuck", StatsDeliveryState.ABANDONED, "cancelled-in-flight")
        assertOneTerminal("b1", StatsDeliveryState.ABANDONED, "scope-cancelled-buffered")
        assertOneTerminal("b2", StatsDeliveryState.ABANDONED, "scope-cancelled-buffered")
        assertEquals(6, outs.filter { it.state in terminal }.size)
    }

    @Test fun `no duplicate reports when a worker was cancelled first and the parent scope ends afterwards`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val q = queue(PerId { gate.await() })
        (1..3).forEach { q.submit(play("p$it")) }                    // p1 in flight, p2 and p3 buffered

        job.children.forEach { it.cancel() }                          // worker only: scope still active
        assertOneTerminal("p1", StatsDeliveryState.ABANDONED, "cancelled-in-flight")
        assertEquals("buffered tasks are retained, not reported", 0, terminals("p2").size + terminals("p3").size)

        job.cancel()                                                  // now the parent ends; no consumer is alive to do the cleanup
        q.submit(play("late"))                                        // and repeated cleanup attempts must stay idempotent

        assertOneTerminal("p1", StatsDeliveryState.ABANDONED, "cancelled-in-flight")
        assertOneTerminal("p2", StatsDeliveryState.ABANDONED, "scope-cancelled-buffered")
        assertOneTerminal("p3", StatsDeliveryState.ABANDONED, "scope-cancelled-buffered")
        assertEquals(3, q.count(StatsDeliveryState.ABANDONED))
    }

    @Test fun `a worker cancelled alone restarts under the live scope and the retained tasks are delivered`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val done = mutableListOf<String>()
        val q = queue(PerId { gate.await(); done += it })
        q.submit(play("p1")); q.submit(play("p2"))                    // p1 in flight, p2 buffered

        job.children.forEach { it.cancel() }
        gate.complete(Unit)                                           // the database is fine again
        assertTrue(q.submit(play("p3")))                              // restarts the consumer

        assertEquals(listOf("p2", "p3"), done)
        assertOneTerminal("p1", StatsDeliveryState.ABANDONED, "cancelled-in-flight")
        assertOneTerminal("p2", StatsDeliveryState.APPLIED)
        assertOneTerminal("p3", StatsDeliveryState.APPLIED)
        assertTrue("no scope-level cleanup ran", outs.none { it.detail?.startsWith("scope-cancelled") == true })
    }

    @Test fun `submissions after the parent scope ended are rejected, not accepted or silently dropped`() = runBlocking {
        val q = queue(PerId { })
        job.cancel()

        assertFalse(q.submit(play("late")))

        assertEquals(1, q.count(StatsDeliveryState.REJECTED))
        assertEquals("scope-cancelled", outs.single { it.state == StatsDeliveryState.REJECTED }.detail)
        assertEquals("a rejected task never gets a terminal accepted-task outcome", 0, terminals("late").size)
    }

    // ── P5-STATS-19C2: no non-terminal report after a terminal one ────────────

    /** For every task: once a terminal outcome was reported, no RETRYING / UNRESOLVED may follow. */
    private fun assertNoProgressAfterTerminal() {
        val finished = HashSet<String>()
        for (o in outs) {
            if (o.state in terminal) finished += o.key
            else if (o.state == StatsDeliveryState.RETRYING || o.state == StatsDeliveryState.UNRESOLVED)
                assertFalse("${o.state} reported for ${o.key} AFTER its terminal outcome: $outs", o.key in finished)
        }
    }

    @Test fun `termination between parking and the UNRESOLVED report cannot publish UNRESOLVED after ABANDONED`() = runBlocking {
        lateinit var q: StatsDeliveryQueue
        var fired = false
        q = StatsDeliveryQueue({ scope }, PerId { throw IOException("down") }, StatsDeliveryPolicy(retryDelaysMs = emptyList()), { }, beforeProgressReport = { state ->
            if (state == StatsDeliveryState.UNRESOLVED && !fired) {
                fired = true
                job.cancel(); q.submit(play("probe"))          // the parent ends exactly now: terminate() runs on the parked task
            }
        }) { outs += it }

        q.submit(play("p1"))

        assertTrue("the window was hit", fired)
        assertOneTerminal("p1", StatsDeliveryState.ABANDONED, "scope-cancelled-parked")
        assertTrue("UNRESOLVED was suppressed, not published after the terminal outcome", outs.none { it.key == "PLAY:p1" && it.state == StatsDeliveryState.UNRESOLVED })
        assertNoProgressAfterTerminal()
    }

    @Test fun `termination during a parked redelivery cannot publish UNRESOLVED after ABANDONED`() = runBlocking {
        lateinit var q: StatsDeliveryQueue
        var unresolved = 0
        q = StatsDeliveryQueue({ scope }, PerId { if (it == "p1") throw IOException("down") }, StatsDeliveryPolicy(retryDelaysMs = emptyList()), { }, beforeProgressReport = { state ->
            if (state == StatsDeliveryState.UNRESOLVED && ++unresolved == 2) { job.cancel(); q.submit(play("probe")) }
        }) { outs += it }
        q.submit(play("p1"))                                // parked (UNRESOLVED #1)
        q.submit(play("ok2"))                               // succeeds, so p1 is redelivered, fails again (UNRESOLVED #2 -> termination)

        assertEquals(2, unresolved)
        assertOneTerminal("p1", StatsDeliveryState.ABANDONED, "scope-cancelled-parked")
        assertOneTerminal("ok2", StatsDeliveryState.APPLIED)
        assertEquals(0, q.parkedCount)
        assertNoProgressAfterTerminal()
    }

    @Test fun `termination between a failed attempt and the RETRYING report cannot publish RETRYING after the terminal outcome`() = runBlocking {
        lateinit var q: StatsDeliveryQueue
        var calls = 0
        q = StatsDeliveryQueue({ scope }, PerId { if (++calls == 1) throw IOException("hiccup") }, StatsDeliveryPolicy(retryDelaysMs = listOf(250L)),
            { CompletableDeferred<Unit>().await() }, beforeProgressReport = { state ->
            if (state == StatsDeliveryState.RETRYING) { job.cancel(); q.submit(play("probe")) }
        }) { outs += it }

        q.submit(play("p1"))

        assertOneTerminal("p1", StatsDeliveryState.ABANDONED, "cancelled-in-flight")
        assertEquals("RETRYING came first, then the terminal outcome", listOf(StatsDeliveryState.RETRYING, StatsDeliveryState.ABANDONED), outs.filter { it.key == "PLAY:p1" }.map { it.state })
        assertNoProgressAfterTerminal()
    }

    @Test fun `normal delivery is unchanged - every task applied once, in order, nothing abandoned`() = runBlocking {
        val seen = mutableListOf<String>()
        val q = queue(PerId { seen += it })

        (1..5).forEach { assertTrue(q.submit(play("p$it"))) }

        assertEquals(listOf("p1", "p2", "p3", "p4", "p5"), seen)
        (1..5).forEach { assertOneTerminal("p$it", StatsDeliveryState.APPLIED) }
        assertEquals(0, q.count(StatsDeliveryState.ABANDONED) + q.count(StatsDeliveryState.REJECTED) + q.count(StatsDeliveryState.FAILED))
    }
}
