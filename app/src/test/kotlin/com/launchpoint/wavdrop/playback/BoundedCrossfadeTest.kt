package com.launchpoint.wavdrop.playback

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.media3.common.AudioAttributes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.playback.PlaybackSessionRepository
import com.launchpoint.wavdrop.data.repository.PlayEventWriter
import com.launchpoint.wavdrop.data.settings.AppSettingsRepository
import com.launchpoint.wavdrop.data.settings.ResumeBehaviorSettingsRepository
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * P5-E Checkpoint C on the PRODUCTION topology: two real ExoPlayers behind [PlayerEngine], the session façade presenting the logical
 * queue, the real [PlayerController] owning the bounded window through [EnginePhysicalPort], and the real NEXT-slot driver and
 * promotion runtime (driven by a fake scheduler/clock). Bounded is enabled for the test only; the committed gate stays false.
 * No claim about audio: ExoPlayer here is codec-free.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class BoundedCrossfadeTest {

    private class Writer : PlayEventWriter {
        override suspend fun recordPlay(songId: Long, contentUri: String, listenedMs: Long, durationMs: Long) = Unit
        override suspend fun recordSkip(songId: Long, contentUri: String, durationMs: Long) = Unit
        override suspend fun recordListenStart(songId: Long, contentUri: String) = Unit
    }

    private class FakeScheduler : CrossfadeTimingScheduler {
        override fun postDelayed(delayMs: Long, block: () -> Unit) = Unit
        override fun cancelAll() = Unit
    }

    private inner class Rig {
        val context: Context = RuntimeEnvironment.getApplication()
        val sessionId = 4242
        val p1: ExoPlayer = TestMediaSource.newPlayer(context, sessionId)
        val p2: ExoPlayer = TestMediaSource.newPlayer(context, sessionId)
        val engine = PlayerEngine(context, p1, p2, sessionId, AudioAttributes.DEFAULT)
        val session = androidx.media3.session.MediaSession.Builder(context, engine.facade).setId("bcf-${counter++}").setCallback(AckCallback()).build()
        val controller: MediaController
        val port = EnginePhysicalPort(engine)
        private fun store(name: String) = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + Job()),
            produceFile = { File.createTempFile(name, ".preferences_pb").also { f -> f.delete(); f.deleteOnExit() } },
        )
        val pc = PlayerController(
            context,
            StatsTracker(Writer()).also { it.scope = CoroutineScope(Dispatchers.Unconfined); it.clock = { 0L } },
            PlaybackSessionRepository(store("sessionstore")), ResumeBehaviorSettingsRepository(store("resumestore")), AppSettingsRepository(store("appstore")),
        )
        var now = 1_000L
        val driver = NextSlotPreparationDriver(
            preparation = engine.nextPreparation,
            snapshotProvider = { pc.captureCrossfadeRuntimeSnapshot() },
            materialize = { songs -> pc.materializePlaybackMediaItemsForCrossfade(songs) },
            configuredDurationMsProvider = { 6_000L },
            currentDurationMsProvider = { engine.currentPlayer.duration.takeIf { it > 0L } },
            scheduler = FakeScheduler(),
        )
        val runtime = CrossfadePromotionRuntime(
            engine = engine,
            snapshotProvider = { pc.captureCrossfadeRuntimeSnapshot() },
            configuredDurationMsProvider = { 6_000L },
            scheduler = FakeScheduler(),
            clock = { now },
        )

        init {
            val hints = android.os.Bundle().apply { putBoolean(ExternalTransportPolicy.APP_CONTROLLER_HINT, true) }
            val future = MediaController.Builder(context, session.token).setConnectionHints(hints).buildAsync()
            var guard = 0
            while (!future.isDone && guard++ < 200) idleMainLooper()
            controller = future.get(5, TimeUnit.SECONDS)
            pc.boundedInstallEnabledForTesting = true
            pc.attachPhysicalWindowPort(port)
            engine.facade.bindLogicalQueue(pc.logicalQueuePresentation, pc.logicalQueueCommands)
            pc.setLogicalPresentationInvalidator { engine.facade.invalidateLogicalState() }
            PlayerController::class.java.getDeclaredField("controllerConnectionState").apply { isAccessible = true }
                .set(pc, ControllerConnectionState.Connected)
            PlayerController::class.java.getDeclaredMethod("onControllerConnected", MediaController::class.java)
                .apply { isAccessible = true }.invoke(pc, controller)
            TestMediaSource.settle(100)
        }

        val adapter: PhysicalWindowAdapter
            get() = PlayerController::class.java.getDeclaredField("physicalWindowAdapter").apply { isAccessible = true }.get(pc) as PhysicalWindowAdapter

        fun quiesce() { TestMediaSource.awaitCondition { !adapter.isBusy }; idleMainLooper(); TestMediaSource.settle(60) }

        /** Starts the queue, waits until playing, and prepares NEXT through the real driver. */
        fun start(queue: List<Song>, index: Int) {
            pc.playFromQueue(queue, index)
            quiesce()
            TestMediaSource.awaitCondition { engine.currentPlayer.playbackState == Player.STATE_READY && engine.currentPlayer.playWhenReady }
        }

        fun prepareNext(): NextSlotState.Ready {
            driver.evaluate()
            TestMediaSource.awaitCondition { engine.nextPreparation.state is NextSlotState.Ready }
            return engine.nextPreparation.state as NextSlotState.Ready
        }

        /** Makes the fade window due on CURRENT and lets the runtime promote; then completes the overlap on the fake clock. */
        fun promoteAndSettle(): PromotionOutcome? {
            val current = engine.currentPlayer
            val duration = current.duration
            current.seekTo(current.currentMediaItemIndex, duration - 6_500L)
            TestMediaSource.settle(60)
            runtime.evaluate()
            if (runtime.state is PromotionOverlapState.Overlap) {
                now += 10_000L
                runtime.tick()
            }
            TestMediaSource.settle(120)
            quiesce()
            return runtime.lastOutcome
        }

        fun release() {
            runCatching { runtime.close() }; runCatching { driver.close() }
            runCatching { controller.release() }; runCatching { pc.release() }
            runCatching { session.release() }; runCatching { engine.release() }
        }
    }

    private companion object { var counter = 0 }

    private val rigs = mutableListOf<Rig>()
    private fun rig() = Rig().also { rigs += it }
    @After fun tearDown() { rigs.forEach { it.release() }; rigs.clear() }

    private fun queue(n: Int) = List(n) { RealMedia3QueueRig.song(it + 1L) }

    private fun ids(p: Player) = (0 until p.mediaItemCount).map { p.getMediaItemAt(it).mediaId }

    @Test fun nextMirrorsTheVerifiedWindowInPhysicalCoordinatesAndNeverTheFullQueue() {
        val r = rig()
        r.start(queue(25_000), 12_000)
        val ready = r.prepareNext()
        val current = r.engine.currentPlayer
        val next = r.engine.nextPlayer
        assertTrue("window is bounded (${current.mediaItemCount})", current.mediaItemCount in 1..PhysicalWindowPolicy.W_MAX)
        assertEquals("NEXT holds exactly CURRENT's window", ids(current), ids(next))
        assertEquals(current.currentMediaItemIndex, ready.key.fromPlaybackIndex)
        assertEquals(current.currentMediaItemIndex + 1, ready.key.toPlaybackIndex)
        assertEquals("B is current on NEXT", ready.key.toPlaybackIndex, next.currentMediaItemIndex)
        assertEquals("12002", next.currentMediaItem?.mediaId)
        assertTrue("never the logical index space: ${ready.key}", ready.key.toPlaybackIndex < PhysicalWindowPolicy.W_MAX)
    }

    @Test fun promotionKeepsTheLogicalQueueStableAndMovesOwnershipToTheWindowOfTheNewCurrent() {
        val r = rig()
        r.start(queue(25_000), 12_000)
        val key = r.prepareNext().key
        val events = mutableListOf<String>()
        r.controller.addListener(object : Player.Listener {
            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) { events += "timeline($reason,${timeline.windowCount})" }
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) { events += "transition(${mediaItem?.mediaId},$reason)" }
        })
        val outcome = r.promoteAndSettle()
        assertTrue("outcome=$outcome", outcome is PromotionOutcome.Completed)
        assertSame("the promoted player is CURRENT", r.p2, r.engine.currentPlayer)
        assertSame("the port follows CURRENT", r.p2, r.port.owner)
        assertEquals(12_001, r.pc.nowPlayingState.value.currentIndex)
        assertEquals("12002", r.controller.currentMediaItem?.mediaId)
        assertEquals(12_001, r.controller.currentMediaItemIndex)
        assertEquals(25_000, r.controller.mediaItemCount)
        assertFalse("promotion is not a queue change: $events", events.any { it.startsWith("timeline(0,") })
        assertTrue("one AUTO transition to the next logical occurrence: $events", events.contains("transition(12002,${Player.MEDIA_ITEM_TRANSITION_REASON_AUTO})"))
        assertNotNull(r.adapter.active)
        assertEquals(12_001, (r.adapter.resolveCurrent(r.port) as CurrentResolution.Proven).logical)
        assertEquals(key.toPlaybackIndex, r.p2.currentMediaItemIndex)
    }

    @Test fun consecutivePromotionsAcrossRefillsKeepEveryOccurrenceExact() {
        val r = rig()
        r.start(queue(25_000), 5_000)
        var expected = 5_000
        repeat(40) { step ->
            r.prepareNext()
            val outcome = r.promoteAndSettle()
            assertTrue("step $step: $outcome", outcome is PromotionOutcome.Completed)
            expected++
            assertEquals("step $step logical", expected, r.pc.nowPlayingState.value.currentIndex)
            assertEquals("step $step song", (expected + 1).toString(), r.engine.currentPlayer.currentMediaItem?.mediaId)
            assertEquals(expected, r.controller.currentMediaItemIndex)
            assertTrue("bounded (${r.engine.currentPlayer.mediaItemCount})", r.engine.currentPlayer.mediaItemCount <= PhysicalWindowPolicy.W_MAX)
        }
        assertTrue("the window shifted at least once while crossfading", r.adapter.active!!.window.start > 5_000 - PhysicalWindowPolicy.HISTORY_TARGET)
        assertEquals(25_000, r.controller.mediaItemCount)
    }

    @Test fun aWindowChangeInvalidatesTheNextBuiltOnTheOldWindowAndItIsNeverPromoted() {
        val r = rig()
        r.start(queue(25_000), 12_000)
        val key = r.prepareNext().key
        // A logical mutation changes the window while NEXT is Ready: CURRENT is authoritative, NEXT is invalidated, not patched.
        r.pc.playNext(RealMedia3QueueRig.song(99_999L))
        r.quiesce()
        r.driver.evaluate()
        assertTrue("NEXT must not stay Ready on the old window (state=${r.engine.nextPreparation.state})",
            r.engine.nextPreparation.state.key != key || r.engine.nextPreparation.state !is NextSlotState.Ready)
        val rejected = r.engine.promoteReadyNext(key)
        assertFalse("a stale key is never promoted: $rejected", rejected is PromotionStartResult.Promoted)
        assertSame(r.p1, r.engine.currentPlayer)
    }

    @Test fun nextIsTransientlyUnavailableWhileTheWindowIsNotVerifiedOrBusy() {
        val r = rig()
        r.start(queue(25_000), 12_000)
        r.prepareNext()
        // A far jump re-anchors the window: until its physical acknowledgement the snapshot says "needs sync".
        r.pc.jumpToQueueItem(20_000)
        val during = r.pc.captureCrossfadeRuntimeSnapshot()
        assertTrue("busy window must not host a NEXT", during.playerQueueNeedsSync)
        r.driver.evaluate()
        assertFalse(r.engine.nextPreparation.state is NextSlotState.Ready)
        r.quiesce()
        assertFalse("verified again", r.pc.captureCrossfadeRuntimeSnapshot().playerQueueNeedsSync)
        r.prepareNext()
        assertEquals(20_000, r.pc.nowPlayingState.value.currentIndex)
    }

    @Test fun anInsufficientAheadMarginIsNotReadyUnlessTheWindowEndsTheQueue() {
        val r = rig()
        r.start(queue(25_000), 12_000)
        r.quiesce()
        val active = r.adapter.active!!
        val snap = r.pc.captureCrossfadeRuntimeSnapshot()
        val ahead = active.installedCount - 2 - snap.currentPlaybackIndex!!
        assertTrue("fresh window has ample margin ($ahead)", ahead >= PhysicalWindowPolicy.AHEAD_FLOOR)
        assertFalse(snap.playerQueueNeedsSync)
        // Logical end: the window genuinely ends the queue, no margin is required (and no next exists, so no crossfade is planned).
        val end = rig()
        end.start(queue(25_000), 24_999)
        assertTrue(end.adapter.active!!.window.endsAtLogicalEnd)
    }
}
