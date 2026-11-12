package com.launchpoint.wavdrop.playback

import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.repository.PlayEventWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P5-STATS-19A - failure characterisation of [StatsTracker] persistence. TEST-ONLY: nothing here changes production behaviour.
 *
 * Naming convention:
 *  - `DEFECT`          - the assertion pins CURRENT behaviour that loses a statistic. When retry safety is implemented the test is
 *                        expected to FAIL and must be rewritten to assert the new guarantee (the desired behaviour is in each comment).
 *  - `CHARACTERISATION` - the assertion pins current behaviour that is a hazard only in combination with a retry design.
 *
 * The scope is [Dispatchers.Unconfined], so every `launch` runs inline until the writer suspends. This is JVM-only evidence about
 * the tracker's in-memory logic. It says nothing about what happens when the OS kills the process.
 */
class StatsWriteFailureTest {

    private var clockMs = 0L

    private fun song(id: Long) = Song(
        id = id, title = "Song $id", artist = "A", album = "B", albumId = 0L, duration = 120_000L,
        uri = "content://media/$id", dateAdded = 0L, trackNumber = 0, year = 2020,
    )

    private fun tracker(writer: PlayEventWriter, scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined)) =
        StatsTracker(writer).also { it.scope = scope; it.clock = { clockMs } }

    private fun StatsTracker.playFor(ms: Long) {
        onPlaybackStarted(); clockMs += ms; onPlaybackPaused()
    }

    /** Records every attempt; the Nth attempt of a kind throws when listed in the matching `fail*` set (1-based). */
    private class ScriptedWriter(
        val failPlays: Set<Int> = emptySet(),
        val failSkips: Set<Int> = emptySet(),
        val failListens: Set<Int> = emptySet(),
    ) : PlayEventWriter {
        val playAttempts = mutableListOf<Long>()
        val skipAttempts = mutableListOf<Long>()
        val listenAttempts = mutableListOf<Long>()
        val committedPlays = mutableListOf<Long>()
        val committedSkips = mutableListOf<Long>()
        val committedListens = mutableListOf<Long>()

        override suspend fun recordPlay(songId: Long, contentUri: String, listenedMs: Long, durationMs: Long) {
            playAttempts += songId
            if (playAttempts.size in failPlays) throw RuntimeException("injected play failure")
            committedPlays += songId
        }

        override suspend fun recordSkip(songId: Long, contentUri: String, durationMs: Long) {
            skipAttempts += songId
            if (skipAttempts.size in failSkips) throw RuntimeException("injected skip failure")
            committedSkips += songId
        }

        override suspend fun recordListenStart(songId: Long, contentUri: String) {
            listenAttempts += songId
            if (listenAttempts.size in failListens) throw RuntimeException("injected listen-start failure")
            committedListens += songId
        }
    }

    // ── 1 / 4: recordPlay fails, the same song keeps playing ──────────────────

    @Test fun `DEFECT - a failed play is never retried while the same song keeps playing`() {
        val w = ScriptedWriter(failPlays = setOf(1))
        val t = tracker(w)
        t.onSongSelected(song(1))
        t.playFor(30_000L)          // threshold crossed -> write attempt 1 throws
        t.playFor(30_000L)          // 30 more seconds of the SAME song
        t.playFor(30_000L)

        // Desired after the fix: the play is retried (or queued) so exactly one PLAY is eventually committed.
        assertEquals("exactly one attempt, ever", listOf(1L), w.playAttempts)
        assertTrue("the play is lost", w.committedPlays.isEmpty())
    }

    // ── 3: recordListenStart fails ────────────────────────────────────────────

    @Test fun `DEFECT - a failed listen start is never retried for the same selection`() {
        val w = ScriptedWriter(failListens = setOf(1))
        val t = tracker(w)
        t.onSongSelected(song(1))
        t.playFor(5_000L)           // listen threshold crossed -> attempt 1 throws, flag already set
        t.playFor(5_000L)
        t.onSongSelected(song(2))   // finalise song 1

        // Desired after the fix: lastListenedAt is eventually written, so the song reaches Recently Played.
        assertEquals(listOf(1L), w.listenAttempts)
        assertTrue(w.committedListens.isEmpty())
    }

    // ── 2: recordSkip fails ───────────────────────────────────────────────────

    @Test fun `DEFECT - a failed skip is never retried`() {
        val w = ScriptedWriter(failSkips = setOf(1))
        val t = tracker(w)
        t.onSongSelected(song(1))
        t.playFor(2_000L)
        t.onSongSelected(song(2))   // skip of song 1 -> throws
        t.playFor(2_000L)
        t.onSongSelected(song(3))   // skip of song 2 -> succeeds

        assertEquals(listOf(1L, 2L), w.skipAttempts)
        assertEquals("song 1's skip is gone for good", listOf(2L), w.committedSkips)
    }

    // ── 5: switch tracks right after a failure ────────────────────────────────

    @Test fun `DEFECT - a failed play followed by an immediate switch is lost and is not downgraded to a skip`() {
        val w = ScriptedWriter(failPlays = setOf(1))
        val t = tracker(w)
        t.onSongSelected(song(1))
        t.playFor(30_000L)          // play of song 1 fails
        t.onSongSelected(song(2))   // user switches immediately
        t.playFor(30_000L)          // song 2 is recorded normally

        assertEquals(listOf(1L, 2L), w.playAttempts)
        assertEquals(listOf(2L), w.committedPlays)
        assertTrue("the guard suppressed the skip, so song 1 leaves NO trace at all", w.skipAttempts.isEmpty())
    }

    // ── 6: repeat-one creates another session after a failure ─────────────────

    @Test fun `DEFECT - repeat-one - the failed loop is lost while the next loop is recorded`() {
        val w = ScriptedWriter(failPlays = setOf(1))
        val t = tracker(w)
        t.onSongSelected(song(1))
        t.playFor(30_000L)          // loop 1: fails
        t.onSongSelected(song(1))   // loop boundary (same song) resets the guards
        t.playFor(30_000L)          // loop 2: succeeds

        assertEquals(2, w.playAttempts.size)
        assertEquals("two listens happened, one was persisted", 1, w.committedPlays.size)
    }

    // ── 7: a second event while an earlier write is in flight ─────────────────

    @Test fun `CHARACTERISATION - writes are not serialised, so two events overlap and can complete out of playback order`() = runBlocking {
        val gates = HashMap<Long, CompletableDeferred<Unit>>()
        var inFlight = 0
        var maxInFlight = 0
        val completionOrder = mutableListOf<Long>()
        val writer = object : PlayEventWriter {
            override suspend fun recordPlay(songId: Long, contentUri: String, listenedMs: Long, durationMs: Long) {
                val gate = CompletableDeferred<Unit>().also { gates[songId] = it }
                inFlight++; maxInFlight = maxOf(maxInFlight, inFlight)
                gate.await()
                inFlight--; completionOrder += songId
            }
            override suspend fun recordSkip(songId: Long, contentUri: String, durationMs: Long) {}
            override suspend fun recordListenStart(songId: Long, contentUri: String) {}
        }
        val t = tracker(writer)
        t.onSongSelected(song(1)); t.playFor(30_000L)   // write 1 starts and suspends (e.g. waiting on SQLite)
        t.onSongSelected(song(2)); t.playFor(30_000L)   // write 2 starts while write 1 is still in flight

        gates.getValue(2L).complete(Unit)               // the later play finishes first
        gates.getValue(1L).complete(Unit)

        assertEquals("no queue: both writes were in flight at once", 2, maxInFlight)
        assertEquals("completion order is inverted relative to playback order", listOf(2L, 1L), completionOrder)
    }

    // ── 9: cancellation while a write is pending ──────────────────────────────

    @Test fun `DEFECT - cancelling the tracker scope loses the pending write and silently disables every later write`() = runBlocking {
        var cancellationSeenByWriter = false
        val gate = CompletableDeferred<Unit>()
        val committed = mutableListOf<Long>()
        val attempts = mutableListOf<Long>()
        val writer = object : PlayEventWriter {
            override suspend fun recordPlay(songId: Long, contentUri: String, listenedMs: Long, durationMs: Long) {
                attempts += songId
                try { gate.await() } catch (e: CancellationException) { cancellationSeenByWriter = true; throw e }
                committed += songId
            }
            override suspend fun recordSkip(songId: Long, contentUri: String, durationMs: Long) {}
            override suspend fun recordListenStart(songId: Long, contentUri: String) {}
        }
        val job = SupervisorJob()
        val t = tracker(writer, CoroutineScope(job + Dispatchers.Unconfined))
        t.onSongSelected(song(1)); t.playFor(30_000L)    // write pending
        job.cancel()                                      // no production code does this: StatsTracker has no shutdown API
        t.onSongSelected(song(2)); t.playFor(30_000L)    // would have been recorded

        assertTrue("the pending write was interrupted", cancellationSeenByWriter)
        assertTrue("nothing committed", committed.isEmpty())
        assertEquals("the second play never even reached the writer", listOf(1L), attempts)
    }
}
