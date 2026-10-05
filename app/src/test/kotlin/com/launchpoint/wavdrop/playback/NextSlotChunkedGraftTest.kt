package com.launchpoint.wavdrop.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * CF-2M4 correction: the graft runs as bounded chunks across looper turns. These tests step a [ManualGraftScheduler] one turn at
 * a time on the scripted NEXT physical to prove ordering, progress, token/scheduler stale-work protection, cancellation and
 * supersession at every phase, failure containment and teardown. The real-ExoPlayer identity/event proof is in NextSlotGraftTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class NextSlotChunkedGraftTest {

    private val chunk = NextSlotPreparation.GRAFT_CHUNK_SIZE

    private fun ids(n: Int, prefix: String = "q") = (0 until n).map { "$prefix$it" }
    private fun items(ids: List<String>): List<MediaItem> = ids.map { ScriptedPlayer.mediaItem(it) }

    private class Rig(test: NextSlotChunkedGraftTest) {
        val scheduler = ManualGraftScheduler()
        val f = PlayerEngineFixture(graftScheduler = scheduler)
        val prep get() = f.engine.nextPreparation
        val p2 get() = f.p2
        fun request(key: CrossfadeTransitionKey, queue: List<MediaItem>) = prep.request(NextSlotRequest(key, queue))

        /** Request, make B READY, and leave the graft waiting for its first turn. */
        fun startGraft(key: CrossfadeTransitionKey, queue: List<MediaItem>) {
            request(key, queue)
            p2.becomeReady()
            check(prep.state is NextSlotState.Grafting) { prep.state }
        }
    }

    private fun rig() = Rig(this)

    // ── ordering ─────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun beforeBChunksKeepFullOrderByInsertingAtAGrowingIndex() {
        val r = rig()
        val all = ids(700)
        val to = 600 // before = 600 -> chunks of 256, 256, 88; after = 99 -> one chunk
        r.startGraft(nextSlotKey(to, from = 599), items(all))
        assertFalse("the READY callback turn does no insertion", r.p2.commands.any { it.contains("addMediaItems") })
        val b = r.p2.uidAt(0)
        r.scheduler.runAll()
        assertEquals(all, r.p2.mediaIds)
        assertEquals(to, r.p2.currentMediaItemIndex)
        assertSame(b, r.p2.uidAt(to))
        val adds = r.p2.commands.filter { it.contains("addMediaItems") }
        assertEquals(
            listOf("P2.addMediaItems(0,256)", "P2.addMediaItems(256,256)", "P2.addMediaItems(512,88)", "P2.addMediaItems(601,99)"),
            adds,
        )
        assertTrue(r.prep.state is NextSlotState.Ready)
    }

    @Test fun afterBChunksRemainOrdered() {
        val r = rig()
        val all = ids(700)
        r.startGraft(nextSlotKey(1, from = 0), items(all)) // before = 1, after = 698 -> 256, 256, 186
        r.scheduler.runAll()
        assertEquals(all, r.p2.mediaIds)
        assertEquals(1, r.p2.currentMediaItemIndex)
        val adds = r.p2.commands.filter { it.contains("addMediaItems") }
        assertEquals(listOf("P2.addMediaItems(0,1)", "P2.addMediaItems(2,256)", "P2.addMediaItems(258,256)", "P2.addMediaItems(514,186)"), adds)
    }

    @Test fun noSingleInsertionExceedsTheChunkBoundAndNothingSeeks() {
        val r = rig()
        r.startGraft(nextSlotKey(2000, from = 1999), items(ids(4000)))
        r.scheduler.runAll()
        val sizes = r.p2.commands.filter { it.contains("addMediaItems") }.map { it.substringAfter(",").substringBefore(")").toInt() }
        assertTrue("largest insertion ${sizes.max()}", sizes.max() <= chunk)
        assertEquals(3999, sizes.sum())
        assertFalse(r.p2.commands.any { it.contains("seek") })
        assertEquals(0L, r.p2.currentPosition)
        assertEquals(4000, r.p2.mediaItemCount)
        assertEquals(2000, r.p2.currentMediaItemIndex)
    }

    @Test fun duplicateHeavyMultiChunkQueueKeepsPositionalIdentity() {
        val r = rig()
        val all = (0 until 1200).map { if (it % 2 == 0) "A" else "B" }
        r.startGraft(nextSlotKey(777, from = 776), items(all))
        r.scheduler.runAll()
        assertEquals(all, r.p2.mediaIds)
        assertEquals(777, r.p2.currentMediaItemIndex)
    }

    // ── progress / never partially Ready ─────────────────────────────────────────────────────────────────────────────────

    @Test fun graftingSpansTurnsTracksProgressAndIsNeverReadyEarly() {
        val r = rig()
        r.startGraft(nextSlotKey(300, from = 299), items(ids(700))) // before 300 -> 2 chunks; after 399 -> 2 chunks; verify 1 slice
        val b = r.p2.uidAt(0)
        val seen = mutableListOf<NextSlotGraftProgress>()
        while (r.prep.state is NextSlotState.Grafting) {
            seen += r.prep.graftProgress!!
            assertEquals("B is current and anchored at every turn boundary", r.p2.currentMediaItemIndex, r.prep.graftProgress!!.beforeInserted)
            assertSame(b, r.p2.uidAt(r.p2.currentMediaItemIndex))
            assertTrue(r.p2.playbackState != Player.STATE_IDLE)
            assertFalse(r.p2.playWhenReady)
            assertTrue(r.scheduler.runNext())
        }
        assertTrue(r.prep.state is NextSlotState.Ready)
        assertNotNull(r.prep.lastGraftTiming)
        assertEquals("2 prepend turns, 2 append turns, 1 verification turn", 5, seen.size)
        assertEquals(seen.sortedBy { it.beforeInserted + it.afterInserted }, seen)
        assertEquals(NextSlotGraftPhase.Before, seen.first().phase)
        assertEquals(0, seen.first().beforeInserted + seen.first().afterInserted)
        assertEquals(4, r.prep.lastGraftTiming!!.chunkCount)
        assertEquals(null, r.prep.graftProgress)
    }

    // ── cancellation at every point of the graft ─────────────────────────────────────────────────────────────────────────

    private enum class At { AfterFirstPrependChunk, DuringPrepend, BetweenPrependAndAppend, DuringAppend, BeforeVerification }

    private fun Rig.stepTo(at: At) {
        startGraft(nextSlotKey(600, from = 599), items(ids(1100))) // before 600 = 3 chunks, after 499 = 2 chunks
        when (at) {
            At.AfterFirstPrependChunk -> scheduler.runNext()
            At.DuringPrepend -> repeat(2) { scheduler.runNext() }
            At.BetweenPrependAndAppend -> repeat(3) { scheduler.runNext() }
            At.DuringAppend -> repeat(4) { scheduler.runNext() }
            At.BeforeVerification -> repeat(5) { scheduler.runNext() }
        }
        check(prep.state is NextSlotState.Grafting)
        val progress = prep.graftProgress!!
        when (at) {
            At.AfterFirstPrependChunk, At.DuringPrepend -> check(progress.phase == NextSlotGraftPhase.Before && progress.beforeInserted > 0)
            At.BetweenPrependAndAppend -> check(progress.beforeInserted == 600 && progress.afterInserted == 0)
            At.DuringAppend -> check(progress.afterInserted in 1..498)
            At.BeforeVerification -> check(progress.afterInserted == 499 && progress.verified == 0)
        }
    }

    @Test fun invalidationAtEveryGraftPointDropsPendingTurnsResetsNextAndPreventsReady() {
        for (at in At.values()) {
            val r = rig()
            r.stepTo(at)
            val survivors = r.scheduler.snapshot()
            assertTrue("$at: a turn is pending", survivors.isNotEmpty())
            r.f.p1.commands.clear()
            val cancelsBefore = r.scheduler.cancelCalls
            r.prep.invalidate(CrossfadeCancelReason.Seek)
            assertEquals("$at", NextSlotState.Idle, r.prep.state)
            assertTrue("$at: remaining chunks cancelled", r.scheduler.cancelCalls > cancelsBefore && r.scheduler.pending == 0)
            assertEquals("$at: NEXT emptied", 0, r.p2.mediaItemCount)
            assertEquals(Player.STATE_IDLE, r.p2.playbackState)
            assertFalse(r.p2.playWhenReady)
            assertTrue("$at: CURRENT untouched", r.f.p1.commands.isEmpty())
            val commandsAfterInvalidate = r.p2.commands.toList()
            // A turn that survived the cancellation (already dequeued elsewhere) must be inert.
            survivors.forEach { it() }
            assertEquals("$at: stale turn mutated NEXT", commandsAfterInvalidate, r.p2.commands)
            assertEquals(NextSlotState.Idle, r.prep.state)
            assertEquals("$at: never Ready", 0, r.prep.graftsCompleted)
            assertNull(r.prep.graftProgress)
        }
    }

    private fun assertNull(value: Any?) = org.junit.Assert.assertNull(value)

    @Test fun supersessionAtEveryGraftPointNeverContaminatesTheNewQueue() {
        for (at in At.values()) {
            val r = rig()
            r.stepTo(at)
            val survivors = r.scheduler.snapshot()
            val oldToken = (r.prep.state as NextSlotState.Grafting).token
            val newQueue = ids(900, prefix = "n")
            val newKey = nextSlotKey(450, from = 449, generation = 2L)
            val state = r.request(newKey, items(newQueue)) as NextSlotState.PreparingTarget
            assertTrue("$at: fresh token", state.token != oldToken)
            assertEquals("$at: NEXT reset, only the new target", listOf("n450"), r.p2.mediaIds)
            // Every old turn is stale: running it changes nothing.
            val before = r.p2.commands.toList()
            survivors.forEach { it() }
            assertEquals("$at", before, r.p2.commands)
            assertEquals(listOf("n450"), r.p2.mediaIds)
            // The new request completes with exactly its own queue.
            r.p2.becomeReady()
            r.scheduler.runAll()
            assertTrue("$at: ${r.prep.state}", r.prep.state is NextSlotState.Ready)
            assertEquals(newQueue, r.p2.mediaIds)
            assertEquals(450, r.p2.currentMediaItemIndex)
            assertEquals(1, r.prep.graftsCompleted)
        }
    }

    @Test fun sameKeyWhileGraftingIsIdempotent() {
        val r = rig()
        val q = items(ids(700))
        r.startGraft(nextSlotKey(300, from = 299), q)
        r.scheduler.runNext()
        val commands = r.p2.commands.toList()
        val state = r.prep.state
        assertEquals(state, r.request(nextSlotKey(300, from = 299), q))
        assertEquals(commands, r.p2.commands)
        assertEquals(1, r.prep.preparationsStarted)
    }

    // ── failure containment ──────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun aThrowingChunkFailsOnlyThisPreparationAndCancelsTheRest() {
        val r = rig()
        r.f.facade.play(); idleMainLooper()
        r.f.events.events.clear()
        r.f.p1.commands.clear()
        r.startGraft(nextSlotKey(600, from = 599), items(ids(1100)))
        r.scheduler.runNext() // one chunk succeeds
        r.p2.throwOnAdd = true
        r.scheduler.runNext() // this chunk throws
        assertEquals(NextSlotFailure.GraftError, (r.prep.state as NextSlotState.Failed).reason)
        assertEquals(0, r.scheduler.pending)
        assertEquals(0, r.p2.mediaItemCount)
        assertEquals(Player.STATE_IDLE, r.p2.playbackState)
        assertFalse(r.p2.playWhenReady)
        assertTrue("CURRENT untouched", r.f.p1.commands.isEmpty())
        assertTrue("no logical error", r.f.events.events.none { it.startsWith("error") })
        assertTrue(r.f.facade.isPlaying)
        assertSame(r.f.p1, r.f.facade.delegatePlayer)
        assertEquals(0, r.prep.graftsCompleted)
    }

    @Test fun anExternalTimelineChangeBetweenChunksFailsClosed() {
        val r = rig()
        r.startGraft(nextSlotKey(600, from = 599), items(ids(1100)))
        r.scheduler.runNext()
        r.p2.removeMediaItem(0) // something other than the owner changed NEXT's timeline
        r.scheduler.runNext()
        assertEquals(NextSlotFailure.TimelineMismatch, (r.prep.state as NextSlotState.Failed).reason)
        assertEquals(0, r.p2.mediaItemCount)
    }

    @Test fun aPhysicalErrorDuringGraftFailsAndCancelsTurns() {
        val r = rig()
        r.startGraft(nextSlotKey(600, from = 599), items(ids(1100)))
        r.scheduler.runNext()
        r.p2.failWith(); idleMainLooper()
        assertEquals(NextSlotFailure.PrepareError, (r.prep.state as NextSlotState.Failed).reason)
        assertEquals(0, r.scheduler.pending)
        assertEquals(0, r.p2.mediaItemCount)
    }

    // ── teardown cancels pending graft turns ─────────────────────────────────────────────────────────────────────────────

    private class DriverRig(test: NextSlotChunkedGraftTest) {
        val rig = Rig(test)
        val timer = FakeTimer()
        var generation = 7L
        val queue = (1L..700L).map {
            com.launchpoint.wavdrop.data.model.Song(
                id = it, title = "S$it", artist = "A", album = "B", albumId = 0L, duration = 200_000L,
                uri = "content://media/$it", dateAdded = 0L, trackNumber = 0, year = 2020,
            )
        }
        val driver = NextSlotPreparationDriver(
            preparation = rig.prep,
            snapshotProvider = {
                CrossfadeRuntimeSnapshot(
                    queueGeneration = generation, playbackQueue = queue, currentPlaybackIndex = 300,
                    repeatMode = RepeatMode.OFF, shuffleEnabled = false, isPlaying = true, isExternalPlayback = false,
                    playerQueueNeedsSync = false, controllerConnected = true,
                )
            },
            materialize = { songs -> songs.map { MediaItem.Builder().setMediaId(it.id.toString()).build() } },
            configuredDurationMsProvider = { 6_000L },
            currentDurationMsProvider = { 200_000L },
            scheduler = timer,
        )

        fun intoGrafting() {
            driver.evaluate()
            rig.p2.becomeReady()
            rig.scheduler.runNext()
            check(rig.prep.state is NextSlotState.Grafting) { rig.prep.state }
            check(rig.scheduler.pending > 0)
        }
    }

    private class FakeTimer : CrossfadeTimingScheduler {
        override fun postDelayed(delayMs: Long, block: () -> Unit) = Unit
        override fun cancelAll() = Unit
    }

    @Test fun driverCloseDuringGraftCancelsPendingTurns() {
        val d = DriverRig(this); d.intoGrafting()
        d.driver.close()
        assertEquals(NextSlotState.Idle, d.rig.prep.state)
        assertEquals(0, d.rig.scheduler.pending)
        assertEquals(0, d.rig.p2.mediaItemCount)
    }

    @Test fun configurationDisableDuringGraftCancelsPendingTurns() {
        val d = DriverRig(this); d.intoGrafting()
        applyNextSlotConfiguredDurationChange(6_000L, 0L, d.driver)
        assertEquals(NextSlotState.Idle, d.rig.prep.state)
        assertEquals(0, d.rig.scheduler.pending)
    }

    @Test fun explicitCancelHookDuringGraftCancelsPendingTurns() {
        val d = DriverRig(this); d.intoGrafting()
        recoverCrossfadeFromQueueReorder(d.driver)
        assertEquals(NextSlotState.Idle, d.rig.prep.state)
        assertEquals(0, d.rig.scheduler.pending)
    }

    @Test fun engineReleaseDuringGraftCancelsPendingTurnsAndLeavesNothingHeld() {
        val r = rig()
        r.startGraft(nextSlotKey(600, from = 599), items(ids(1100)))
        r.scheduler.runNext()
        val survivors = r.scheduler.snapshot()
        r.f.engine.release()
        assertEquals(0, r.scheduler.pending)
        assertEquals(NextSlotState.Idle, r.prep.state)
        val before = r.p2.commands.toList()
        survivors.forEach { it() }
        assertEquals("a surviving turn must not touch a released engine's NEXT", before, r.p2.commands)
    }

    @Test fun roleSwapSeamEndsAGraftInFlight() {
        val r = rig()
        r.startGraft(nextSlotKey(600, from = 599), items(ids(1100)))
        r.scheduler.runNext()
        val survivors = r.scheduler.snapshot()
        r.f.engine.swapRolesForTest()
        assertEquals(NextSlotState.Idle, r.prep.state)
        survivors.forEach { it() }
        assertEquals(NextSlotState.Idle, r.prep.state)
    }

    @Test fun preparationOwnerNeverStartsOrPromotesDuringAChunkedGraft() {
        val r = rig()
        r.startGraft(nextSlotKey(600, from = 599), items(ids(1100)))
        r.scheduler.runAll()
        assertFalse(r.p2.playWhenReady)
        assertFalse(r.p2.commands.any { it.contains("setPlayWhenReady(true)") || it.contains("seek") })
        assertSame(r.f.p1, r.f.facade.delegatePlayer)
        assertSame(r.f.p2, r.f.engine.nextPlayer)
    }
}
