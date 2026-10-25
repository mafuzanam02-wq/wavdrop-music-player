package com.launchpoint.wavdrop.playback

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
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
 * P5-C at the PRODUCTION [PlayerController] level: bounded (test-only enabled, committed gate false) navigation, current-occurrence
 * resolution, automatic transitions, repeat boundaries, reconnect and stale callbacks, against REAL Media3 with the real physical
 * acknowledgement. Natural playback completion is emulated by moving the physical player and invoking the production listener
 * with the Media3 AUTO reasons (a trackless 3-minute source cannot be played to its end in a JVM test).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class PlayerControllerBoundedWindowTest {

    private class Writer : PlayEventWriter {
        val listenStarts = mutableListOf<Long>()
        override suspend fun recordPlay(songId: Long, contentUri: String, listenedMs: Long, durationMs: Long) = Unit
        override suspend fun recordSkip(songId: Long, contentUri: String, durationMs: Long) = Unit
        override suspend fun recordListenStart(songId: Long, contentUri: String) { listenStarts += songId }
    }

    private inner class Harness(bounded: Boolean = true) {
        val context: Context = RuntimeEnvironment.getApplication()
        val rig = RealMedia3QueueRig(emptyList(), 0, loadInitialQueue = false, sessionName = "pcb", physicalAck = true)
        val writer = Writer()
        val tracker = StatsTracker(writer).also {
            it.scope = CoroutineScope(Dispatchers.Unconfined)
            it.clock = { 0L }
        }
        private fun store(name: String) = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + Job()),
            produceFile = { File.createTempFile(name, ".preferences_pb").also { f -> f.delete(); f.deleteOnExit() } },
        )
        val pc = PlayerController(
            context, tracker, PlaybackSessionRepository(store("sessionstore")), ResumeBehaviorSettingsRepository(store("resumestore")), AppSettingsRepository(store("appstore")),
        )
        val controller: MediaController get() = rig.controller
        val calls get() = rig.recording.calls

        init {
            pc.boundedInstallEnabledForTesting = bounded
            field("controllerConnectionState").set(pc, ControllerConnectionState.Connected)
            invoke("onControllerConnected", arrayOf(MediaController::class.java), arrayOf(rig.controller))
            rig.settle(100)
        }

        fun field(name: String) = PlayerController::class.java.getDeclaredField(name).apply { isAccessible = true }
        fun invoke(name: String, types: Array<Class<*>>, args: Array<Any?>): Any? =
            PlayerController::class.java.getDeclaredMethod(name, *types).apply { isAccessible = true }.invoke(pc, *args)

        val adapter: PhysicalWindowAdapter get() = field("physicalWindowAdapter").get(pc) as PhysicalWindowAdapter
        val listener: Player.Listener get() = field("playerListener").get(pc) as Player.Listener

        /** Pumps until the adapter has no operation in flight. */
        fun quiesce() { TestMediaSource.awaitCondition { !adapter.isBusy }; idleMainLooper() }

        fun start(queue: List<Song>, index: Int, playing: Boolean = true) {
            pc.playFromQueue(queue, index)
            quiesce()
            if (!playing) { controller.pause(); rig.settle(100) }
        }

        fun logical(): Int = pc.nowPlayingState.value.currentIndex
        fun currentId(): String? = controller.currentMediaItem?.mediaId
        fun releaseAll() { runCatching { pc.release() }; rig.releaseAll() }

        /** What Media3 does at a natural boundary, then the production callbacks that report it. */
        fun naturalAdvance() {
            rig.player.seekToNextMediaItem()
            TestMediaSource.awaitCondition { controller.currentMediaItemIndex == rig.player.currentMediaItemIndex }
            listener.onMediaItemTransition(controller.currentMediaItem, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
            quiesce()
        }
    }

    private val harnesses = mutableListOf<Harness>()
    private fun harness(bounded: Boolean = true) = Harness(bounded).also { harnesses += it }
    @After fun tearDown() { harnesses.forEach { it.releaseAll() }; harnesses.clear() }

    private fun queue(n: Int) = List(n) { RealMedia3QueueRig.song(it + 1L) }

    private fun assertBounded(h: Harness) {
        assertTrue("window bound: ${h.rig.player.mediaItemCount}", h.rig.player.mediaItemCount <= PhysicalWindowPolicy.W_MAX)
        assertTrue(h.calls.none { it.startsWith("setMediaItems(") && Regex("""\((\d+)\)""").find(it)!!.groupValues[1].toInt() > PhysicalWindowPolicy.W_MAX })
    }

    // ── navigation ──────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun nextAndPreviousMoveByLogicalPositionAndKeepPlayOrPauseIntent() {
        for (playing in listOf(true, false)) {
            val h = harness()
            h.start(queue(25_000), 12_500, playing)
            assertEquals(12_500, h.logical())
            assertEquals("12501", h.currentId())
            h.pc.skipToNext()
            h.quiesce()
            assertEquals(12_501, h.logical()); assertEquals("12502", h.currentId())
            assertEquals("play intent preserved (playing=$playing)", playing, h.controller.playWhenReady)
            h.pc.skipToPrevious()
            h.quiesce()
            assertEquals(12_500, h.logical()); assertEquals("12501", h.currentId())
            assertEquals(playing, h.controller.playWhenReady)
            assertBounded(h)
        }
    }

    @Test fun previousAtTheLogicalStartWithRepeatOffRestartsTheCurrentTrackInsteadOfMoving() {
        val h = harness()
        h.start(queue(25_000), 0, playing = true)
        val before = h.calls.size
        h.pc.skipToPrevious() // QueueNavigator: index 0, repeat OFF -> RestartCurrent (the same branch as "past the threshold")
        h.rig.settle(150)
        val ops = h.calls.drop(before)
        assertEquals(0, h.logical()); assertEquals("1", h.currentId())
        assertEquals("restart is a seek inside the current item", listOf("seekTo(0)"), ops.filter { it.startsWith("seekTo") })
        assertEquals(0, ops.count { it.startsWith("setMediaItems") })
    }

    @Test fun jumpToQueueItemInAndOutOfTheWindowIsExact() {
        val h = harness()
        h.start(queue(25_000), 12_500)
        val before = h.calls.size
        h.pc.jumpToQueueItem(12_520)
        h.quiesce()
        assertEquals(12_520, h.logical())
        assertEquals("inside the window: a seek, no install", 0, h.calls.drop(before).count { it.startsWith("setMediaItems") })
        h.pc.jumpToQueueItem(3)
        h.quiesce()
        assertEquals(3, h.logical()); assertEquals("4", h.currentId())
        h.pc.jumpToQueueItem(24_999)
        h.quiesce()
        assertEquals(24_999, h.logical()); assertEquals("25000", h.currentId())
        assertBounded(h)
    }

    @Test fun duplicateOccurrencesStayDistinctThroughNextAndPrevious() {
        val h = harness()
        val q = List(2_000) { RealMedia3QueueRig.song(longArrayOf(1, 2, 1, 3, 1)[it % 5]) }
        h.start(q, 1_002)
        h.pc.skipToNext(); h.quiesce()
        assertEquals(1_003, h.logical()); assertEquals("3", h.currentId())
        h.pc.skipToPrevious(); h.quiesce()
        assertEquals(1_002, h.logical()); assertEquals("1", h.currentId())
        h.pc.jumpToQueueItem(1_004); h.quiesce()
        assertEquals(1_004, h.logical())
        assertEquals(h.adapter.active!!.logicalToPhysical(1_004), h.controller.currentMediaItemIndex)
    }

    @Test fun rapidNextPressesEachAdvanceExactlyOneOccurrence() {
        val h = harness()
        h.start(queue(25_000), 100)
        repeat(30) { h.pc.skipToNext() }
        h.quiesce()
        assertEquals(130, h.logical()); assertEquals("131", h.currentId())
        assertBounded(h)
    }

    // ── automatic transitions ───────────────────────────────────────────────────────────────────────────────────────────

    @Test fun nativeAutoTransitionIsFollowedWithoutAnyReinstallOrFullSync() {
        val h = harness()
        h.start(queue(25_000), 5_000)
        val before = h.calls.size
        repeat(5) { h.naturalAdvance() }
        assertEquals(5_005, h.logical()); assertEquals("5006", h.currentId())
        val ops = h.calls.drop(before)
        assertEquals("no playlist install on a native boundary: $ops", 0, ops.count { it.startsWith("setMediaItems") })
        assertEquals("no seek on a correct automatic transition: $ops", 0, ops.count { it.startsWith("seekTo") })
        assertFalse(h.pc.nowPlayingState.value.song == null)
        assertEquals(5_006L, h.pc.nowPlayingState.value.song!!.id)
    }

    @Test fun walkingAcrossSeveralWindowsByAutoTransitionsKeepsTheWindowBoundedAndExact() {
        val h = harness()
        h.start(queue(25_000), 0)
        var maxCount = 0
        for (step in 1..300) {
            h.naturalAdvance()
            maxCount = maxOf(maxCount, h.rig.player.mediaItemCount)
            assertEquals("step $step state=${h.adapter.state} busy=${h.adapter.isBusy} calls=${h.calls.takeLast(6)}", step, h.logical())
        }
        assertEquals(300, h.logical()); assertEquals("301", h.currentId())
        assertTrue("never beyond W_MAX (saw $maxCount)", maxCount <= PhysicalWindowPolicy.W_MAX)
        assertTrue("the window moved past its first span", h.adapter.active!!.window.start > 150)
        assertEquals(1, h.calls.count { it.startsWith("setMediaItems") }) // the initial install only: pure native gapless
    }

    @Test fun repeatOffAtTheGenuineLogicalEndStopsAndNeverReinstalls() {
        val h = harness()
        h.start(queue(25_000), 24_999)
        val before = h.calls.size
        h.listener.onPlaybackStateChanged(Player.STATE_ENDED)
        h.quiesce()
        assertEquals(24_999, h.logical())
        assertEquals(0, h.calls.drop(before).count { it.startsWith("setMediaItems") || it.startsWith("seekTo") })
    }

    @Test fun repeatAllContinuesThroughTheCompleteLogicalOrderAcrossTheEnd() {
        val h = harness()
        h.start(queue(25_000), 24_998)
        h.pc.cycleRepeatMode() // OFF -> ALL
        h.quiesce()
        assertEquals("physical repeat never loops inside the window", Player.REPEAT_MODE_OFF, h.controller.repeatMode)
        assertEquals(RepeatMode.ALL, h.pc.nowPlayingState.value.repeatMode)
        assertTrue("ring window: logical 0 follows the last song", h.adapter.active!!.window.wraps)
        repeat(3) { h.naturalAdvance() } // 24999, 0, 1
        assertEquals(1, h.logical()); assertEquals("2", h.currentId())
        assertBounded(h)
    }

    @Test fun repeatOneIsMirroredNativelyAndTheLogicalModeIsPreserved() {
        val h = harness()
        h.start(queue(25_000), 500)
        h.pc.cycleRepeatMode(); h.pc.cycleRepeatMode() // ALL, ONE
        h.quiesce()
        assertEquals(Player.REPEAT_MODE_ONE, h.controller.repeatMode)
        assertEquals(RepeatMode.ONE, h.pc.nowPlayingState.value.repeatMode)
        assertEquals(500, h.logical())
        h.pc.cycleRepeatMode() // OFF
        h.quiesce()
        assertEquals(Player.REPEAT_MODE_OFF, h.controller.repeatMode)
        assertEquals(RepeatMode.OFF, h.pc.nowPlayingState.value.repeatMode)
    }

    @Test fun previousFromLogicalZeroUnderRepeatAllMovesToTheLastSongThroughARingWindow() {
        val h = harness()
        h.start(queue(25_000), 0)
        h.pc.cycleRepeatMode(); h.quiesce() // ALL
        val before = h.calls.size
        h.pc.skipToPrevious()
        h.quiesce()
        assertEquals(24_999, h.logical()); assertEquals("25000", h.currentId())
        assertEquals("one bounded install", 1, h.calls.drop(before).count { it.startsWith("setMediaItems(") })
        assertBounded(h)
    }

    @Test fun physicalEndReportedMidQueueContinuesAtTheNextLogicalOccurrence() {
        val h = harness()
        h.start(queue(25_000), 12_000)
        // The physical player reports the end of its window although the logical queue has much more (window outrun).
        h.listener.onPlaybackStateChanged(Player.STATE_ENDED)
        h.quiesce()
        assertEquals(12_001, h.logical())
        assertEquals("12002", h.currentId())
        assertBounded(h)
    }

    @Test fun physicalEndUnderRepeatAllContinuesAtLogicalZeroThroughTheCompleteQueue() {
        val h = harness()
        h.start(queue(25_000), 24_999)
        h.pc.cycleRepeatMode(); h.quiesce() // ALL
        h.listener.onPlaybackStateChanged(Player.STATE_ENDED)
        h.quiesce()
        assertEquals(0, h.logical()); assertEquals("1", h.currentId())
        assertBounded(h)
    }

    @Test fun unprovablePhysicalCurrentFailsClosedWithOneBoundedRecoveryAndNeverAFullSync() {
        val h = harness()
        h.start(queue(25_000), 5_000)
        val before = h.calls.size
        // The physical current is replaced behind the adapter's back. The production listener reports the playlist change and the
        // occurrence can no longer be proven: ONE bounded re-anchor around the last proven occurrence, never a full-queue sync.
        h.rig.player.replaceMediaItem(h.controller.currentMediaItemIndex, RealMedia3QueueRig.song(99_999L).toPlaybackMediaItem())
        TestMediaSource.awaitCondition { h.calls.drop(before).any { it.startsWith("setMediaItems") } }
        h.quiesce()
        val installs = h.calls.drop(before).filter { it.startsWith("setMediaItems") }
        assertEquals("exactly one bounded recovery install", listOf("setMediaItems(100)"), installs)
        assertEquals(5_000, h.logical()); assertEquals("5001", h.currentId())
        assertBounded(h)
    }

    // ── authority, reconnect, rejection, Full mode ──────────────────────────────────────────────────────────────────────

    @Test fun staleAutomaticCallbackWhileTheWindowIsUnverifiedChangesNothing() {
        val h = harness()
        h.start(queue(25_000), 5_000)
        h.pc.skipToNext(); h.quiesce() // 5001
        val before = h.calls.size
        // A new re-anchor is in flight (provisional/unacknowledged); an old automatic callback arrives.
        h.pc.jumpToQueueItem(20_000)
        h.listener.onMediaItemTransition(h.controller.currentMediaItem, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        h.quiesce()
        assertEquals(20_000, h.logical())
        assertEquals(1, h.calls.drop(before).count { it.startsWith("setMediaItems") })
        assertEquals("a stale callback must not change the current song", 20_001L, h.pc.nowPlayingState.value.song!!.id)
    }

    @Test fun queueReplacementWhileAWindowOperationIsPendingSupersedesIt() {
        val h = harness()
        h.start(queue(25_000), 5_000)
        h.pc.jumpToQueueItem(15_000) // re-anchor in flight (not yet awaited)
        h.pc.playFromQueue(queue(20), 3) // new small queue: Full mode
        h.quiesce()
        assertEquals(3, h.logical())
        assertEquals(WindowInstallState.Idle, h.adapter.state)
        assertNull(h.adapter.active)
        assertEquals(20, h.rig.player.mediaItemCount)
        assertFalse("Full queue: the bounded branches are off", h.field("installedQueueMode").get(h.pc) == QueueMode.Bounded)
    }

    @Test fun deferredNavigationWhileDisconnectedIsHonouredAfterReconnect() {
        val h = harness()
        h.start(queue(25_000), 7_000)
        // Controller lost: navigation is captured against the exact occurrence.
        h.field("mediaController").set(h.pc, null)
        h.pc.skipToNext()
        // Reconnect with the same (still live) physical player behind a fresh controller reference.
        h.invoke("onControllerConnected", arrayOf(MediaController::class.java), arrayOf(h.rig.controller))
        h.quiesce()
        assertEquals(7_001, h.logical()); assertEquals("7002", h.currentId())
        assertBounded(h)
    }

    @Test fun reconnectReanchorsAroundTheExactCurrentWithoutTheFullQueue() {
        val h = harness()
        h.start(queue(25_000), 9_000)
        val before = h.calls.size
        h.invoke("onControllerConnected", arrayOf(MediaController::class.java), arrayOf(h.rig.controller))
        h.quiesce()
        assertEquals(9_000, h.logical())
        val installs = h.calls.drop(before).filter { it.startsWith("setMediaItems") }
        assertEquals(listOf("setMediaItems(100)"), installs)
    }

    @Test fun queueMutationsAndShuffleAreRejectedInABoundedWindowUntilP5D() {
        val h = harness()
        val q = queue(25_000)
        h.start(q, 500)
        val before = h.calls.size
        h.pc.playNext(RealMedia3QueueRig.song(77_777L))
        h.pc.addToQueue(RealMedia3QueueRig.song(77_778L))
        h.pc.removeFromQueue(510)
        h.pc.moveQueueItemDown(510)
        h.pc.toggleShuffle()
        h.rig.settle(50)
        assertEquals(25_000, h.pc.nowPlayingState.value.queue.size)
        assertEquals(500, h.logical())
        assertEquals(0, h.calls.drop(before).count { it.startsWith("add") || it.startsWith("append") || it.startsWith("remove") || it.startsWith("replace") || it.startsWith("setMediaItems") })
        assertFalse(h.pc.nowPlayingState.value.shuffleEnabled)
    }

    @Test fun crossfadePlanningIsUnavailableInABoundedWindow() {
        val h = harness()
        h.start(queue(25_000), 500)
        assertTrue(h.pc.captureCrossfadeRuntimeSnapshot().playerQueueNeedsSync)
        val plan = planCrossfadeFromRuntimeSnapshot(6_000L, h.pc.captureCrossfadeRuntimeSnapshot(), 180_000L)
        assertTrue(plan is CrossfadeTransitionPlan.Unavailable)
    }

    @Test fun boundedControllerCodeNeverReachesAFullQueuePush() {
        val source = File("src/main/kotlin/com/launchpoint/wavdrop/playback/PlayerController.kt").readText()
        val start = source.indexOf("// ── P5-C: bounded-window integration")
        val end = source.indexOf("private fun MediaController.setMeasuredMediaItems(")
        assertTrue(start in 0 until end)
        val bounded = source.substring(start, end)
        for (forbidden in listOf("syncPlayerQueueAt(", "setMeasuredMediaItems(", ".setMediaItems(", "reconcilePhysicalQueue(", "kickQueueRepair(")) {
            assertFalse("bounded section must not call $forbidden", bounded.contains(forbidden))
        }
        // The Full-mode automatic-transition resync is unreachable from a bounded window.
        val handler = source.substring(source.indexOf("private fun handlePendingAutomaticTransition"))
        assertTrue(handler.indexOf("if (isBounded())") in 0 until handler.indexOf("syncPlayerQueueAt("))
        // The Full-queue reconciler never runs against a window.
        assertTrue(source.contains("if (isBounded()) return QueueReconcileOutcome.Unsafe(\"bounded_mode\")"))
        assertTrue(source.contains("if (!queueDirty || isBounded()) return"))
    }

    @Test fun fullModeIsUnchangedWithTheCommittedGateAndForSmallQueues() {
        assertFalse(PhysicalQueuePolicy.BOUNDED_WINDOW_ENABLED)
        // Gate-off controller: even a huge queue installs in full, exactly as before.
        val off = harness(bounded = false)
        off.start(queue(1_000), 400)
        assertEquals(1_000, off.rig.player.mediaItemCount)
        assertEquals(400, off.logical())
        off.pc.skipToNext(); off.rig.settle(100)
        assertEquals(401, off.logical()); assertEquals("402", off.currentId())
        assertEquals(1, off.calls.count { it.startsWith("setMediaItems(1000)") })
        // Test switch on, small queue: still Full.
        val small = harness(bounded = true)
        small.start(queue(300), 10)
        assertEquals(300, small.rig.player.mediaItemCount)
        assertEquals(QueueMode.Full, small.field("installedQueueMode").get(small.pc))
    }
}
