package com.launchpoint.wavdrop.playback

import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.playback.ShufflePhysicalSyncPlan.MarkDirty
import com.launchpoint.wavdrop.playback.ShufflePhysicalSyncPlan.NoOp
import com.launchpoint.wavdrop.playback.ShufflePhysicalSyncPlan.ReplaceAroundCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ShuffleQueueSyncPlannerTest {

    /** [tag] distinguishes occurrences of the same song id (they are otherwise equal). */
    private fun song(id: Long, tag: Long = 0L) = Song(
        id = id, title = "Song $id", artist = "Artist", album = "Album",
        albumId = 0L, duration = 180_000L, uri = "content://media/$id",
        dateAdded = tag, trackNumber = 0, year = 2020,
    )

    private val a = song(1)
    private val b = song(2)
    private val c = song(3)
    private val d = song(4)
    private val e = song(5)
    private val x = song(6)
    private val y = song(7)
    private val z = song(8)

    private fun plan(
        old: List<Song>,
        new: List<Song>,
        oldIndex: Int,
        newIndex: Int,
        controllerAvailable: Boolean = true,
        controllerIndex: Int? = oldIndex,
        controllerCount: Int? = old.size,
        dirty: Boolean = false,
    ) = planShufflePhysicalSync(
        oldPlaybackQueue = old,
        newPlaybackQueue = new,
        oldCurrentPlaybackIndex = oldIndex,
        newCurrentPlaybackIndex = newIndex,
        controllerAvailable = controllerAvailable,
        controllerCurrentIndex = controllerIndex,
        controllerMediaItemCount = controllerCount,
        playerQueueNeedsSync = dirty,
    )

    private fun ShufflePhysicalSyncPlan.replace(): ReplaceAroundCurrent {
        assertTrue("expected ReplaceAroundCurrent but was $this", this is ReplaceAroundCurrent)
        return this as ReplaceAroundCurrent
    }

    /** Applies the plan the way the controller does, to a copy of the physical playlist. */
    private fun apply(physical: List<Song>, plan: ReplaceAroundCurrent): List<Song> {
        val list = physical.toMutableList()
        // Suffix first, then prefix: identical order to PlayerController.applyShuffleAroundCurrent.
        val suffixStart = plan.oldCurrentPhysicalIndex + 1
        repeat(list.size - suffixStart) { list.removeAt(suffixStart) }
        list.addAll(suffixStart, plan.desiredSuffix)
        repeat(plan.oldCurrentPhysicalIndex) { list.removeAt(0) }
        list.addAll(0, plan.desiredPrefix)
        return list
    }

    @Test
    fun `aligned shuffle on produces a live around-current plan`() {
        val old = listOf(a, b, c, d, e)
        val new = listOf(c, e, a, d, b)

        val p = plan(old, new, oldIndex = 2, newIndex = 0).replace()

        assertEquals(2, p.oldCurrentPhysicalIndex)
        assertEquals(0, p.newCurrentPhysicalIndex)
        assertEquals(emptyList<Song>(), p.desiredPrefix)
        assertEquals(listOf(e, a, d, b), p.desiredSuffix)
        assertEquals(new, apply(old, p))
    }

    @Test
    fun `aligned shuffle off produces a live around-current plan`() {
        val oldShuffled = listOf(x, y, c, z, e)
        val natural = listOf(a, b, c, d, e)

        val p = plan(oldShuffled, natural, oldIndex = 2, newIndex = 2).replace()

        assertEquals(listOf(a, b), p.desiredPrefix)
        assertEquals(listOf(d, e), p.desiredSuffix)
        assertEquals(natural, apply(oldShuffled, p))
    }

    @Test
    fun `current occurrence is excluded from both replaced ranges`() {
        val old = listOf(a, b, c, d, e)
        val new = listOf(e, a, c, b, d)

        val p = plan(old, new, oldIndex = 2, newIndex = 2).replace()

        assertFalse(c in p.desiredPrefix)
        assertFalse(c in p.desiredSuffix)
        assertEquals(p.newCurrentPhysicalIndex, p.desiredPrefix.size)
        assertEquals(new, apply(old, p))
        assertSame(c, apply(old, p)[p.newCurrentPhysicalIndex])
    }

    @Test
    fun `current index may move from nonzero to zero`() {
        val old = listOf(a, b, c, d, e)
        val new = listOf(c, a, b, d, e)

        val p = plan(old, new, oldIndex = 2, newIndex = 0).replace()

        assertEquals(0, p.newCurrentPhysicalIndex)
        assertEquals(new, apply(old, p))
    }

    @Test
    fun `current index may move from zero to nonzero`() {
        val old = listOf(c, a, b, d, e)
        val new = listOf(a, b, d, c, e)

        val p = plan(old, new, oldIndex = 0, newIndex = 3).replace()

        assertEquals(0, p.oldCurrentPhysicalIndex)
        assertEquals(3, p.newCurrentPhysicalIndex)
        assertEquals(listOf(a, b, d), p.desiredPrefix)
        assertEquals(listOf(e), p.desiredSuffix)
        assertEquals(new, apply(old, p))
    }

    @Test
    fun `duplicate song ids are reasoned about by position not id`() {
        val a1 = song(1, tag = 1)
        val a2 = song(1, tag = 2)
        val a3 = song(1, tag = 3)
        // A1 B [A2] A3 D  ->  [A2] D A3 B A1 (same occurrences, reordered)
        val old = listOf(a1, b, a2, a3, d)
        val new = listOf(a2, d, a3, b, a1)

        val p = plan(old, new, oldIndex = 2, newIndex = 0).replace()

        assertEquals(new, apply(old, p))
        assertSame(a2, apply(old, p)[0])
        // A different occurrence of the same song at the new current index is not the same occurrence.
        assertSame(MarkDirty, plan(old, listOf(a1, d, a3, b, a2), oldIndex = 2, newIndex = 0))
    }

    @Test
    fun `already dirty queue is marked dirty`() {
        val old = listOf(a, b, c, d, e)
        assertSame(MarkDirty, plan(old, listOf(c, e, a, d, b), 2, 0, dirty = true))
    }

    @Test
    fun `controller unavailable is marked dirty`() {
        val old = listOf(a, b, c, d, e)
        assertSame(
            MarkDirty,
            plan(
                old, listOf(c, e, a, d, b), 2, 0,
                controllerAvailable = false, controllerIndex = null, controllerCount = null,
            ),
        )
    }

    @Test
    fun `controller index mismatch is marked dirty`() {
        val old = listOf(a, b, c, d, e)
        assertSame(MarkDirty, plan(old, listOf(c, e, a, d, b), 2, 0, controllerIndex = 3))
        assertSame(MarkDirty, plan(old, listOf(c, e, a, d, b), 2, 0, controllerIndex = null))
    }

    @Test
    fun `controller media item count mismatch is marked dirty`() {
        val old = listOf(a, b, c, d, e)
        assertSame(MarkDirty, plan(old, listOf(c, e, a, d, b), 2, 0, controllerCount = 4))
        assertSame(MarkDirty, plan(old, listOf(c, e, a, d, b), 2, 0, controllerCount = null))
    }

    @Test
    fun `invalid old or new current index is marked dirty`() {
        val old = listOf(a, b, c, d, e)
        val new = listOf(c, e, a, d, b)
        assertSame(MarkDirty, plan(old, new, oldIndex = -1, newIndex = 0, controllerIndex = -1))
        assertSame(MarkDirty, plan(old, new, oldIndex = 9, newIndex = 0, controllerIndex = 9))
        assertSame(MarkDirty, plan(old, new, oldIndex = 2, newIndex = 7))
        assertSame(MarkDirty, plan(old, new, oldIndex = 2, newIndex = -1))
    }

    @Test
    fun `size change or different current occurrence is marked dirty`() {
        val old = listOf(a, b, c, d, e)
        assertSame(MarkDirty, plan(old, listOf(c, a, b, d), oldIndex = 2, newIndex = 0))
        assertSame(MarkDirty, plan(old, listOf(d, e, a, c, b), oldIndex = 2, newIndex = 0))
    }

    @Test
    fun `unchanged physical order is a no-op`() {
        val old = listOf(a, b, c, d, e)
        assertSame(NoOp, plan(old, old.toList(), oldIndex = 2, newIndex = 2))
    }

    @Test
    fun `real shuffle model results sync live in both directions and round trip`() {
        val library = listOf(a, b, c, d, e)
        val natural = library.indices.toList()

        val on = QueueMutation.shuffleToggleModel(library, natural, 2, shuffleEnabled = true, random = Random(7))!!
        val onPlan = plan(library, on.playbackQueue, 2, on.currentPlaybackIndex).replace()
        assertEquals(on.playbackQueue, apply(library, onPlan))
        assertSame(c, apply(library, onPlan)[onPlan.newCurrentPhysicalIndex])

        val off = QueueMutation.shuffleToggleModel(
            library, on.playbackOrder, on.currentPlaybackIndex, shuffleEnabled = false,
        )!!
        val offPlan = plan(on.playbackQueue, off.playbackQueue, on.currentPlaybackIndex, off.currentPlaybackIndex)
            .replace()
        assertEquals(off.playbackQueue, apply(on.playbackQueue, offPlan))
        assertSame(c, apply(on.playbackQueue, offPlan)[offPlan.newCurrentPhysicalIndex])
    }

    @Test
    fun `successful live sync leaves the queue aligned so the natural boundary needs no resync`() {
        val old = listOf(a, b, c, d, e)
        val livePlan = plan(old, listOf(c, e, a, d, b), 2, 0)

        assertFalse(livePlan.leavesPlayerQueueDirty)
        assertFalse(
            naturalTransitionRequiresQueueResync(MediaItemTransitionKind.Auto, livePlan.leavesPlayerQueueDirty),
        )
        assertFalse(NoOp.leavesPlayerQueueDirty)
    }

    @Test
    fun `live replacement is dirty before the physical mutation`() {
        val live = plan(listOf(a, b, c, d, e), listOf(c, e, a, d, b), 2, 0).replace()

        assertTrue(live.playerQueueNeedsSync(ShuffleLiveSyncProgress.BeforeMutation))
    }

    @Test
    fun `live replacement is clean only after successful completion`() {
        val live = plan(listOf(a, b, c, d, e), listOf(c, e, a, d, b), 2, 0).replace()

        assertFalse(live.playerQueueNeedsSync(ShuffleLiveSyncProgress.Succeeded))
    }

    @Test
    fun `live replacement stays dirty after a failed mutation`() {
        val live = plan(listOf(a, b, c, d, e), listOf(c, e, a, d, b), 2, 0).replace()

        assertTrue(live.playerQueueNeedsSync(ShuffleLiveSyncProgress.Failed))
        // A failure keeps the boundary resync as the recovery path.
        assertTrue(
            naturalTransitionRequiresQueueResync(
                MediaItemTransitionKind.Auto,
                live.playerQueueNeedsSync(ShuffleLiveSyncProgress.Failed),
            ),
        )
    }

    @Test
    fun `no-op is clean and mark-dirty is dirty at every progress point`() {
        ShuffleLiveSyncProgress.values().forEach { progress ->
            assertFalse(NoOp.playerQueueNeedsSync(progress))
            assertTrue(MarkDirty.playerQueueNeedsSync(progress))
        }
    }

    @Test
    fun `fallback keeps the dirty flag so the boundary resync remains the recovery path`() {
        val dirtyPlan = plan(
            listOf(a, b, c), listOf(c, a, b), 1, 0,
            controllerAvailable = false, controllerIndex = null, controllerCount = null,
        )

        assertTrue(dirtyPlan.leavesPlayerQueueDirty)
        assertTrue(
            naturalTransitionRequiresQueueResync(MediaItemTransitionKind.Auto, dirtyPlan.leavesPlayerQueueDirty),
        )
    }
}
