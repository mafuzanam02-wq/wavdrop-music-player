package com.launchpoint.wavdrop.playback

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import com.google.common.util.concurrent.Futures
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * P5-E Checkpoint A: the physical-window command boundary. Bounded code reaches the physical CURRENT only through
 * [PhysicalWindowPort]; the logical MediaController never carries a physical index and never proves anything about the window.
 * The "logical" session player and the "physical" player are DIFFERENT real ExoPlayers here, so any leakage is observable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class PhysicalWindowPortTest {

    private class Writer : PlayEventWriter {
        override suspend fun recordPlay(songId: Long, contentUri: String, listenedMs: Long, durationMs: Long) = Unit
        override suspend fun recordSkip(songId: Long, contentUri: String, durationMs: Long) = Unit
        override suspend fun recordListenStart(songId: Long, contentUri: String) = Unit
    }

    /** logical = the session/controller side; physical = the player the port fronts. */
    private inner class Split(bounded: Boolean, attachPort: Boolean = true) {
        val context: Context = RuntimeEnvironment.getApplication()
        val logical = RealMedia3QueueRig(emptyList(), 0, loadInitialQueue = false, sessionName = "pl", physicalAck = true)
        val physical = RealMedia3QueueRig(emptyList(), 0, loadInitialQueue = false, sessionName = "pp")
        private fun store(name: String) = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + Job()),
            produceFile = { File.createTempFile(name, ".preferences_pb").also { f -> f.delete(); f.deleteOnExit() } },
        )
        val pc = PlayerController(
            context,
            StatsTracker(Writer()).also { it.scope = CoroutineScope(Dispatchers.Unconfined); it.clock = { 0L } },
            PlaybackSessionRepository(store("sessionstore")), ResumeBehaviorSettingsRepository(store("resumestore")), AppSettingsRepository(store("appstore")),
        )

        init {
            pc.boundedInstallEnabledForTesting = bounded
            if (attachPort) pc.attachPhysicalWindowPort(physical.port)
            PlayerController::class.java.getDeclaredField("controllerConnectionState").apply { isAccessible = true }
                .set(pc, ControllerConnectionState.Connected)
            PlayerController::class.java.getDeclaredMethod("onControllerConnected", MediaController::class.java)
                .apply { isAccessible = true }.invoke(pc, logical.controller)
            logical.settle(100)
        }

        val adapter: PhysicalWindowAdapter
            get() = PlayerController::class.java.getDeclaredField("physicalWindowAdapter").apply { isAccessible = true }.get(pc) as PhysicalWindowAdapter

        fun quiesce() { TestMediaSource.awaitCondition { !adapter.isBusy }; idleMainLooper() }
        fun release() { runCatching { pc.release() }; logical.releaseAll(); physical.releaseAll() }
    }

    private val splits = mutableListOf<Split>()
    private fun split(bounded: Boolean = true, attachPort: Boolean = true) = Split(bounded, attachPort).also { splits += it }
    @After fun tearDown() { splits.forEach { it.release() }; splits.clear() }

    private fun queue(n: Int) = List(n) { RealMedia3QueueRig.song(it + 1L) }

    private val indexBearing = listOf("setMediaItems", "add", "append", "remove", "replace", "seekTo")

    private fun newAdapter() = PhysicalWindowAdapter(currentLogicalGeneration = { 1L }, materialize = { songs -> songs.map(Song::toPlaybackMediaItem) })

    // ── A1 / A2 / A3: index-bearing operations and proof live on the physical player only ─────────────────────────────────

    @Test fun boundedInstallAndNavigationReachOnlyThePhysicalPlayerNeverTheLogicalController() {
        val s = split()
        s.pc.playFromQueue(queue(25_000), 12_000)
        s.quiesce()
        s.pc.skipToNext(); s.quiesce()
        s.pc.jumpToQueueItem(20_000); s.quiesce()
        s.pc.skipToPrevious(); s.quiesce()
        val leaked = s.logical.recording.calls.filter { c -> indexBearing.any { c.startsWith(it) } }
        assertTrue("no index-bearing call may reach the logical player: $leaked", leaked.isEmpty())
        assertEquals("the logical player never held the physical window", 0, s.logical.player.mediaItemCount)
        val physicalOps = s.physical.recording.calls.filter { c -> indexBearing.any { c.startsWith(it) } }
        assertTrue("the physical player received the window operations: $physicalOps", physicalOps.any { it.startsWith("setMediaItems") })
        assertTrue(s.physical.player.mediaItemCount in 1..PhysicalWindowPolicy.W_MAX)
        assertEquals(20_000 - 1, s.pc.nowPlayingState.value.currentIndex)
    }

    @Test fun logicalIndicesAreNeverInterpretedAsPhysicalOnes() {
        val s = split()
        s.pc.playFromQueue(queue(25_000), 12_000)
        s.quiesce()
        val active = s.adapter.active!!
        assertTrue(active.physicalStart < PhysicalWindowPolicy.W_MAX)
        assertEquals(12_000, s.pc.nowPlayingState.value.currentIndex)
        assertEquals(active.physicalStart, s.physical.player.currentMediaItemIndex)
        assertEquals("12001", s.physical.player.currentMediaItem?.mediaId)
    }

    @Test fun verificationObservesThePhysicalPlayerNotTheSessionPlayer() {
        val s = split()
        s.pc.playFromQueue(queue(25_000), 500)
        s.quiesce()
        val active = s.adapter.active
        assertNotNull(active)
        assertEquals(0, s.logical.player.mediaItemCount)
        val q = s.pc.nowPlayingState.value.queue
        assertEquals(s.physical.serverIds(), (0 until active!!.installedCount).map { q[active.physicalToLogical(it)!!].id.toString() })
    }

    @Test fun aLogicalTimelineObservationIsNotPhysicalProof() {
        // The window is pushed into one player but verified against another one: it must never activate.
        val r = RealMedia3QueueRig(emptyList(), 0, loadInitialQueue = false, sessionName = "pw")
        val other = RealMedia3QueueRig(emptyList(), 0, loadInitialQueue = false, sessionName = "po")
        try {
            val adapter = newAdapter()
            val outcomes = mutableListOf<BoundedInstallOutcome>()
            adapter.install(r.port, queue(1_000), 1L, 400, 0L, RepeatMode.OFF, other.port.verifier) { outcomes += it }
            TestMediaSource.awaitCondition { outcomes.any { it !is BoundedInstallOutcome.Submitted } }
            assertNull(adapter.active)
            assertTrue(outcomes.last() is BoundedInstallOutcome.Incomplete)
        } finally { r.releaseAll(); other.releaseAll() }
    }

    // ── A4 / A5: ownership across promotion or replacement ─────────────────────────────────────────────────────────────────

    /** A port whose fronted player can be switched, like the engine's CURRENT across a promotion. */
    private class SwitchPort(var target: PhysicalWindowPort) : PhysicalWindowPort {
        override val looper get() = target.looper
        override val owner: Any get() = target.owner
        override val mediaItemCount: Int get() = target.mediaItemCount
        override val currentMediaItemIndex: Int get() = target.currentMediaItemIndex
        override val currentMediaId: String? get() = target.currentMediaId
        override val currentPosition: Long get() = target.currentPosition
        override fun mediaIdAt(index: Int): String = target.mediaIdAt(index)
        override fun mediaItemAt(index: Int): MediaItem = target.mediaItemAt(index)
        override var repeatMode: Int
            get() = target.repeatMode
            set(v) { target.repeatMode = v }
        override var shuffleModeEnabled: Boolean
            get() = target.shuffleModeEnabled
            set(v) { target.shuffleModeEnabled = v }
        override fun setMediaItems(items: List<MediaItem>, startIndex: Int, startPositionMs: Long) = target.setMediaItems(items, startIndex, startPositionMs)
        override fun addMediaItems(index: Int, items: List<MediaItem>) = target.addMediaItems(index, items)
        override fun addMediaItems(items: List<MediaItem>) = target.addMediaItems(items)
        override fun removeMediaItems(fromIndex: Int, toIndexExclusive: Int) = target.removeMediaItems(fromIndex, toIndexExclusive)
        override fun seekTo(index: Int, positionMs: Long) = target.seekTo(index, positionMs)
        override fun addListener(listener: Player.Listener) = target.addListener(listener)
        override fun removeListener(listener: Player.Listener) = target.removeListener(listener)
        override val verifier: PhysicalInstallVerifier? get() = target.verifier
    }

    @Test fun aRetiredPhysicalPlayerCanNeverAcknowledgeAfterPromotionOrReplacement() {
        val a = RealMedia3QueueRig(emptyList(), 0, loadInitialQueue = false, sessionName = "sa")
        val b = RealMedia3QueueRig(emptyList(), 0, loadInitialQueue = false, sessionName = "sb")
        try {
            val port = SwitchPort(a.port)
            val held = SettableFuture.create<PhysicalObservation>()
            var token: PlanToken? = null
            val verifier = PhysicalInstallVerifier { t -> token = t; held }
            val adapter = newAdapter()
            val outcomes = mutableListOf<BoundedInstallOutcome>()
            adapter.install(port, queue(1_000), 1L, 400, 0L, RepeatMode.OFF, verifier) { outcomes += it }
            TestMediaSource.awaitCondition { token != null }
            // The engine promotes: CURRENT is now player B. A late, perfectly matching acknowledgement from the RETIRED player A arrives.
            port.target = b.port
            held.set(PhysicalObservation.observe(a.player, token!!))
            TestMediaSource.awaitCondition { outcomes.any { it !is BoundedInstallOutcome.Submitted } }
            val last = outcomes.last()
            assertTrue("stale after promotion, was $last", last is BoundedInstallOutcome.Stale && last.reason == StaleReason.PhysicalPlayerReplaced)
            assertNull("a retired player's proof never activates a mapping", adapter.active)
        } finally { a.releaseAll(); b.releaseAll() }
    }

    @Test fun aReplacedCurrentMakesTheOldMappingUnprovableAndNothingIsWrittenToTheRetiredOwner() {
        val a = RealMedia3QueueRig(emptyList(), 0, loadInitialQueue = false, sessionName = "ra")
        val b = RealMedia3QueueRig(emptyList(), 0, loadInitialQueue = false, sessionName = "rb")
        try {
            val port = SwitchPort(a.port)
            val adapter = newAdapter()
            val outcomes = mutableListOf<BoundedInstallOutcome>()
            adapter.install(port, queue(1_000), 1L, 400, 0L, RepeatMode.OFF, port.verifier) { outcomes += it }
            TestMediaSource.awaitCondition { adapter.active != null }
            port.target = b.port
            assertTrue("mapping of a retired owner is not provable", adapter.resolveCurrent(port) is CurrentResolution.Unproven)
            val aOps = a.recording.calls.size
            adapter.maintain(port, queue(1_000), RepeatMode.OFF, port.verifier) { outcomes += it }
            assertEquals("nothing is written to the retired player", aOps, a.recording.calls.size)
        } finally { a.releaseAll(); b.releaseAll() }
    }

    // ── A6: failed verification never activates a mapping ─────────────────────────────────────────────────────────────────

    @Test fun failedOrMismatchedVerificationLeavesNoActiveMappingAndARecoverableState() {
        val r = RealMedia3QueueRig(emptyList(), 0, loadInitialQueue = false, sessionName = "fv")
        try {
            for (verifier in listOf(
                PhysicalInstallVerifier { Futures.immediateFailedFuture(IllegalStateException("probe failed")) },
                PhysicalInstallVerifier { t -> Futures.immediateFuture(PhysicalObservation(t, 3, 0, listOf("1", "2", "3"))) },
            )) {
                val adapter = newAdapter()
                val outcomes = mutableListOf<BoundedInstallOutcome>()
                adapter.install(r.port, queue(1_000), 1L, 400, 0L, RepeatMode.OFF, verifier) { outcomes += it }
                TestMediaSource.awaitCondition { outcomes.any { it !is BoundedInstallOutcome.Submitted } }
                assertNull(adapter.active)
                assertTrue("state ${adapter.state}", adapter.state is WindowInstallState.NeedsReinstall)
            }
        } finally { r.releaseAll() }
    }

    // ── A7: Full mode is unchanged ──────────────────────────────────────────────────────────────────────────────────────────

    @Test fun fullModeNeverNeedsOrTouchesThePortAndKeepsTheControllerRouting() {
        val s = split(bounded = true, attachPort = false) // 100 songs is below the threshold: Full even with bounded enabled
        s.pc.playFromQueue(queue(100), 40)
        s.logical.settle(150)
        assertEquals("Full mode installs the whole queue through the controller", 100, s.logical.player.mediaItemCount)
        assertEquals(40, s.logical.player.currentMediaItemIndex)
        s.pc.skipToNext(); s.logical.settle(150)
        assertEquals(41, s.logical.player.currentMediaItemIndex)
        assertEquals(0, s.physical.recording.calls.size)
        assertFalse(PhysicalQueuePolicy.BOUNDED_WINDOW_ENABLED)
    }
}
