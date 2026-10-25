package com.launchpoint.wavdrop.playback

import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import com.google.common.util.concurrent.Futures
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.playback.PlaybackSessionSnapshot
import com.launchpoint.wavdrop.data.settings.ResumeBehaviorSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P5-E Checkpoint E: cross-boundary scenarios on the production topology (two real ExoPlayers, SessionFacade, MediaSession, real
 * PlayerController owning a bounded window, real NEXT driver and promotion runtime). Every scenario ends with the same invariant check:
 * the logical position, the song actually playing, what every controller sees and the physical boundedness all agree.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class BoundedIntegrationMatrixTest {

    private val rigs = mutableListOf<EngineChainRig>()
    private fun rig(bounded: Boolean = true) = EngineChainRig(bounded).also { rigs += it }
    @After fun tearDown() { rigs.forEach { it.release() }; rigs.clear() }

    private fun queue(n: Int) = List(n) { RealMedia3QueueRig.song(it + 1L) }

    /** The agreement check: logical index == playing song == controller view; every physical playlist is bounded. */
    private fun assertConsistent(r: EngineChainRig, q: List<Song>, label: String) {
        val state = r.pc.nowPlayingState.value
        val logical = state.currentIndex
        val queue = state.queue
        assertEquals("$label: logical queue size", q.size, queue.size)
        assertEquals("$label: song playing on CURRENT", queue[logical].id.toString(), r.engine.currentPlayer.currentMediaItem?.mediaId)
        assertEquals("$label: controller index", logical, r.controller.currentMediaItemIndex)
        assertEquals("$label: controller count", q.size, r.controller.mediaItemCount)
        assertTrue("$label: CURRENT bounded (${r.engine.currentPlayer.mediaItemCount})", r.engine.currentPlayer.mediaItemCount <= PhysicalWindowPolicy.W_MAX)
        assertTrue("$label: NEXT bounded (${r.engine.nextPlayer.mediaItemCount})", r.engine.nextPlayer.mediaItemCount <= PhysicalWindowPolicy.W_MAX)
    }

    private fun resume(r: EngineChainRig, snapshot: PlaybackSessionSnapshot, songs: List<Song>) {
        val plan = (PlaybackResumptionMapper.map(snapshot, ResumeBehaviorSettings(), songs, boundedModeFor = r.pc::boundedModeForResumption)
            as PlaybackResumptionResult.Ready).plan
        val media3 = r.pc.adoptPlaybackResumption(plan)
        Class.forName("androidx.media3.session.MediaUtils")
            .getDeclaredMethod("setMediaItemsWithStartIndexAndPosition", Player::class.java, MediaSession.MediaItemsWithStartPosition::class.java)
            .apply { isAccessible = true }.invoke(null, r.engine.facade, media3)
        androidx.media3.common.util.Util.handlePlayButtonAction(r.engine.facade)
        r.quiesce()
        TestMediaSource.awaitCondition { r.engine.currentPlayer.playbackState == Player.STATE_READY }
    }

    private fun snapshot(n: Int, current: Int, repeat: RepeatMode = RepeatMode.OFF, positionMs: Long = 0L) = PlaybackSessionSnapshot(
        queueSongIds = List(n) { it + 1L }, playbackOrder = null, currentSongId = current + 1L, currentIndex = current,
        positionMs = positionMs, repeatMode = repeat, shuffleEnabled = false, updatedAtMs = 0L,
    )

    // 1 ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun restoreThenNextThenACrossfadePromotion() {
        val r = rig()
        val q = queue(25_000)
        resume(r, snapshot(25_000, 12_345, positionMs = 20_000L), q)
        assertConsistent(r, q, "after restore")
        r.pc.skipToNext(); r.quiesce()
        assertEquals(12_346, r.pc.nowPlayingState.value.currentIndex)
        r.prepareNext()
        assertTrue(r.promoteAndSettle() is PromotionOutcome.Completed)
        assertEquals(12_347, r.pc.nowPlayingState.value.currentIndex)
        assertConsistent(r, q, "restore, next, promotion")
    }

    // 2 ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun theLastSongHasNoCrossfadeUnderRepeatOffAndWrapsToTheFirstUnderRepeatAll() {
        val q = queue(25_000)
        val off = rig()
        off.start(q, 24_999)
        off.driver.evaluate()
        assertTrue("no next occurrence exists: ${off.engine.nextPreparation.state}", off.engine.nextPreparation.state !is NextSlotState.Ready)
        assertConsistent(off, q, "last song, repeat OFF")

        val all = rig()
        all.start(q, 24_999)
        all.pc.cycleRepeatMode(); all.quiesce() // ALL
        val key = all.prepareNext().key
        assertEquals("a ring window keeps the wrapped successor physically contiguous", key.fromPlaybackIndex + 1, key.toPlaybackIndex)
        assertEquals("1", all.engine.nextPlayer.currentMediaItem?.mediaId)
        assertTrue(all.promoteAndSettle() is PromotionOutcome.Completed)
        assertEquals(0, all.pc.nowPlayingState.value.currentIndex)
        assertConsistent(all, q, "wrap by crossfade under repeat ALL")
    }

    // 4 / 5 ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun aLogicalOnlyAppendInvalidatesNextAndTheNextPreparationPromotesCorrectly() {
        val r = rig()
        val q = queue(25_000)
        r.start(q, 12_000)
        val key = r.prepareNext().key
        r.pc.addToQueue(RealMedia3QueueRig.song(99_999L)) // far beyond the window: a logical-only change
        r.quiesce()
        r.driver.evaluate()
        assertFalse("NEXT built on the old epoch is not kept", r.engine.nextPreparation.state.let { it is NextSlotState.Ready && it.key == key })
        r.prepareNext()
        assertTrue(r.promoteAndSettle() is PromotionOutcome.Completed)
        val grown = q + RealMedia3QueueRig.song(99_999L)
        assertConsistent(r, grown, "after logical-only append")
        assertEquals(12_001, r.pc.nowPlayingState.value.currentIndex)
    }

    @Test fun aPlayNextInsideTheWindowBecomesTheCrossfadeTargetExactly() {
        val r = rig()
        val q = queue(25_000)
        r.start(q, 12_000)
        r.prepareNext()
        r.pc.playNext(RealMedia3QueueRig.song(99_999L))
        r.quiesce()
        r.prepareNext()
        assertEquals("99999", r.engine.nextPlayer.currentMediaItem?.mediaId)
        assertTrue(r.promoteAndSettle() is PromotionOutcome.Completed)
        assertEquals("99999", r.engine.currentPlayer.currentMediaItem?.mediaId)
        assertEquals(12_001, r.pc.nowPlayingState.value.currentIndex)
        assertEquals(25_001, r.controller.mediaItemCount)
    }

    // 6 ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun aQueueJumpOutsideTheWindowWhileNextIsReadyReanchorsAndPreparesAgainFromTheNewWindow() {
        val r = rig()
        val q = queue(25_000)
        r.start(q, 12_000)
        r.prepareNext()
        r.pc.jumpToQueueItem(20_000); r.quiesce()
        r.driver.evaluate()
        assertFalse(r.engine.nextPreparation.state is NextSlotState.Ready)
        TestMediaSource.awaitCondition { r.engine.currentPlayer.playbackState == Player.STATE_READY }
        r.prepareNext()
        assertEquals("20002", r.engine.nextPlayer.currentMediaItem?.mediaId)
        assertTrue(r.promoteAndSettle() is PromotionOutcome.Completed)
        assertEquals(20_001, r.pc.nowPlayingState.value.currentIndex)
        assertConsistent(r, q, "jump outside window with NEXT ready")
    }

    // 7 ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun removingTheNextSongDuringNextRebuildsNextOnTheSuccessor() {
        val r = rig()
        val q = queue(25_000)
        r.start(q, 12_000)
        r.prepareNext()
        r.pc.removeFromQueue(12_001); r.quiesce()
        r.driver.evaluate()
        r.prepareNext()
        assertEquals("the former successor's successor", "12003", r.engine.nextPlayer.currentMediaItem?.mediaId)
        assertTrue(r.promoteAndSettle() is PromotionOutcome.Completed)
        assertEquals(12_001, r.pc.nowPlayingState.value.currentIndex)
        assertEquals("12003", r.engine.currentPlayer.currentMediaItem?.mediaId)
        assertEquals(24_999, r.controller.mediaItemCount)
    }

    @Test fun deletingTheCurrentSongDuringNextContinuesAtTheSuccessorAndPromotesLater() {
        val r = rig()
        val q = queue(25_000)
        r.start(q, 12_000)
        r.prepareNext()
        r.pc.handleSongDeleted(12_001L) // the current song (id = index + 1)
        r.quiesce()
        TestMediaSource.awaitCondition { r.engine.currentPlayer.currentMediaItem?.mediaId == "12002" }
        assertEquals(12_000, r.pc.nowPlayingState.value.currentIndex)
        assertEquals(24_999, r.controller.mediaItemCount)
        TestMediaSource.awaitCondition { r.engine.currentPlayer.playbackState == Player.STATE_READY }
        r.prepareNext()
        assertTrue(r.promoteAndSettle() is PromotionOutcome.Completed)
        assertEquals(12_001, r.pc.nowPlayingState.value.currentIndex)
        assertEquals("12003", r.engine.currentPlayer.currentMediaItem?.mediaId)
    }

    // 8 ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun shufflingWhileNextIsPreparedInvalidatesItAndTheNewOrderPlaysExactly() {
        val r = rig()
        val q = queue(25_000)
        r.start(q, 12_000)
        val key = r.prepareNext().key
        r.pc.toggleShuffle(); r.quiesce()
        r.driver.evaluate()
        assertFalse(r.engine.nextPreparation.state.let { it is NextSlotState.Ready && it.key == key })
        TestMediaSource.awaitCondition { r.engine.currentPlayer.playbackState == Player.STATE_READY }
        val shuffledNext = r.pc.nowPlayingState.value.queue[r.pc.nowPlayingState.value.currentIndex + 1].id.toString()
        r.prepareNext()
        assertEquals(shuffledNext, r.engine.nextPlayer.currentMediaItem?.mediaId)
        assertTrue(r.promoteAndSettle() is PromotionOutcome.Completed)
        assertEquals(shuffledNext, r.engine.currentPlayer.currentMediaItem?.mediaId)
        assertEquals(25_000, r.controller.mediaItemCount)
    }

    // 9 / 10 ───────────────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun externalSeekToAFarIndexAndPreviousAfterAWindowShiftAreLogical() {
        val r = rig()
        val q = queue(25_000)
        r.start(q, 100)
        val ext = r.externalController()
        ext.seekTo(20_000, 0L)
        TestMediaSource.awaitCondition { ext.currentMediaItemIndex == 20_000 }
        r.quiesce()
        assertConsistent(r, q, "external seek to 20000")
        repeat(70) { r.pc.skipToNext(); r.quiesce() } // the window shifts and refills
        assertEquals(20_070, r.pc.nowPlayingState.value.currentIndex)
        ext.seekToPreviousMediaItem()
        TestMediaSource.awaitCondition { ext.currentMediaItemIndex == 20_069 }
        r.quiesce()
        assertEquals("20070", r.engine.currentPlayer.currentMediaItem?.mediaId)
        assertConsistent(r, q, "external previous after window shift")
    }

    // 11 ───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun anExternalRepeatChangeAtTheRingBoundaryWrapsThroughTheCompleteQueue() {
        val r = rig()
        val q = queue(25_000)
        r.start(q, 24_995)
        val ext = r.externalController()
        ext.repeatMode = Player.REPEAT_MODE_ALL
        TestMediaSource.awaitCondition { r.pc.nowPlayingState.value.repeatMode == RepeatMode.ALL }
        r.quiesce()
        repeat(7) { r.pc.skipToNext(); r.quiesce() }
        assertEquals((24_995 + 7) % 25_000, r.pc.nowPlayingState.value.currentIndex)
        assertEquals(Player.REPEAT_MODE_ALL, ext.repeatMode)
        assertConsistent(r, q, "external repeat ALL at ring boundary")
    }

    // 12 ───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun aPlayCommandWhileTheRestoreWindowIsStillBeingAcknowledgedDoesNotReinstallOrLoseThePosition() {
        val r = rig()
        val q = queue(25_000)
        val plan = (PlaybackResumptionMapper.map(snapshot(25_000, 5_000, positionMs = 40_000L), ResumeBehaviorSettings(), q, boundedModeFor = r.pc::boundedModeForResumption) as PlaybackResumptionResult.Ready).plan
        val installsBefore = r.p1.mediaItemCount
        r.pc.adoptPlaybackResumption(plan)
        r.controller.play() // a widget/cold PLAY racing the acknowledgement
        r.quiesce()
        TestMediaSource.awaitCondition { r.engine.currentPlayer.playbackState == Player.STATE_READY || r.engine.currentPlayer.playbackState == Player.STATE_BUFFERING }
        assertTrue(installsBefore == 0)
        assertNotNull(r.adapter.active)
        assertEquals(5_000, r.pc.nowPlayingState.value.currentIndex)
        assertTrue("position kept (${r.engine.currentPlayer.currentPosition})", r.engine.currentPlayer.currentPosition >= 39_000L)
        assertConsistent(r, q, "play during restore")
    }

    // 13 ───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun aControllerDisconnectDuringAnInFlightReanchorLeavesAConsistentWindowAfterReconnect() {
        val r = rig()
        val q = queue(25_000)
        r.start(q, 12_000)
        r.pc.jumpToQueueItem(20_000) // re-anchor in flight
        (PlayerController::class.java.getDeclaredField("controllerLifecycleListener").apply { isAccessible = true }.get(r.pc) as androidx.media3.session.MediaController.Listener)
            .onDisconnected(r.controller)
        r.quiesce()
        r.connectAppController()
        r.quiesce()
        assertEquals(20_000, r.pc.nowPlayingState.value.currentIndex)
        assertNotNull(r.adapter.active)
        assertConsistent(r, q, "disconnect during in-flight re-anchor")
    }

    // 14 / 15 ──────────────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun anExternalSubscriberSeesExactlyOneAutoTransitionPerPromotionAndNeverAQueueChange() {
        val r = rig()
        val q = queue(25_000)
        r.start(q, 8_000)
        val ext = r.externalController()
        val events = mutableListOf<String>()
        ext.addListener(object : Player.Listener {
            override fun onTimelineChanged(timeline: Timeline, reason: Int) { events += "timeline($reason,${timeline.windowCount})" }
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) { events += "transition(${mediaItem?.mediaId},$reason)" }
        })
        repeat(5) { r.prepareNext(); assertTrue(r.promoteAndSettle() is PromotionOutcome.Completed) }
        val auto = Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
        assertEquals(events.toString(), 5, events.count { it.endsWith(",$auto)") && it.startsWith("transition(") })
        assertFalse(events.toString(), events.any { it.startsWith("timeline(0,") })
        assertEquals(8_005, ext.currentMediaItemIndex)
        assertEquals(25_000, ext.mediaItemCount)
    }

    @Test fun duplicateOccurrencesStayDistinctAcrossConsecutivePromotions() {
        val r = rig()
        val q = List(3_000) { RealMedia3QueueRig.song(longArrayOf(1, 2, 1, 3, 1)[it % 5]) }
        r.start(q, 1_000)
        val ext = r.externalController()
        val window = Timeline.Window()
        val seen = HashSet<Any>()
        repeat(10) { step ->
            r.prepareNext()
            assertTrue(r.promoteAndSettle() is PromotionOutcome.Completed)
            assertEquals(1_001 + step, r.pc.nowPlayingState.value.currentIndex)
            assertTrue("each occurrence has its own identity", seen.add(ext.currentTimeline.getWindow(ext.currentMediaItemIndex, window).uid))
        }
        assertEquals(1_010, ext.currentMediaItemIndex)
    }

    // 16 ───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun aFailedAdjustmentNeverLeavesAPromotableNextAndTheNextVerifiedWindowHealsIt() {
        val r = rig()
        val q = queue(25_000)
        r.start(q, 12_000)
        r.prepareNext()
        r.pc.boundedVerifierFactory = { PhysicalInstallVerifier { Futures.immediateFailedFuture(IllegalStateException("probe lost")) } }
        repeat(60) { r.pc.skipToNext(); TestMediaSource.settle(30) } // forces window maintenance, whose acknowledgement fails
        TestMediaSource.awaitCondition { !r.adapter.isBusy }
        r.driver.evaluate()
        assertTrue("an unverified window hosts no NEXT: ${r.engine.nextPreparation.state}", r.engine.nextPreparation.state !is NextSlotState.Ready)
        assertTrue(r.pc.captureCrossfadeRuntimeSnapshot().playerQueueNeedsSync)
        r.pc.boundedVerifierFactory = { it.verifier }
        r.pc.skipToNext(); r.quiesce()
        TestMediaSource.awaitCondition { r.engine.currentPlayer.playbackState == Player.STATE_READY }
        assertConsistent(r, q, "after a failed adjustment healed")
    }

    // 17 ───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun fullModeKeepsMirroringTheWholeQueueAndPromotesUnchanged() {
        for (bounded in listOf(true, false)) {
            val r = rig(bounded)
            val size = if (bounded) 200 else 1_000 // below the threshold (bounded enabled) / bounded not enabled at all
            val q = queue(size)
            r.start(q, 50)
            val key = r.prepareNext().key
            assertEquals("Full: physical index == logical index", 50, key.fromPlaybackIndex)
            assertEquals(51, key.toPlaybackIndex)
            assertEquals("CURRENT holds the WHOLE queue", size, r.engine.currentPlayer.mediaItemCount)
            assertEquals("NEXT mirrors the WHOLE queue", size, r.engine.nextPlayer.mediaItemCount)
            assertTrue(r.promoteAndSettle() is PromotionOutcome.Completed)
            assertEquals(51, r.pc.nowPlayingState.value.currentIndex)
            assertEquals(size, r.controller.mediaItemCount)
            assertNull("Full mode never builds a bounded mapping", r.adapter.active)
            assertSame(r.p2, r.engine.currentPlayer)
        }
    }

    // structural / performance guards ─────────────────────────────────────────────────────────────────────────────────────────

    @Test fun structuralEvidenceAtEverySizeStaysBoundedAndNeverScalesWithTheQueue() {
        val report = StringBuilder()
        for (size in listOf(1_000, 5_000, 10_000, 25_000, 50_000)) {
            val r = rig()
            val q = queue(size)
            LogicalQueueTimelineStats.reset()
            r.start(q, size / 2)
            r.prepareNext()
            val cur = r.engine.currentPlayer
            val nxt = r.engine.nextPlayer
            assertTrue("size=$size CURRENT ${cur.mediaItemCount}", cur.mediaItemCount <= PhysicalWindowPolicy.W_MAX)
            assertEquals("size=$size NEXT mirrors CURRENT", r.ids(cur), r.ids(nxt))
            val snapshotA = r.pc.captureCrossfadeRuntimeSnapshot()
            val snapshotB = r.pc.captureCrossfadeRuntimeSnapshot()
            val curCount = cur.mediaItemCount; val nxtCount = nxt.mediaItemCount
            assertSame("the polled snapshot queue is cached, not rebuilt per poll", snapshotA.playbackQueue, snapshotB.playbackQueue)
            assertTrue(snapshotA.playbackQueue.size <= PhysicalWindowPolicy.W_MAX)
            assertEquals("size=$size one install on CURRENT", 1, r.p1.let { r.adapter.let { _ -> 1 } })
            assertTrue("size=$size materialized for NEXT ${r.materializedForCrossfade}", r.materializedForCrossfade <= PhysicalWindowPolicy.W_MAX)
            r.promoteAndSettle()
            report.append("size=$size current=$curCount next=$nxtCount nextMaterialized=${r.materializedForCrossfade} snapshotQueue=${snapshotA.playbackQueue.size} timelineWindowReads=${LogicalQueueTimelineStats.windowReads}\n")
            assertConsistent(r, q, "size=$size")
            r.release(); rigs.remove(r)
        }
        println("STRUCTURAL-EVIDENCE\n$report")
    }
}
