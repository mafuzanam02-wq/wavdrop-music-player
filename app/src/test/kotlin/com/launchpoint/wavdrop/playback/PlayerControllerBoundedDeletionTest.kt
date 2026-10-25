package com.launchpoint.wavdrop.playback

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
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
 * P5-D playback authority for a bounded current-song deletion, on the PRODUCTION [PlayerController] against REAL Media3. The
 * physical acknowledgement is controlled by the test (never a sleep): while it is pending the physical player still holds the old
 * window, and nothing may start, prepare or replay playback; once it is verified the replacement plays under the LATEST intent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class PlayerControllerBoundedDeletionTest {

    private class Writer : PlayEventWriter {
        val listenStarts = mutableListOf<Long>()
        override suspend fun recordPlay(songId: Long, contentUri: String, listenedMs: Long, durationMs: Long) = Unit
        override suspend fun recordSkip(songId: Long, contentUri: String, durationMs: Long) = Unit
        override suspend fun recordListenStart(songId: Long, contentUri: String) { listenStarts += songId }
    }

    /** Answers from the physical player, except while [holding]: requests are then parked until [release] (or failed). */
    private class Controlled(val r: RealMedia3QueueRig) : PhysicalInstallVerifier {
        var holding = false
        var failAll = false
        val held = mutableListOf<Pair<PlanToken, SettableFuture<PhysicalObservation>>>()
        override fun observe(token: PlanToken): ListenableFuture<PhysicalObservation> = when {
            failAll -> Futures.immediateFailedFuture(IllegalStateException("session refused"))
            holding -> SettableFuture.create<PhysicalObservation>().also { held += token to it }
            else -> Futures.immediateFuture(PhysicalObservation.observe(r.player, token))
        }
        fun hold() { holding = true }
        fun release() {
            holding = false
            held.forEach { (token, future) -> future.set(PhysicalObservation.observe(r.player, token)) }
            held.clear()
        }
    }

    private inner class H(bounded: Boolean = true) {
        val context: Context = RuntimeEnvironment.getApplication()
        val rig = RealMedia3QueueRig(emptyList(), 0, loadInitialQueue = false, sessionName = "pcd", physicalAck = true)
        val writer = Writer()
        val tracker = StatsTracker(writer).also { it.scope = CoroutineScope(Dispatchers.Unconfined); it.clock = { 0L } }
        private fun store(name: String) = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + Job()),
            produceFile = { File.createTempFile(name, ".preferences_pb").also { f -> f.delete(); f.deleteOnExit() } },
        )
        val pc = PlayerController(
            context, tracker, PlaybackSessionRepository(store("sessionstore")), ResumeBehaviorSettingsRepository(store("resumestore")), AppSettingsRepository(store("appstore")),
        )
        val ctl = Controlled(rig)
        val controller: MediaController get() = rig.controller
        val calls get() = rig.recording.calls

        init {
            pc.boundedInstallEnabledForTesting = bounded
            pc.boundedVerifierFactory = { ctl }
            pc.attachPhysicalWindowPort(rig.port)
            field("controllerConnectionState").set(pc, ControllerConnectionState.Connected)
            invoke("onControllerConnected", arrayOf(MediaController::class.java), arrayOf(rig.controller))
            rig.settle(100)
        }

        fun field(name: String) = PlayerController::class.java.getDeclaredField(name).apply { isAccessible = true }
        fun invoke(name: String, types: Array<Class<*>>, args: Array<Any?>): Any? =
            PlayerController::class.java.getDeclaredMethod(name, *types).apply { isAccessible = true }.invoke(pc, *args)

        val adapter: PhysicalWindowAdapter get() = field("physicalWindowAdapter").get(pc) as PhysicalWindowAdapter
        val continuation: Any? get() = field("pendingDeletionContinuation").get(pc)

        fun quiesce() { TestMediaSource.awaitCondition { !adapter.isBusy }; idleMainLooper() }
        fun start(queue: List<Song>, index: Int, playing: Boolean = true) {
            pc.playFromQueue(queue.toList(), index)
            quiesce()
            if (!playing) { controller.pause(); rig.settle(100) }
        }

        val queue: List<Song> get() = pc.nowPlayingState.value.queue
        val logical: Int get() = pc.nowPlayingState.value.currentIndex
        fun currentId(): String? = rig.player.currentMediaItem?.mediaId
        fun serverIds(): List<String> = rig.serverIds()
        fun releaseAll() { runCatching { pc.release() }; rig.releaseAll() }
        fun playbackCommands(from: Int) = calls.drop(from).filter { it == "play" || it == "pause" || it == "prepare" }
        fun playlistOps(from: Int) = calls.drop(from).filter { it.startsWith("setMediaItems(") || it.startsWith("add(") || it.startsWith("append(") || it.startsWith("remove(") || it.startsWith("replace(") }

        fun assertConsistent(model: List<Song>, label: String) {
            assertEquals("$label: logical queue", model.map { it.id }, queue.map { it.id })
            val a = adapter.active
            org.junit.Assert.assertNotNull("$label: verified window (state=${adapter.state})", a)
            a!!
            assertEquals("$label: window ids", (0 until a.installedCount).map { model[a.physicalToLogical(it)!!].id.toString() }, serverIds())
            assertEquals("$label: current song", model[logical].id.toString(), currentId())
            assertTrue("$label: bound", rig.player.mediaItemCount <= PhysicalWindowPolicy.W_MAX)
        }
    }

    private val hs = mutableListOf<H>()
    private fun h(bounded: Boolean = true) = H(bounded).also { hs += it }
    @After fun tearDown() { hs.forEach { it.releaseAll() }; hs.clear() }
    private fun queue(n: Int) = List(n) { RealMedia3QueueRig.song(it + 1L) }

    // ── delayed verification ────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun deletedCurrentWhilePlayingStartsNothingUntilTheReplacementIsVerifiedThenTheSuccessorTakesOverExactlyOnce() {
        val h = h(); val model = queue(25_000).toMutableList()
        h.start(model, 12_500)
        val server = RealMedia3QueueRig.Callbacks().also { h.rig.player.addListener(it) }
        h.ctl.hold()
        val before = h.calls.size
        h.pc.handleSongDeleted(12_501L); model.removeAt(12_500)
        // The logical queue and Now Playing are final at once; the PHYSICAL player is untouched and still holds the old window.
        assertEquals(24_999, h.queue.size)
        assertEquals(12_500, h.logical)
        assertEquals(12_502L, h.pc.nowPlayingState.value.song?.id)
        assertEquals("the deleted item is still the physical current", "12501", h.currentId())
        assertEquals("nothing at all reached the physical player while unverified: ${h.calls.drop(before)}", emptyList<String>(), h.calls.drop(before))
        assertTrue("a continuation is bound and waiting", h.continuation != null)
        assertTrue(h.ctl.held.isNotEmpty())

        h.ctl.release(); h.quiesce()
        h.assertConsistent(model, "after verification")
        assertEquals("12502", h.currentId())
        assertEquals(listOf("setMediaItems(100)"), h.playlistOps(before))
        assertEquals("no Play/Pause/prepare is ever issued by the continuation: ${h.playbackCommands(before)}", emptyList<String>(), h.playbackCommands(before))
        assertTrue("still playing: the user's intent was never touched", h.rig.player.playWhenReady)
        assertEquals("exactly one transition to the successor", 1, server.events.count { it.startsWith("transition(12502,") })
        assertTrue("the successor's listen is recorded at most once: ${h.writer.listenStarts}", h.writer.listenStarts.count { it == 12_502L } <= 1)
        assertNull("the continuation is consumed", h.continuation)
    }

    @Test fun aPauseDuringVerificationIsNeverOverriddenByTheEarlierPlayIntent() {
        val h = h(); val model = queue(25_000).toMutableList()
        h.start(model, 12_500)
        h.ctl.hold()
        h.pc.handleSongDeleted(12_501L); model.removeAt(12_500)
        h.pc.togglePlayPause() // the user pauses while verification is pending
        TestMediaSource.awaitCondition { !h.rig.player.playWhenReady }
        val afterPause = h.calls.size
        h.ctl.release(); h.quiesce()
        h.assertConsistent(model, "paused deletion")
        assertEquals("12502", h.currentId())
        assertFalse("the successor stays paused", h.rig.player.playWhenReady)
        assertFalse(h.rig.player.isPlaying)
        assertEquals("no Play after the Pause: ${h.playbackCommands(afterPause)}", emptyList<String>(), h.playbackCommands(afterPause))
    }

    @Test fun aPlayDuringVerificationOfAPausedDeletionIsHonouredExactlyOnce() {
        val h = h(); val model = queue(25_000).toMutableList()
        h.start(model, 12_500, playing = false)
        h.ctl.hold()
        h.pc.handleSongDeleted(12_501L); model.removeAt(12_500)
        assertFalse(h.rig.player.playWhenReady)
        h.pc.togglePlayPause()
        TestMediaSource.awaitCondition { h.rig.player.playWhenReady }
        val afterPlay = h.calls.size
        h.ctl.release(); h.quiesce()
        h.assertConsistent(model, "play during verification")
        assertTrue(h.rig.player.playWhenReady)
        assertEquals("no second Play: ${h.playbackCommands(afterPlay)}", emptyList<String>(), h.playbackCommands(afterPlay))
    }

    // ── supersession ────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun aNewerSelectionSupersedesTheDeletedSongContinuation() {
        val h = h(); val model = queue(25_000).toMutableList()
        h.start(model, 12_500)
        h.ctl.hold()
        h.pc.handleSongDeleted(12_501L)
        assertTrue(h.continuation != null)
        val other = List(1_000) { RealMedia3QueueRig.song(700_000L + it) }
        h.ctl.holding = false // the new selection verifies normally
        h.pc.playFromQueue(other.toList(), 10)
        assertNull("a newer selection invalidates the continuation at once", h.continuation)
        h.ctl.release(); h.quiesce() // the OLD acknowledgement arrives late
        h.assertConsistent(other, "newer selection")
        assertEquals("700010", h.currentId())
        assertTrue("nothing of the deleted-song continuation reached the physical player", h.serverIds().none { it.toLong() < 700_000L })
    }

    @Test fun aSecondDeletionSupersedesTheFirstRecovery() {
        val h = h(); val model = queue(25_000).toMutableList()
        h.start(model, 12_500)
        h.ctl.hold()
        val before = h.calls.size
        h.pc.handleSongDeleted(12_501L); model.removeAt(12_500)
        val first = h.continuation
        h.pc.handleSongDeleted(12_502L); model.removeAt(12_500) // the successor is deleted before anything was verified
        assertTrue("a second, distinct continuation replaced the first", h.continuation != null && h.continuation !== first)
        h.ctl.release(); h.quiesce()
        h.assertConsistent(model, "second deletion")
        assertEquals("12503", h.currentId())
        assertEquals("only the second continuation installed anything: ${h.playlistOps(before)}", listOf("setMediaItems(100)"), h.playlistOps(before))
        assertEquals(emptyList<String>(), h.playbackCommands(before))
    }

    // ── failure and disconnection ───────────────────────────────────────────────────────────────────────────────────────

    @Test fun failedVerificationLeavesABoundedRepairObligationAndNeverPlaysTheDeletedItem() {
        val h = h(); val model = queue(25_000).toMutableList()
        h.start(model, 12_500)
        h.ctl.failAll = true
        val before = h.calls.size
        h.pc.handleSongDeleted(12_501L); model.removeAt(12_500)
        TestMediaSource.awaitCondition { !h.adapter.isBusy }
        idleMainLooper()
        assertEquals("the logical edit stands", 24_999, h.queue.size)
        assertTrue("explicit repair obligation", h.adapter.state is WindowInstallState.NeedsReinstall)
        assertNull(h.adapter.active)
        // At most the ONE bounded recovery install, around the exact planned successor; never the full queue, never the deleted item.
        val ops = h.playlistOps(before)
        assertTrue("bounded: $ops", ops.size <= 1 && ops.all { it == "setMediaItems(100)" })
        assertTrue("the deleted item is not playing", h.currentId() != "12501")
        assertEquals("no Play/prepare was issued for an unverified window: ${h.playbackCommands(before)}", emptyList<String>(), h.playbackCommands(before))
    }

    @Test fun aControllerDisconnectDuringTheContinuationInvalidatesItAndReconnectReanchorsOnTheSuccessor() {
        val h = h(); val model = queue(25_000).toMutableList()
        h.start(model, 12_500)
        h.ctl.hold()
        h.pc.handleSongDeleted(12_501L); model.removeAt(12_500)
        assertTrue(h.continuation != null)
        val staleAcks = h.ctl.held.toList()
        h.ctl.held.clear()
        val lifecycle = h.field("controllerLifecycleListener").get(h.pc) as MediaController.Listener
        lifecycle.onDisconnected(h.controller)
        assertNull("the disconnect cleared the continuation", h.continuation)
        h.ctl.holding = false
        val before = h.calls.size
        h.invoke("onControllerConnected", arrayOf(MediaController::class.java), arrayOf(h.rig.controller))
        h.quiesce()
        staleAcks.forEach { (token, future) -> future.set(PhysicalObservation.observe(h.rig.player, token)) } // late, from the dead link
        idleMainLooper()
        h.assertConsistent(model, "after reconnect")
        assertEquals("12502", h.currentId())
        assertEquals(listOf("setMediaItems(100)"), h.playlistOps(before))
        assertEquals("a stale acknowledgement issued no playback command: ${h.playbackCommands(before)}", emptyList<String>(), h.playbackCommands(before).filter { it == "play" || it == "pause" })
    }

    // ── survivors, empty queue, Full mode ───────────────────────────────────────────────────────────────────────────────

    @Test fun deletingADuplicateSongSelectsTheDocumentedExactSurvivor() {
        val h = h()
        val model = List(2_000) { RealMedia3QueueRig.song(longArrayOf(1, 2, 1, 3, 1)[it % 5]) }.toMutableList()
        h.start(model, 1_002) // an occurrence of song 1; the next survivor after it is the song 3 at 1003
        val expected = model.subList(0, 1_003).count { it.id != 1L }
        h.pc.handleSongDeleted(1L); model.removeAll { it.id == 1L }; h.quiesce()
        h.assertConsistent(model, "duplicate deletion")
        assertEquals(expected, h.logical)
        assertEquals("3", h.currentId())
        assertTrue(h.queue.none { it.id == 1L })
    }

    @Test fun deletingTheLastDistinctSongStopsCleanlyWithoutAnyContinuation() {
        val h = h()
        h.start(List(400) { RealMedia3QueueRig.song(7L) }, 5)
        val before = h.calls.size
        h.pc.handleSongDeleted(7L); h.quiesce()
        assertEquals(0, h.queue.size)
        TestMediaSource.awaitCondition { h.rig.player.mediaItemCount == 0 }
        assertNull(h.continuation)
        assertNull(h.adapter.active)
        assertEquals(WindowInstallState.Idle, h.adapter.state)
        assertEquals("nothing is installed or played afterwards", emptyList<String>(), h.playlistOps(before))
        assertEquals(QueueMode.Full, h.field("installedQueueMode").get(h.pc))
    }

    @Test fun fullModeCurrentSongDeletionIsUnchanged() {
        assertFalse(PhysicalQueuePolicy.BOUNDED_WINDOW_ENABLED)
        val h = h(bounded = false); val model = queue(1_000).toMutableList()
        h.start(model, 400)
        val before = h.calls.size
        h.pc.handleSongDeleted(401L); model.removeAt(400)
        h.rig.settle(200)
        assertEquals("the Full path pushes the surviving queue and keeps playing", listOf("setMediaItems(999)"), h.playlistOps(before))
        assertEquals(model.map { it.id.toString() }, h.serverIds())
        assertEquals(400, h.logical)
        assertEquals("402", h.currentId())
        assertTrue(h.rig.player.playWhenReady)
        assertNull(h.continuation)
        assertEquals(WindowInstallState.Idle, h.adapter.state)
    }
}
