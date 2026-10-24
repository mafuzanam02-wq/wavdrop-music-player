package com.launchpoint.wavdrop.playback

import android.util.Pair
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Deterministic timeline-IDENTITY guards for the crossfade façade (P4). Real ExoPlayers behind a real [SessionFacade]; nothing here
 * asserts wall-clock time. The pathology being guarded is structural: Media3 keys its period index by
 * `Pair(windowUid, periodUid)` (XOR-hashed) and ExoPlayer's period uid already embeds the window uid, so the hash cancels and every
 * period collides. The invariant that makes the index (and every uid lookup) O(1) again is "distinct hash codes == distinct uids".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class CrossfadeTimelineIdentityTest {

    private val players = mutableListOf<ExoPlayer>()

    @After fun tearDown() {
        players.forEach { runCatching { it.release() } }
        players.clear()
    }

    private fun player(ids: List<Long>, index: Int = 0): ExoPlayer = TestMediaSource.newPlayer(RuntimeEnvironment.getApplication()).also {
        players += it
        it.setMediaItems(ids.map { id -> RealMedia3QueueRig.song(id).toPlaybackMediaItem() }, index, 0L)
        it.prepare()
        TestMediaSource.settle(200)
    }

    private fun periodUids(timeline: Timeline): List<Any> = (0 until timeline.periodCount).map { timeline.getUidOfPeriod(it) }

    /**
     * The occurrence identity inside each period uid. Before any promotion the façade presents the occurrence uid itself; once
     * the uid aliasing path rebuilds the playlist, Media3 presents `Pair(windowUid, occurrenceUid)`. The identity is the same.
     */
    private fun periodIdentities(timeline: Timeline): List<Any> =
        periodUids(timeline).map { (it as? Pair<*, *>)?.second ?: it }

    /** Media3's own composition of a period uid (what its index HashMap is actually keyed by). */
    private fun composed(timeline: Timeline): List<Any> {
        val window = Timeline.Window()
        return (0 until timeline.periodCount).map {
            val period = timeline.getPeriod(it, Timeline.Period(), true)
            Pair.create(timeline.getWindow(period.windowIndex, window).uid, period.uid)
        }
    }

    private fun assertIndexConsistent(timeline: Timeline) {
        val period = Timeline.Period()
        for (i in 0 until timeline.periodCount) {
            val uid = timeline.getUidOfPeriod(i)
            assertEquals("uid -> index round trip at $i", i, timeline.getIndexOfPeriod(uid))
            assertEquals("getPeriodByUid at $i", i, timeline.getPeriodByUid(uid, period).windowIndex)
        }
    }

    // ------------------------------------------------------------------------------------------ the pathology, structurally

    @Test fun presentedPeriodUidHashesAreDistinctForALargeDuplicateHeavyQueue() {
        val n = 4_000
        val facade = SessionFacade(player((1..n).map { (it % 7 + 1).toLong() }, index = 3))
        val timeline = facade.currentTimeline
        assertEquals(n, timeline.windowCount)
        assertEquals(n, timeline.periodCount)
        val uids = periodUids(timeline)
        assertEquals("every period uid is a distinct occurrence", n, uids.toSet().size)
        assertTrue("hash spread, not one collision bin", uids.map { it.hashCode() }.toSet().size >= n - 4)
        assertTrue("Media3's Pair(window, period) composition must not cancel", composed(timeline).map { it.hashCode() }.toSet().size >= n - 4)
        assertIndexConsistent(timeline)
    }

    @Test fun thePhysicalPlayersOwnPeriodUidsDoCollapseToOneHash() {
        // Documents the adversary this change defends against: ExoPlayer's raw shape, composed the way Media3 composes it.
        val physical = player((1..500).map { it.toLong() })
        assertEquals("raw ExoPlayer uids cancel in the XOR hash", 1, composed(physical.currentTimeline).map { it.hashCode() }.toSet().size)
        assertTrue(composed(SessionFacade(physical).currentTimeline).map { it.hashCode() }.toSet().size >= 496)
    }

    @Test fun theIdentityWrapperCachesOnePresentedTimelinePerPhysicalTimeline() {
        val physical = player((1..300).map { it.toLong() })
        val wrapper = OccurrenceIdentityPlayer(physical, OccurrenceIdSource())
        assertSame("no per-read O(N) work between physical changes", wrapper.currentTimeline, wrapper.currentTimeline)
    }

    // ------------------------------------------------------------------------------------------ occurrences

    @Test fun duplicateSongOccurrencesHaveDistinctWindowAndPeriodIdentities() {
        val facade = SessionFacade(player(listOf(1, 2, 1, 1, 2), index = 2))
        val timeline = facade.currentTimeline
        val window = Timeline.Window()
        val windowUids = (0 until 5).map { timeline.getWindow(it, window).uid }
        assertEquals(5, windowUids.toSet().size)
        assertEquals(5, periodUids(timeline).toSet().size)
        assertEquals(listOf("1", "2", "1", "1", "2"), (0 until 5).map { timeline.getWindow(it, window).mediaItem.mediaId })
        assertIndexConsistent(timeline)
        assertEquals(2, facade.currentMediaItemIndex)
    }

    @Test fun identicalMetadataInTwoQueueSlotsStillGivesTwoOccurrences() {
        val physical = TestMediaSource.newPlayer(RuntimeEnvironment.getApplication()).also { players += it }
        val same: MediaItem = RealMedia3QueueRig.song(9).toPlaybackMediaItem()
        physical.setMediaItems(listOf(same, same, same), 0, 0L)
        physical.prepare()
        TestMediaSource.settle(200)
        val timeline = SessionFacade(physical).currentTimeline
        assertEquals(3, periodUids(timeline).toSet().size)
        assertIndexConsistent(timeline)
    }

    @Test fun anUnknownUidIsNotFound() {
        val timeline = SessionFacade(player(listOf(1, 2, 3))).currentTimeline
        assertEquals(C.INDEX_UNSET, timeline.getIndexOfPeriod(PeriodOccurrenceUid(9_999L, 0)))
        assertEquals(C.INDEX_UNSET, timeline.getIndexOfPeriod(Object()))
        val known = timeline.getUidOfPeriod(0) as PeriodOccurrenceUid
        assertEquals(C.INDEX_UNSET, timeline.getIndexOfPeriod(PeriodOccurrenceUid(known.occurrence, 5)))
    }

    // ------------------------------------------------------------------------------------------ stability across mutation

    private fun uidsByItem(facade: SessionFacade): Map<Any, Any> {
        val timeline = facade.currentTimeline
        val window = Timeline.Window()
        return (0 until timeline.windowCount).associate {
            val w = timeline.getWindow(it, window)
            w.uid to timeline.getUidOfPeriod(w.firstPeriodIndex)
        }
    }

    @Test fun insertsRemovalsAndMovesNeverReuseOrReassignIdentities() {
        val physical = player((1..8).map { it.toLong() }, index = 2)
        val facade = SessionFacade(physical)
        val before = uidsByItem(facade)

        physical.addMediaItem(3, RealMedia3QueueRig.song(1).toPlaybackMediaItem()) // a duplicate of song 1 in the middle
        TestMediaSource.settle(200)
        val afterInsert = uidsByItem(facade)
        assertEquals(9, afterInsert.size)
        assertTrue("every retained item keeps its identity", before.all { (w, p) -> afterInsert[w] == p })
        val inserted = afterInsert.keys - before.keys
        assertEquals(1, inserted.size)
        assertFalse("the new occurrence's uid was never presented before", before.values.contains(afterInsert[inserted.single()]))

        physical.removeMediaItem(0)
        TestMediaSource.settle(200)
        val afterRemove = uidsByItem(facade)
        assertEquals(8, afterRemove.size)
        assertTrue(afterRemove.all { (w, p) -> afterInsert[w] == p })

        physical.moveMediaItem(1, 6)
        TestMediaSource.settle(200)
        assertEquals("a move keeps every identity (the identity follows the item)", afterRemove, uidsByItem(facade))
        assertIndexConsistent(facade.currentTimeline)

        // A removed occurrence's id is never handed to a later one.
        val removedUid = afterInsert.entries.first { it.key !in afterRemove.keys }.value
        physical.addMediaItem(RealMedia3QueueRig.song(1).toPlaybackMediaItem())
        TestMediaSource.settle(200)
        assertFalse(uidsByItem(facade).values.contains(removedUid))
    }

    @Test fun shuffleChangesDoNotChangeIdentitiesOrBreakLookup() {
        val physical = player((1..40).map { (it % 5 + 1).toLong() }, index = 7)
        val facade = SessionFacade(physical)
        val before = uidsByItem(facade)
        physical.shuffleModeEnabled = true
        TestMediaSource.settle(200)
        assertEquals(before, uidsByItem(facade))
        assertIndexConsistent(facade.currentTimeline)
        physical.shuffleModeEnabled = false
        TestMediaSource.settle(200)
        assertEquals(before, uidsByItem(facade))
        assertEquals(7, facade.currentMediaItemIndex)
    }

    // ------------------------------------------------------------------------------------------ promotion

    @Test fun promotionKeepsPresentedIdentitiesCurrentItemAndLookup() {
        val ids = (1..60).map { (it % 9 + 1).toLong() }
        val p1 = player(ids, index = 11)
        val p2 = player(ids, index = 12)
        val facade = SessionFacade(p1)
        val before = periodIdentities(facade.currentTimeline)
        val beforeWindows = uidsByItem(facade).keys

        facade.replaceDelegate(p2, presentAsAutoTransition = true)
        TestMediaSource.settle(200)

        assertEquals(before, periodIdentities(facade.currentTimeline))
        assertEquals(beforeWindows, uidsByItem(facade).keys)
        assertEquals(12, facade.currentMediaItemIndex)
        assertEquals(ids[12].toString(), facade.currentMediaItem?.mediaId)
        assertEquals(60, facade.currentTimeline.windowCount)
        assertIndexConsistent(facade.currentTimeline)
    }
    @Test fun aLargeDuplicateHeavyQueueStaysCollisionFreeAfterPromotion() {
        // The audited pathology: the timeline REBUILT by the uid-aliasing path after a promotion keyed every period by one hash.
        val n = 3_000
        val ids = (1..n).map { (it % 11 + 1).toLong() }
        val p1 = player(ids, index = 5)
        val p2 = player(ids, index = 6)
        val facade = SessionFacade(p1)
        facade.replaceDelegate(p2, presentAsAutoTransition = true)
        TestMediaSource.settle(300)
        val timeline = facade.currentTimeline
        assertEquals(n, timeline.windowCount)
        assertTrue("distinct hash codes after promotion", periodUids(timeline).map { it.hashCode() }.toSet().size >= n - 4)
        assertIndexConsistent(timeline)
        facade.invalidateLogicalState() // a further state evaluation rebuilds the same way and must stay collision-free
        assertTrue(periodUids(facade.currentTimeline).map { it.hashCode() }.toSet().size >= n - 4)
    }


    @Test fun repeatedSuccessivePromotionsNeverCollideOrDrift() {
        val ids = (1..120).map { (it % 4 + 1).toLong() }
        var current = player(ids, index = 0)
        val facade = SessionFacade(current)
        val baseline = periodIdentities(facade.currentTimeline)
        for (step in 1..6) {
            val next = player(ids, index = step)
            facade.replaceDelegate(next, presentAsAutoTransition = true)
            TestMediaSource.settle(150)
            val timeline = facade.currentTimeline
            assertEquals("step $step identities unchanged", baseline, periodIdentities(timeline))
            assertEquals(120, periodIdentities(timeline).toSet().size)
            assertTrue("the uids Media3 keys its index by must not collide", periodUids(timeline).map { it.hashCode() }.toSet().size >= 116)
            assertEquals(step, facade.currentMediaItemIndex)
            assertIndexConsistent(timeline)
            current.release() // the retired player is released: nothing the façade presents may depend on it
            current = next
        }
    }

    @Test fun anInsertAfterPromotionGetsAFreshIdentityNeverAnAliasedOne() {
        val ids = listOf(1L, 2L, 3L)
        val p1 = player(ids)
        val p2 = player(ids, index = 1)
        val facade = SessionFacade(p1)
        val presented = periodIdentities(facade.currentTimeline)
        facade.replaceDelegate(p2, presentAsAutoTransition = true)
        TestMediaSource.settle(150)
        p2.addMediaItem(RealMedia3QueueRig.song(1).toPlaybackMediaItem())
        TestMediaSource.settle(200)
        val after = periodIdentities(facade.currentTimeline)
        assertEquals(4, after.toSet().size)
        assertEquals(presented, after.take(3))
        assertFalse(presented.contains(after.last()))
    }

    @Test fun aQueueThatDoesNotMirrorIsNotAliasedAndStillHasUniqueIdentities() {
        val p1 = player(listOf(1L, 2L, 3L))
        val p2 = player(listOf(1L, 2L, 9L), index = 1)
        val facade = SessionFacade(p1)
        val presented = periodIdentities(facade.currentTimeline)
        facade.replaceDelegate(p2)
        TestMediaSource.settle(150)
        val after = periodIdentities(facade.currentTimeline)
        assertEquals(3, after.toSet().size)
        assertTrue("a genuinely different queue gets fresh identities", after.none { presented.contains(it) })
        assertIndexConsistent(facade.currentTimeline)
    }

    @Test fun cancellingBeforePromotionLeavesTheOriginalIdentitiesUntouched() {
        val ids = (1..30).map { it.toLong() }
        val p1 = player(ids)
        val spare = player(ids)
        val facade = SessionFacade(p1)
        val before = periodIdentities(facade.currentTimeline)
        spare.release() // an abandoned secondary: never promoted
        TestMediaSource.settle(100)
        assertEquals(before, periodIdentities(facade.currentTimeline))
        assertSame(p1, facade.delegatePlayer)
    }

    @Test fun replacingWithTheSameDelegateIsANoOp() {
        val p1 = player(listOf(1L, 2L, 3L))
        val facade = SessionFacade(p1)
        val before = periodIdentities(facade.currentTimeline)
        facade.replaceDelegate(p1)
        assertEquals(before, periodIdentities(facade.currentTimeline))
        assertSame(p1, facade.delegatePlayer)
    }

    // ------------------------------------------------------------------------------------------ the abstraction itself

    /** A minimal physical timeline of [n] single-period windows with a caller-chosen raw period-uid shape. */
    private class FakeTimeline(private val n: Int, private val rawPeriodUid: (Any) -> Any) : Timeline() {
        val windowUids = List(n) { Object() }
        override fun getWindowCount() = n
        override fun getPeriodCount() = n
        override fun getWindow(windowIndex: Int, window: Window, defaultPositionProjectionUs: Long): Window =
            window.set(
                windowUids[windowIndex], MediaItem.EMPTY, null, C.TIME_UNSET, C.TIME_UNSET, C.TIME_UNSET,
                true, false, null, 0L, 1_000_000L, windowIndex, windowIndex, 0L,
            )

        override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period =
            period.set(
                if (setIds) periodIndex else null, if (setIds) rawPeriodUid(windowUids[periodIndex]) else null,
                periodIndex, 1_000_000L, 0L,
            )

        override fun getIndexOfPeriod(uid: Any): Int = windowUids.indexOfFirst { rawPeriodUid(it) == uid }
        override fun getUidOfPeriod(periodIndex: Int): Any = rawPeriodUid(windowUids[periodIndex])
    }

    /** Raw uids whose hashCode is a constant: the worst possible source. */
    private class ConstantHashUid(val id: Any) {
        override fun hashCode() = 7
        override fun equals(other: Any?) = other is ConstantHashUid && other.id === id
    }

    @Test fun anAdversarialSourceWithXorCancellingUidsIsDefused() {
        val shared = Object()
        val base = FakeTimeline(2_000) { w -> Pair.create(w, shared) } // ExoPlayer's shape: Pair(window uid, shared child uid)
        val ids = OccurrenceIdSource()
        val presented = OccurrenceTimeline(base, LongArray(2_000) { ids.next() })
        assertEquals("the adversary really cancels", 1, composed(base).map { it.hashCode() }.toSet().size)
        assertTrue(composed(presented).map { it.hashCode() }.toSet().size >= 1_996)
        assertIndexConsistent(presented)
    }

    @Test fun anAdversarialSourceWithConstantHashUidsDoesNotInfluenceThePresentedHashes() {
        val base = FakeTimeline(2_000) { w -> ConstantHashUid(w) }
        val ids = OccurrenceIdSource()
        val presented = OccurrenceTimeline(base, LongArray(2_000) { ids.next() })
        assertEquals("the source is fully colliding", 1, periodUids(base).map { it.hashCode() }.toSet().size)
        assertTrue(periodUids(presented).map { it.hashCode() }.toSet().size >= 1_996)
        assertIndexConsistent(presented)
    }

    @Test fun occurrenceUidsHaveValueEqualityOnTheirPairOnly() {
        assertEquals(PeriodOccurrenceUid(5, 0), PeriodOccurrenceUid(5, 0))
        assertEquals(PeriodOccurrenceUid(5, 0).hashCode(), PeriodOccurrenceUid(5, 0).hashCode())
        assertNotEquals(PeriodOccurrenceUid(5, 0), PeriodOccurrenceUid(5, 1))
        assertNotEquals(PeriodOccurrenceUid(5, 0), PeriodOccurrenceUid(6, 0))
    }

    @Test fun sequentialOccurrenceIdsSpreadAcrossTheHashSpace() {
        val hashes = (1L..100_000L).map { PeriodOccurrenceUid(it, 0).hashCode() }
        assertTrue(hashes.toSet().size >= 99_900)
        assertTrue("not clustered in one half of the int range", hashes.count { it < 0 } in 49_000..51_000)
    }
}
