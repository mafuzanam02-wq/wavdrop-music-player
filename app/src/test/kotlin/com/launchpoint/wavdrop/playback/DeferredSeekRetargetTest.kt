package com.launchpoint.wavdrop.playback

import android.content.Context
import android.os.Bundle
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import com.google.common.util.concurrent.SettableFuture
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.playback.PlaybackSessionRepository
import com.launchpoint.wavdrop.data.repository.PlayEventWriter
import com.launchpoint.wavdrop.data.settings.AppSettingsRepository
import com.launchpoint.wavdrop.data.settings.ResumeBehaviorSettingsRepository
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * An external index-and-position seek that arrives while NO controller exists and retargets an already-pending deferred start.
 * The PRODUCTION [PlayerController] against real Media3: the connection attempt is a [SettableFuture] the test completes, and the
 * assertions are on what the real ExoPlayer ends up holding: the exact logical occurrence AND the physical position. Bounded
 * enabled for the test only (production gate stays false).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class DeferredSeekRetargetTest {

    private class Writer : PlayEventWriter {
        override suspend fun recordPlay(songId: Long, contentUri: String, listenedMs: Long, durationMs: Long) = Unit
        override suspend fun recordSkip(songId: Long, contentUri: String, durationMs: Long) = Unit
        override suspend fun recordListenStart(songId: Long, contentUri: String) = Unit
    }

    private inner class Harness(val bounded: Boolean) {
        val context: Context = RuntimeEnvironment.getApplication()
        val rig = RealMedia3QueueRig(emptyList(), 0, loadInitialQueue = false, sessionName = "dsr", withFacade = bounded)
        private fun store(name: String) = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + Job()),
            produceFile = { File.createTempFile(name, ".preferences_pb").also { f -> f.delete(); f.deleteOnExit() } },
        )
        val pc = PlayerController(
            context, StatsTracker(Writer()).also { it.scope = CoroutineScope(Dispatchers.Unconfined) },
            PlaybackSessionRepository(store("sessionstore")), ResumeBehaviorSettingsRepository(store("resumestore")), AppSettingsRepository(store("appstore")),
        )
        val attempts = mutableListOf<SettableFuture<MediaController>>()
        private val controllers = mutableListOf<MediaController>()

        init {
            field("controllerConnectionState").set(pc, ControllerConnectionState.Connected)
            rig.settle(50)
            pc.controllerFutureFactory = { SettableFuture.create<MediaController>().also { attempts += it } }
            field("controllerConnectionState").set(pc, ControllerConnectionState.Disconnected)
            if (bounded) {
                pc.boundedInstallEnabledForTesting = true
                pc.attachPhysicalWindowPort(rig.port)
                rig.facade!!.bindLogicalQueue(pc.logicalQueuePresentation, pc.logicalQueueCommands)
                pc.setLogicalPresentationInvalidator { rig.facade.invalidateLogicalState() }
            }
        }

        fun field(name: String) = PlayerController::class.java.getDeclaredField(name).apply { isAccessible = true }

        fun connect(): MediaController {
            rig.settle(50)
            val future = MediaController.Builder(context, rig.session.token).setConnectionHints(Bundle()).buildAsync()
            var guard = 0
            while (!future.isDone && guard++ < 200) rig.settle(20)
            val controller = future.get().also { controllers += it }
            attempts.last().set(controller)
            rig.settle(400)
            TestMediaSource.awaitCondition { !(field("physicalWindowAdapter").get(pc) as PhysicalWindowAdapter).isBusy }
            rig.settle(100)
            return controller
        }

        val logical get() = pc.nowPlayingState.value
        val physical get() = rig.player
        fun release() { runCatching { pc.release() }; controllers.forEach { runCatching { it.release() } }; rig.releaseAll() }
    }

    private val hs = mutableListOf<Harness>()
    private fun h(bounded: Boolean = true) = Harness(bounded).also { hs += it }
    @After fun tearDown() { hs.forEach { it.release() }; hs.clear(); PlaybackStartupTrace.resetForTest() }

    private fun queue(n: Int) = List(n) { RealMedia3QueueRig.song(it + 1L) }

    private fun assertLanded(h: Harness, index: Int, minPositionMs: Long, label: String) {
        assertEquals("$label: logical occurrence", index, h.logical.currentIndex)
        assertEquals("$label: song on the physical player", h.logical.queue[index].id.toString(), h.physical.currentMediaItem?.mediaId)
        TestMediaSource.awaitCondition { h.physical.currentPosition >= minPositionMs }
        val position = h.physical.currentPosition
        assertTrue("$label: position $position >= $minPositionMs", position >= minPositionMs)
        assertTrue("$label: position $position stays near the request", position < minPositionMs + 20_000L)
    }

    private fun assertNearStart(h: Harness, label: String) =
        assertTrue("$label: at the start of the item (${h.physical.currentPosition})", h.physical.currentPosition < 5_000L)

    // ── the reported defect ────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun aDeferredStartThenASeekToIndex20000At45000KeepsBothOccurrenceAndPosition() {
        val h = h()
        h.pc.playFromQueue(queue(25_000), 100)
        h.pc.seekToQueueItem(20_000, 45_000L)
        assertEquals("the pending start was retargeted in logical state at once", 20_000, h.logical.currentIndex)
        assertEquals(45_000L, h.logical.positionMs)
        h.connect()
        assertLanded(h, 20_000, 45_000L, "20000@45s")
        assertTrue("bounded window", h.physical.mediaItemCount <= PhysicalWindowPolicy.W_MAX)
        assertTrue("playing (the start's intent)", h.physical.playWhenReady)
    }

    @Test fun aNewerZeroPositionSeekReplacesAnEarlierNonzeroPosition() {
        val h = h()
        h.pc.playFromQueue(queue(25_000), 100)
        h.pc.seekToQueueItem(200, 30_000L)
        h.pc.seekToQueueItem(300, 0L)
        h.connect()
        assertEquals(300, h.logical.currentIndex)
        assertNearStart(h, "zero")
        assertEquals("301", h.physical.currentMediaItem?.mediaId)
    }

    @Test fun anOrdinaryJumpKeepsTheZeroPositionBehavior() {
        val h = h()
        h.pc.playFromQueue(queue(25_000), 100)
        h.pc.seekToQueueItem(200, 30_000L)
        h.pc.jumpToQueueItem(400) // no position: an ordinary queue jump
        h.connect()
        assertEquals(400, h.logical.currentIndex)
        assertNearStart(h, "ordinary jump")
    }

    @Test fun successivePositionBearingSeeksLatestWins() {
        val h = h()
        h.pc.playFromQueue(queue(25_000), 100)
        h.pc.seekToQueueItem(1_000, 10_000L)
        h.pc.seekToQueueItem(2_000, 20_000L)
        h.pc.seekToQueueItem(3_000, 30_000L)
        h.connect()
        assertLanded(h, 3_000, 30_000L, "latest")
    }

    @Test fun defaultPositionSemanticsAreDistinctFromAnExplicitPosition() {
        val h = h()
        h.pc.playFromQueue(queue(25_000), 100)
        h.pc.seekToQueueItem(500, 25_000L)
        h.pc.logicalQueueCommands.seekToQueueItem(600, C.TIME_UNSET) // the logical command port: default position
        h.connect()
        assertEquals(600, h.logical.currentIndex)
        assertNearStart(h, "default position")
        val h2 = h()
        h2.pc.playFromQueue(queue(25_000), 100)
        h2.pc.logicalQueueCommands.seekToQueueItem(600, 12_000L)
        h2.connect()
        assertLanded(h2, 600, 12_000L, "explicit position")
    }

    @Test fun duplicateSongOccurrencesStayExact() {
        val h = h()
        val q = List(3_000) { RealMedia3QueueRig.song(longArrayOf(1, 2, 1, 3, 1)[it % 5]) }
        h.pc.playFromQueue(q, 1_000)
        h.pc.seekToQueueItem(1_002, 7_000L)
        h.pc.seekToQueueItem(1_004, 9_000L) // the same song id as 1_000 and 1_002
        h.connect()
        assertLanded(h, 1_004, 9_000L, "third copy of the song")
        assertEquals("1", h.physical.currentMediaItem?.mediaId)
    }

    // ── authority ──────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun aRemovalBeforeReconnectRebasesTheTargetAndKeepsThePosition() {
        val h = h()
        h.pc.playFromQueue(queue(25_000), 100)
        h.pc.seekToQueueItem(20_000, 45_000L)
        val target = h.logical.queue[20_000]
        h.pc.removeFromQueue(50) // an earlier entry is removed before reconnect: the target shifts by one
        h.connect()
        val index = h.logical.queue.indexOfFirst { it === target }
        assertEquals("the same queue entry, one earlier", 19_999, index)
        assertLanded(h, index, 45_000L, "after removal")
        assertEquals(target.id.toString(), h.physical.currentMediaItem?.mediaId)
    }

    @Test fun aNewerQueueReplacementSupersedesTheDeferredSeek() {
        val h = h()
        h.pc.playFromQueue(queue(25_000), 100)
        h.pc.seekToQueueItem(20_000, 45_000L)
        val other = List(2_000) { RealMedia3QueueRig.song(70_000L + it) }
        h.pc.playFromQueue(other, 7)
        h.connect()
        assertEquals(7, h.logical.currentIndex)
        assertEquals(2_000, h.logical.queue.size)
        assertEquals("70007", h.physical.currentMediaItem?.mediaId)
        assertNearStart(h, "replacement")
    }

    @Test fun aPauseCapturedAfterTheSeekIsPreservedAfterReconnect() {
        val h = h()
        h.pc.playFromQueue(queue(25_000), 100)
        h.pc.seekToQueueItem(20_000, 45_000L)
        h.pc.onExplicitExternalPlayIntent(false) // an external Pause captured while no controller exists
        h.connect()
        assertEquals(20_000, h.logical.currentIndex)
        assertFalse("the newer pause wins", h.physical.playWhenReady)
        TestMediaSource.awaitCondition { h.physical.currentPosition >= 45_000L }
    }

    @Test fun aStandaloneDeferredJumpStillCarriesItsPosition() {
        val h = h()
        // no pending start: the queue is installed first, then the controller goes away and a jump with a position is deferred
        val c = h.run { pc.playFromQueue(queue(25_000), 100); connect() }
        (h.field("controllerLifecycleListener").get(h.pc) as MediaController.Listener).onDisconnected(c)
        h.pc.seekToQueueItem(18_000, 33_000L)
        h.connect()
        assertLanded(h, 18_000, 33_000L, "standalone deferred jump")
    }

    // ── Full mode ──────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun fullModeDeferredStartRetargetingKeepsThePositionToo() {
        val h = h(bounded = false)
        h.pc.playFromQueue(queue(1_000), 100)
        h.pc.seekToQueueItem(500, 20_000L)
        h.connect()
        assertEquals("Full: the whole queue is installed", 1_000, h.physical.mediaItemCount)
        assertLanded(h, 500, 20_000L, "Full retarget")
        val h2 = h(bounded = false)
        h2.pc.playFromQueue(queue(1_000), 100)
        h2.pc.jumpToQueueItem(600)
        h2.connect()
        assertEquals(600, h2.logical.currentIndex)
        assertNearStart(h2, "Full ordinary jump")
        assertNull((h2.field("physicalWindowAdapter").get(h2.pc) as PhysicalWindowAdapter).active)
    }
}
