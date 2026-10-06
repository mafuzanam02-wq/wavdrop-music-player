package com.launchpoint.wavdrop.playback

import androidx.media3.common.util.UnstableApi
import com.launchpoint.wavdrop.data.model.Song
import kotlin.random.Random
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The ACTUAL [PhysicalQueueReconciler], through the production [PlayerQueueTimeline] adapter, against a REAL MediaController
 * (ExoPlayer behind a MediaSession). Every mutation is a real Media3 playlist mutation and every index the reconciler re-reads
 * is the controller's real, immediately-updated value. The scripted 1k/5k/12,288 tests keep the scaling shape; this proves the
 * runtime semantics at a size that keeps real-source construction affordable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class RealMedia3ReconcilerIntegrationTest {

    private val rigs = mutableListOf<RealMedia3QueueRig>()

    @After fun tearDown() { rigs.forEach { it.releaseAll() }; rigs.clear() }

    private fun song(id: Long) = RealMedia3QueueRig.song(id)

    private class Counting(val delegate: PhysicalQueueTimeline) : PhysicalQueueTimeline by delegate {
        var ops = 0
        var largestMaterialized = 0
        override fun replace(fromIndex: Int, toIndexExclusive: Int, songs: List<Song>) {
            ops++
            largestMaterialized = maxOf(largestMaterialized, songs.size)
            delegate.replace(fromIndex, toIndexExclusive, songs)
        }
    }

    private fun adapter(rig: RealMedia3QueueRig) = PlayerQueueTimeline(rig.controller) { from, to, songs ->
        if (songs.isEmpty()) rig.controller.removeMediaItems(from, to)
        else rig.controller.replaceMediaItems(from, to, songs.map { it.toPlaybackMediaItem() })
    }

    private fun verifyWindow(rig: RealMedia3QueueRig, queue: List<Song>, outcome: QueueReconcileOutcome.InProgress) {
        for (logical in outcome.verified) {
            val physical = logical + outcome.physicalOffset
            assertTrue("verified logical $logical maps to physical $physical in 0..${rig.controller.mediaItemCount - 1}", physical in 0 until rig.controller.mediaItemCount)
            assertEquals("verified logical $logical", queue[logical].id.toString(), rig.controller.getMediaItemAt(physical).mediaId)
        }
    }

    @Test fun shuffleLikeRepairOnRealMedia3KeepsTheCurrentItemAndReachesCompleted() {
        val size = 400
        val physical = List(size) { song(1_000L + it) }
        val currentPhysical = 211
        val currentSong = physical[currentPhysical]
        val rnd = Random(11)
        // Logical: current moved to 0 (shuffle ON), the rest shuffled, with two duplicate occurrences of one song.
        val rest = physical.filterIndexed { i, _ -> i != currentPhysical }.shuffled(rnd).toMutableList()
        rest[5] = song(1_001L); rest[250] = song(1_001L)
        val logical = listOf(currentSong) + rest

        val rig = RealMedia3QueueRig(physical, currentPhysical, startPositionMs = 33_333L, playing = true).also { rigs += it }
        rig.settle(300)
        val timeline = Counting(adapter(rig))
        val reconciler = PhysicalQueueReconciler(chunkSize = 40)
        val positionBefore = rig.controller.currentPosition
        var cursor: ReconcileCursor? = null
        var steps = 0
        var outcome: QueueReconcileOutcome
        do {
            steps++
            outcome = reconciler.reconcile(timeline, logical, logicalCurrent = 0, priorityIndices = if (steps == 1) listOf(1) else emptyList(), budget = if (steps == 1) 0 else 40, cursor = cursor)
            assertTrue("never unsafe: $outcome", outcome !is QueueReconcileOutcome.Unsafe)
            assertEquals("current item never changes", currentSong.id.toString(), rig.controller.currentMediaItem?.mediaId)
            assertTrue("playWhenReady preserved", rig.controller.playWhenReady)
            if (outcome is QueueReconcileOutcome.InProgress) {
                verifyWindow(rig, logical, outcome)
                cursor = outcome.cursor
            }
            if (steps == 1) assertEquals("Next target is physically next right after the synchronous window repair", logical[1].id.toString(), rig.controller.getMediaItemAt(rig.controller.currentMediaItemIndex + 1).mediaId)
        } while (outcome !is QueueReconcileOutcome.Completed && steps < 200)
        rig.settle(300)

        assertTrue("completed in $steps steps", outcome is QueueReconcileOutcome.Completed)
        assertEquals(logical.map { it.id.toString() }, rig.snapshot().ids)
        assertEquals(logical.map { it.id.toString() }, rig.serverIds()) // the server timeline agrees with the controller's masked view
        assertEquals(0, rig.controller.currentMediaItemIndex)
        assertTrue("every materialized batch is bounded: ${timeline.largestMaterialized}", timeline.largestMaterialized <= 40)
        assertTrue("position intact (${positionBefore} -> ${rig.controller.currentPosition})", rig.controller.currentPosition >= positionBefore - 50)
        println("REAL-M3 reconciler shuffle-like: steps=$steps ops=${timeline.ops} largestBatch=${timeline.largestMaterialized}")
    }

    @Test fun missingPrefixMissingAndExtraItemsAreRepairedWithCorrectOffsetsAfterEveryRealMutation() {
        val size = 300
        val logical = List(size) { song(5_000L + it) }
        val currentLogical = 180
        // Physical: lost the oldest 90 items (negative offset), has 4 extra bogus items after current and is missing the tail.
        val physical = logical.subList(90, 260).toMutableList().also { it.addAll(95, List(4) { i -> song(9_000L + i) }) }
        val currentPhysical = physical.indexOfFirst { it.id == logical[currentLogical].id }
        val rig = RealMedia3QueueRig(physical, currentPhysical, startPositionMs = 5_000L, playing = false).also { rigs += it }
        rig.settle(300)
        val timeline = Counting(adapter(rig))
        val reconciler = PhysicalQueueReconciler(chunkSize = 32)

        var cursor: ReconcileCursor? = null
        var outcome = reconciler.reconcile(timeline, logical, currentLogical, priorityIndices = listOf(currentLogical + 1), budget = 0)
        assertTrue(outcome is QueueReconcileOutcome.InProgress)
        outcome as QueueReconcileOutcome.InProgress
        assertTrue("offset is negative: the physical prefix is shorter", outcome.physicalOffset < 0)
        verifyWindow(rig, logical, outcome)
        assertTrue("only window-sized work so far", timeline.largestMaterialized <= 8)
        cursor = outcome.cursor

        var steps = 0
        while (outcome !is QueueReconcileOutcome.Completed && steps++ < 300) {
            outcome = reconciler.reconcile(timeline, logical, currentLogical, budget = 32, cursor = cursor)
            assertTrue("never unsafe: $outcome", outcome !is QueueReconcileOutcome.Unsafe)
            assertEquals(logical[currentLogical].id.toString(), rig.controller.currentMediaItem?.mediaId)
            if (outcome is QueueReconcileOutcome.InProgress) { verifyWindow(rig, logical, outcome); cursor = outcome.cursor }
        }
        rig.settle(300)

        assertTrue(outcome is QueueReconcileOutcome.Completed)
        assertEquals(logical.map { it.id.toString() }, rig.snapshot().ids)
        assertEquals(logical.map { it.id.toString() }, rig.serverIds())
        assertEquals(currentLogical, rig.controller.currentMediaItemIndex)
        assertTrue(timeline.largestMaterialized <= 32)
        assertTrue("paused stays paused", !rig.controller.playWhenReady)
    }
}
