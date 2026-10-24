package com.launchpoint.wavdrop.playback

import androidx.media3.common.util.UnstableApi
import com.launchpoint.wavdrop.data.model.Song
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P5-A0: [PlayerController.captureCrossfadeRuntimeSnapshot] shares the controller's queue list instead of copying it every
 * 250 ms. That is only sound because the controller REPLACES its queue on every structural change and never mutates a list in
 * place, so each test drives the REAL controller (no Media3 controller attached: the logical queue is the truth) and proves both
 * halves: captures share one reference while nothing changes, and an earlier snapshot keeps describing its own queue after a
 * structural edit.
 *
 * Assertions are on reference identity, list contents and allocated bytes of the capturing thread (a structural, deterministic
 * measure); none is a wall-clock bound.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class CrossfadeSnapshotQueueSharingTest {

    private val rigs = mutableListOf<AuthorityRig>()

    @After fun tearDown() {
        rigs.forEach { it.releaseAll() }
        rigs.clear()
        PlaybackStartupTrace.resetForTest()
    }

    private fun song(id: Long) = RealMedia3QueueRig.song(id)

    // java.management is not on the Android unit-test compile classpath, so the JVM per-thread allocation counter is read
    // reflectively (HotSpot: com.sun.management.ThreadMXBean).
    private val threadMx: Any = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)!!
    private val mxClass = Class.forName("com.sun.management.ThreadMXBean")
    private fun allocationCounterSupported(): Boolean = mxClass.getMethod("isThreadAllocatedMemorySupported").invoke(threadMx) as Boolean
    private fun allocatedBytes(): Long =
        mxClass.getMethod("getThreadAllocatedBytes", java.lang.Long.TYPE).invoke(threadMx, Thread.currentThread().id) as Long

    /** A controller with an accepted queue, still without a Media3 controller (the logical queue is authoritative). */
    private fun rigWith(queue: List<Song>, startIndex: Int): AuthorityRig =
        AuthorityRig("snap").also {
            rigs += it
            it.pc.playFromQueue(queue, startIndex = startIndex)
            it.settle(50)
        }

    private fun internalQueue(rig: AuthorityRig): List<Song> {
        @Suppress("UNCHECKED_CAST")
        return rig.field("playbackQueue").get(rig.pc) as List<Song>
    }

    private fun ids(queue: List<Song>) = queue.map { it.id }

    // ------------------------------------------------------------------------------------------ sharing

    @Test fun twoCapturesWithoutAnEditShareTheSameQueueInstanceAndTheControllersOwnList() {
        val rig = rigWith((1L..8L).map(::song), startIndex = 2)
        val first = rig.pc.captureCrossfadeRuntimeSnapshot()
        val second = rig.pc.captureCrossfadeRuntimeSnapshot()

        assertSame("no edit between captures: one shared list", first.playbackQueue, second.playbackQueue)
        assertSame("the snapshot references the controller's own list, not a copy", internalQueue(rig), first.playbackQueue)
        assertEquals(first.queueGeneration, second.queueGeneration)
        assertEquals(2, first.currentPlaybackIndex)
    }

    @Test fun capturingAHugeQueueAllocatesNothingProportionalToIt() {
        val n = 25_000
        val queue = (1L..n.toLong()).map(::song)
        val rig = rigWith(queue, startIndex = 100)
        assertTrue("allocation accounting must be available for this proof", allocationCounterSupported())

        // What a defensive copy of this exact queue costs on this JVM (the removed behaviour): proves the probe can see it.
        val copyBefore = allocatedBytes()
        val copy = internalQueue(rig).toList()
        val copyBytes = allocatedBytes() - copyBefore
        assertEquals(n, copy.size)
        assertTrue("a full copy of $n entries must be clearly visible (was $copyBytes bytes)", copyBytes >= n * 4L)

        rig.pc.captureCrossfadeRuntimeSnapshot() // warm up class loading / lazy initialisation
        val captures = 200
        val before = allocatedBytes()
        var last: CrossfadeRuntimeSnapshot? = null
        repeat(captures) { last = rig.pc.captureCrossfadeRuntimeSnapshot() }
        val perCapture = (allocatedBytes() - before) / captures

        println("P5-A0 allocation n=$n oneFullCopyBytes=$copyBytes perCaptureBytes=$perCapture")
        assertSame(internalQueue(rig), last!!.playbackQueue)
        assertTrue(
            "a capture must not allocate a queue-sized list: $perCapture bytes per capture vs $copyBytes for one copy",
            perCapture < copyBytes / 20,
        )
        assertTrue("and the per-capture allocation is small and queue-size independent ($perCapture bytes)", perCapture < 4_096)
    }

    // ------------------------------------------------------------------------------------------ immutability across edits

    @Test fun appendingReplacesTheQueueAndLeavesTheEarlierSnapshotUntouched() {
        val rig = rigWith((1L..6L).map(::song), startIndex = 1)
        val old = rig.pc.captureCrossfadeRuntimeSnapshot()
        val oldIds = ids(old.playbackQueue)

        rig.pc.addToQueue(song(99))
        val fresh = rig.pc.captureCrossfadeRuntimeSnapshot()

        assertNotSame("a structural edit produces a new list instance", old.playbackQueue, fresh.playbackQueue)
        assertEquals("the earlier snapshot still describes its own queue", oldIds, ids(old.playbackQueue))
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L, 99L), ids(fresh.playbackQueue))
        assertTrue(fresh.queueGeneration > old.queueGeneration)
        assertEquals(1, old.currentPlaybackIndex)
        assertEquals(1, fresh.currentPlaybackIndex)
    }

    @Test fun removingANonCurrentSongLeavesTheEarlierSnapshotUntouched() {
        val rig = rigWith((1L..6L).map(::song), startIndex = 1)
        val old = rig.pc.captureCrossfadeRuntimeSnapshot()
        val oldIds = ids(old.playbackQueue)

        rig.pc.removeFromQueue(4)
        val fresh = rig.pc.captureCrossfadeRuntimeSnapshot()

        assertNotSame(old.playbackQueue, fresh.playbackQueue)
        assertEquals(oldIds, ids(old.playbackQueue))
        assertEquals(listOf(1L, 2L, 3L, 4L, 6L), ids(fresh.playbackQueue))
        assertTrue(fresh.queueGeneration > old.queueGeneration)
    }

    @Test fun reorderingLeavesTheEarlierSnapshotUntouched() {
        val rig = rigWith((1L..7L).map(::song), startIndex = 1)
        val old = rig.pc.captureCrossfadeRuntimeSnapshot()
        val oldIds = ids(old.playbackQueue)

        rig.pc.moveQueueItemTo(fromPlaybackIndex = 5, toPlaybackIndex = 2)
        val fresh = rig.pc.captureCrossfadeRuntimeSnapshot()

        assertNotSame(old.playbackQueue, fresh.playbackQueue)
        assertEquals(oldIds, ids(old.playbackQueue))
        assertEquals(listOf(1L, 2L, 6L, 3L, 4L, 5L, 7L), ids(fresh.playbackQueue))
        assertEquals("the current occurrence did not move", 1, fresh.currentPlaybackIndex)
    }

    @Test fun shufflePreservesTheOldSnapshotsOrderAndContents() {
        val rig = rigWith((1L..30L).map(::song), startIndex = 4)
        val old = rig.pc.captureCrossfadeRuntimeSnapshot()
        val oldIds = ids(old.playbackQueue)
        assertEquals(false, old.shuffleEnabled)

        rig.pc.toggleShuffle()
        val fresh = rig.pc.captureCrossfadeRuntimeSnapshot()

        assertNotSame(old.playbackQueue, fresh.playbackQueue)
        assertEquals("shuffle must not reorder the earlier snapshot's list", oldIds, ids(old.playbackQueue))
        assertEquals(true, fresh.shuffleEnabled)
        assertEquals("same songs, new order", oldIds.sorted(), ids(fresh.playbackQueue).sorted())
        assertEquals("the current song is kept", 5L, fresh.playbackQueue[fresh.currentPlaybackIndex!!].id)
        assertTrue(fresh.queueGeneration > old.queueGeneration)
    }

    @Test fun duplicateSongOccurrencesStaySeparateInBothSnapshots() {
        val a = song(1)
        val b = song(2)
        val rig = rigWith(listOf(a, b, a, b, a), startIndex = 2)
        val old = rig.pc.captureCrossfadeRuntimeSnapshot()
        assertEquals(listOf(1L, 2L, 1L, 2L, 1L), ids(old.playbackQueue))
        assertEquals("the SECOND copy of song 1 is current", 2, old.currentPlaybackIndex)

        rig.pc.removeFromQueue(4) // the last copy of song 1
        val fresh = rig.pc.captureCrossfadeRuntimeSnapshot()

        assertEquals(listOf(1L, 2L, 1L, 2L, 1L), ids(old.playbackQueue))
        assertEquals(listOf(1L, 2L, 1L, 2L), ids(fresh.playbackQueue))
        assertEquals("the same occurrence is still current in the new queue", 2, fresh.currentPlaybackIndex)
    }

    @Test fun aReplacementQueueIsAFreshInstanceWithItsOwnGeneration() {
        val rig = rigWith((1L..5L).map(::song), startIndex = 0)
        val old = rig.pc.captureCrossfadeRuntimeSnapshot()

        rig.pc.playFromQueue((10L..14L).map(::song), startIndex = 3)
        val fresh = rig.pc.captureCrossfadeRuntimeSnapshot()

        assertNotSame(old.playbackQueue, fresh.playbackQueue)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), ids(old.playbackQueue))
        assertEquals(listOf(10L, 11L, 12L, 13L, 14L), ids(fresh.playbackQueue))
        assertEquals(3, fresh.currentPlaybackIndex)
        assertTrue(fresh.queueGeneration > old.queueGeneration)
    }

    // ------------------------------------------------------------------------------------------ crossfade ownership

    @Test fun aPlanBoundToAnOlderSnapshotIsRejectedAfterTheGenerationMoves() {
        val rig = rigWith((1L..8L).map(::song), startIndex = 2)
        val old = rig.pc.captureCrossfadeRuntimeSnapshot().copy(isPlaying = true, controllerConnected = true)
        val plan = planCrossfadeFromRuntimeSnapshot(configuredDurationMs = 4_000L, snapshot = old, currentDurationOverrideMs = 200_000L)
        assertTrue("the older snapshot yields an eligible plan: $plan", plan is CrossfadeTransitionPlan.Eligible)
        val key = bindCrossfadeTransition(old, plan as CrossfadeTransitionPlan.Eligible)
        assertNotNull(key)
        assertNull("the key is owned by the snapshot it was bound to", crossfadeOwnershipLossReason(old, key!!))

        rig.pc.removeFromQueue(6) // a structural edit elsewhere: the generation moves
        val fresh = rig.pc.captureCrossfadeRuntimeSnapshot().copy(isPlaying = true, controllerConnected = true, playerQueueNeedsSync = false)

        assertTrue(fresh.queueGeneration > key.queueGeneration)
        assertEquals(CrossfadeCancelReason.QueueMutation, crossfadeOwnershipLossReason(fresh, key))
        assertTrue("the older snapshot, untouched by the edit, still owns its own key", old.ownsCrossfadeSource(key))
    }
}
