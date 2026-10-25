package com.launchpoint.wavdrop.playback

import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.SettableFuture
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * External index-bearing seeks (real MediaControllers, no app hint) on the production topology while the queue is bounded. The
 * controller sends a LOGICAL index and a position; both must arrive: the exact logical occurrence AND the physical playback position
 * are asserted. (Robolectric freezes the playback clock, so a seek position is observed essentially as requested.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class ExternalIndexSeekTest {

    private val rigs = mutableListOf<EngineChainRig>()
    private fun rig(bounded: Boolean = true) = EngineChainRig(bounded).also { rigs += it }
    @After fun tearDown() { rigs.forEach { it.release() }; rigs.clear() }

    private fun queue(n: Int) = List(n) { RealMedia3QueueRig.song(it + 1L) }

    private var installs = 0
    private fun count(r: EngineChainRig) {
        installs = 0
        val l = object : Player.Listener {
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) installs++
            }
        }
        r.p1.addListener(l); r.p2.addListener(l)
    }

    private fun MediaController.awaitIndex(i: Int) = TestMediaSource.awaitCondition { currentMediaItemIndex == i }

    private fun assertAt(r: EngineChainRig, ext: MediaController, index: Int, minPositionMs: Long, label: String) {
        r.quiesce()
        TestMediaSource.awaitCondition { r.engine.currentPlayer.currentPosition >= minPositionMs }
        assertEquals("$label: logical occurrence (app)", index, r.pc.nowPlayingState.value.currentIndex)
        assertEquals("$label: logical occurrence (controller)", index, ext.currentMediaItemIndex)
        assertEquals("$label: song actually playing", (index + 1).toString(), r.engine.currentPlayer.currentMediaItem?.mediaId)
        val position = r.engine.currentPlayer.currentPosition
        assertTrue("$label: physical position $position >= $minPositionMs", position >= minPositionMs)
        assertTrue("$label: physical position $position stays near the request", position < minPositionMs + 20_000L)
        assertTrue("$label: bounded", r.engine.currentPlayer.mediaItemCount <= PhysicalWindowPolicy.W_MAX)
    }

    @Test fun seekToIndex20000At45000LandsOnThatOccurrenceAtThatPosition() {
        val r = rig(); r.start(queue(25_000), 100)
        val ext = r.externalController()
        ext.seekTo(20_000, 45_000L)
        ext.awaitIndex(20_000)
        assertAt(r, ext, 20_000, 45_000L, "20000@45s")
    }

    @Test fun anInWindowTargetIsAMappedPhysicalSeekWithNoReinstall() {
        val r = rig(); r.start(queue(25_000), 12_000)
        val ext = r.externalController()
        count(r)
        ext.seekTo(12_010, 30_000L)
        ext.awaitIndex(12_010)
        assertAt(r, ext, 12_010, 30_000L, "in-window")
        assertEquals("a mapped seek installs nothing", 0, installs)
    }

    @Test fun anOutOfWindowTargetIsOneBoundedReanchorAtThePosition() {
        val r = rig(); r.start(queue(25_000), 12_000)
        val ext = r.externalController()
        count(r)
        ext.seekTo(18_000, 30_000L)
        ext.awaitIndex(18_000)
        assertAt(r, ext, 18_000, 30_000L, "out-of-window")
        assertEquals("exactly one bounded re-anchor", 1, installs)
    }

    @Test fun aDefaultPositionSeekIsDistinctFromAnExplicitPosition() {
        val r = rig(); r.start(queue(25_000), 12_000)
        val ext = r.externalController()
        ext.seekTo(12_000, 20_000L) // current item, explicit position
        TestMediaSource.awaitCondition { r.engine.currentPlayer.currentPosition >= 20_000L }
        ext.seekToDefaultPosition(12_005)
        ext.awaitIndex(12_005)
        r.quiesce()
        assertEquals(12_005, r.pc.nowPlayingState.value.currentIndex)
        assertTrue("default position is the start of the item (${r.engine.currentPlayer.currentPosition})", r.engine.currentPlayer.currentPosition < 5_000L)
        ext.seekToDefaultPosition(12_005) // the current item: restart it
        TestMediaSource.awaitCondition { r.engine.currentPlayer.currentPosition < 5_000L }
    }

    @Test fun seekingTheCurrentItemAtAPositionIsAnInItemSeekNotANavigation() {
        val r = rig(); r.start(queue(25_000), 12_000)
        val ext = r.externalController()
        val generation = r.pc.resumptionAuthority()
        count(r)
        ext.seekTo(12_000, 40_000L)
        assertAt(r, ext, 12_000, 40_000L, "current item")
        assertEquals(generation, r.pc.resumptionAuthority())
        assertEquals(0, installs)
    }

    @Test fun duplicateOccurrencesAtDifferentIndicesAreEachExact() {
        val r = rig()
        val q = List(3_000) { RealMedia3QueueRig.song(longArrayOf(1, 2, 1, 3, 1)[it % 5]) }
        r.start(q, 1_000)
        val ext = r.externalController()
        ext.seekTo(1_002, 7_000L); ext.awaitIndex(1_002)
        r.quiesce()
        assertEquals(1_002, r.pc.nowPlayingState.value.currentIndex)
        assertTrue(r.engine.currentPlayer.currentPosition >= 7_000L)
        ext.seekTo(1_004, 9_000L); ext.awaitIndex(1_004)
        r.quiesce()
        assertEquals("a different occurrence of the same song", 1_004, r.pc.nowPlayingState.value.currentIndex)
        assertEquals("1", r.engine.currentPlayer.currentMediaItem?.mediaId)
        assertTrue(r.engine.currentPlayer.currentPosition >= 9_000L)
    }

    @Test fun repeatAllWraparoundSeekToTheFirstItemKeepsThePosition() {
        val r = rig(); r.start(queue(25_000), 24_999)
        r.pc.cycleRepeatMode(); r.quiesce() // ALL
        val ext = r.externalController()
        ext.seekTo(0, 5_000L); ext.awaitIndex(0)
        assertAt(r, ext, 0, 5_000L, "wrap to 0")
        ext.seekTo(24_999, 8_000L); ext.awaitIndex(24_999)
        assertAt(r, ext, 24_999, 8_000L, "wrap back to the last")
    }

    @Test fun rapidSuccessiveSeeksEndOnTheLastOne() {
        val r = rig(); r.start(queue(25_000), 100)
        val ext = r.externalController()
        ext.seekTo(15_000, 1_000L)
        ext.seekTo(16_000, 2_000L)
        ext.seekTo(14_000, 3_000L)
        ext.awaitIndex(14_000)
        assertAt(r, ext, 14_000, 3_000L, "latest wins")
    }

    // ── pending verification ───────────────────────────────────────────────────────────────────────────────────────────────

    private class Held {
        val pending = mutableListOf<Pair<PlanToken, SettableFuture<PhysicalObservation>>>()
        val verifier = PhysicalInstallVerifier { token -> SettableFuture.create<PhysicalObservation>().also { pending += token to it } }
        fun releaseAll(r: EngineChainRig) {
            pending.toList().forEach { (token, f) -> f.set(PhysicalObservation.observe(r.engine.currentPlayer, token)) }
            pending.clear()
        }
    }

    @Test fun anAcknowledgementDelayedAfterAnOutOfWindowSeekNeverSeeksAnUnverifiedMapping() {
        val r = rig(); r.start(queue(25_000), 100)
        val ext = r.externalController()
        val held = Held()
        r.pc.boundedVerifierFactory = { held.verifier }
        ext.seekTo(20_000, 45_000L)
        ext.awaitIndex(20_000) // the logical selection is authoritative at once
        assertTrue("the install awaits its acknowledgement", r.adapter.isBusy)
        assertNull("no mapping is published before the acknowledgement", r.adapter.active)
        // a second seek while unverified must also not seek against a mapping that does not exist
        ext.seekTo(20_003, 12_000L)
        ext.awaitIndex(20_003)
        held.releaseAll(r)
        r.quiesce()
        held.releaseAll(r)
        r.quiesce()
        r.pc.boundedVerifierFactory = { it.verifier }
        assertAt(r, ext, 20_003, 12_000L, "after delayed acknowledgement")
    }

    @Test fun aSeekSupersededByAnotherSelectionIsNeverResurrectedByItsLateAcknowledgement() {
        val r = rig(); r.start(queue(25_000), 100)
        val ext = r.externalController()
        val held = Held()
        r.pc.boundedVerifierFactory = { held.verifier }
        ext.seekTo(20_000, 45_000L)
        ext.awaitIndex(20_000)
        r.pc.boundedVerifierFactory = { it.verifier }
        r.pc.jumpToQueueItem(5_000)
        held.releaseAll(r) // the stale acknowledgement of the superseded seek
        r.quiesce()
        assertAt(r, ext, 5_000, 0L, "superseded")
        assertTrue(r.engine.currentPlayer.currentPosition < 5_000L)
    }

    @Test fun aPauseDuringAPendingReanchorIsNotOverriddenByTheOlderSeek() {
        val r = rig(); r.start(queue(25_000), 100)
        val ext = r.externalController()
        val held = Held()
        r.pc.boundedVerifierFactory = { held.verifier }
        ext.seekTo(20_000, 45_000L)
        ext.awaitIndex(20_000)
        ext.pause()
        TestMediaSource.awaitCondition { !r.engine.currentPlayer.playWhenReady }
        held.releaseAll(r)
        r.pc.boundedVerifierFactory = { it.verifier }
        r.quiesce()
        TestMediaSource.settle(150)
        assertFalse("the newer pause wins", r.engine.currentPlayer.playWhenReady)
        assertAt(r, ext, 20_000, 45_000L, "paused re-anchor")
    }

    @Test fun aRejectedOrFailedVerificationPublishesNoMappingAndTheNextSeekHeals() {
        val r = rig(); r.start(queue(25_000), 100)
        val ext = r.externalController()
        r.pc.boundedVerifierFactory = { PhysicalInstallVerifier { Futures.immediateFailedFuture(IllegalStateException("probe lost")) } }
        ext.seekTo(20_000, 45_000L)
        ext.awaitIndex(20_000)
        TestMediaSource.awaitCondition { !r.adapter.isBusy }
        assertNull(r.adapter.active)
        assertTrue(r.adapter.state is WindowInstallState.NeedsReinstall)
        r.pc.boundedVerifierFactory = { it.verifier }
        ext.seekTo(21_000, 10_000L)
        ext.awaitIndex(21_000)
        assertAt(r, ext, 21_000, 10_000L, "healed")
        assertNotNull(r.adapter.active)
    }

    // ── Full mode ──────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun fullModeKeepsItsSeekBehavior() {
        val r = rig(bounded = false)
        r.start(queue(1_000), 100)
        val ext = r.externalController()
        ext.seekTo(500, 20_000L)
        ext.awaitIndex(500)
        TestMediaSource.awaitCondition { r.engine.currentPlayer.currentPosition >= 20_000L }
        assertEquals("Full: the whole queue stays physical", 1_000, r.engine.currentPlayer.mediaItemCount)
        assertEquals(500, r.engine.currentPlayer.currentMediaItemIndex)
        assertEquals("501", r.engine.currentPlayer.currentMediaItem?.mediaId)
        assertNull(r.adapter.active)
    }
}
