package com.launchpoint.wavdrop.playback

import android.content.Context
import android.os.Bundle
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * P5-E Checkpoint B: the PRODUCTION chain physical ExoPlayer -> SessionFacade -> MediaSession, with the real [PlayerController]
 * (bounded enabled for the test only; the committed gate stays false) and several INDEPENDENT real MediaControllers. A bounded
 * queue is presented to every controller as the complete logical queue; the physical player never holds more than the window.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class LogicalQueueSessionTest {

    private class Writer : PlayEventWriter {
        override suspend fun recordPlay(songId: Long, contentUri: String, listenedMs: Long, durationMs: Long) = Unit
        override suspend fun recordSkip(songId: Long, contentUri: String, durationMs: Long) = Unit
        override suspend fun recordListenStart(songId: Long, contentUri: String) = Unit
    }

    private inner class H(bounded: Boolean = true) {
        val context: Context = RuntimeEnvironment.getApplication()
        val rig = RealMedia3QueueRig(emptyList(), 0, loadInitialQueue = false, sessionName = "lqs", physicalAck = true, withFacade = true)
        val facade = rig.facade!!
        private fun store(name: String) = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + Job()),
            produceFile = { File.createTempFile(name, ".preferences_pb").also { f -> f.delete(); f.deleteOnExit() } },
        )
        val pc = PlayerController(
            context,
            StatsTracker(Writer()).also { it.scope = CoroutineScope(Dispatchers.Unconfined); it.clock = { 0L } },
            PlaybackSessionRepository(store("sessionstore")), ResumeBehaviorSettingsRepository(store("resumestore")), AppSettingsRepository(store("appstore")),
        )
        val extra = mutableListOf<MediaController>()

        init {
            pc.boundedInstallEnabledForTesting = bounded
            pc.attachPhysicalWindowPort(rig.port)
            facade.bindLogicalQueue(pc.logicalQueuePresentation, pc.logicalQueueCommands)
            pc.setLogicalPresentationInvalidator { facade.invalidateLogicalState() }
            PlayerController::class.java.getDeclaredField("controllerConnectionState").apply { isAccessible = true }
                .set(pc, ControllerConnectionState.Connected)
            PlayerController::class.java.getDeclaredMethod("onControllerConnected", MediaController::class.java)
                .apply { isAccessible = true }.invoke(pc, rig.controller)
            rig.settle(100)
        }

        val adapter: PhysicalWindowAdapter
            get() = PlayerController::class.java.getDeclaredField("physicalWindowAdapter").apply { isAccessible = true }.get(pc) as PhysicalWindowAdapter

        fun quiesce() { TestMediaSource.awaitCondition { !adapter.isBusy }; idleMainLooper(); rig.settle(60) }

        fun externalController(): MediaController {
            val future = MediaController.Builder(context, rig.session.token).setConnectionHints(Bundle()).buildAsync()
            var guard = 0
            while (!future.isDone && guard++ < 200) idleMainLooper()
            return future.get(5, TimeUnit.SECONDS).also { extra += it; idleMainLooper(); rig.settle(60) }
        }

        fun release() { extra.forEach { runCatching { it.release() } }; runCatching { pc.release() }; rig.releaseAll() }
    }

    private val hs = mutableListOf<H>()
    private fun h(bounded: Boolean = true) = H(bounded).also { hs += it }
    @After fun tearDown() { hs.forEach { it.release() }; hs.clear() }

    private fun queue(n: Int) = List(n) { RealMedia3QueueRig.song(it + 1L) }

    private fun MediaController.awaitIndex(expected: Int) = TestMediaSource.awaitCondition { currentMediaItemIndex == expected }

    // ── the complete logical queue is what every controller sees ──────────────────────────────────────────────────────────

    @Test fun everyControllerSeesTheCompleteLogicalQueueAtEverySize() {
        val report = StringBuilder()
        for ((size, start) in listOf(1 to 0, 300 to 150, 301 to 150, 1_000 to 400, 5_000 to 2_500, 10_000 to 9_000, 25_000 to 12_345, 50_000 to 49_999)) {
            val h = h()
            LogicalQueueTimelineStats.reset()
            h.pc.playFromQueue(queue(size), start)
            h.quiesce()
            val bounded = size > PhysicalWindowPolicy.FULL_MODE_MAX
            val readsBeforeControllers = LogicalQueueTimelineStats.windowReads
            val a = h.externalController()
            val b = h.externalController()
            for (c in listOf(h.rig.controller, a, b)) {
                assertEquals("size=$size count seen by a controller", size, c.mediaItemCount)
                assertEquals("size=$size logical index", start, c.currentMediaItemIndex)
                assertEquals((start + 1).toString(), c.currentMediaItem?.mediaId)
                for (probe in listOf(0, size / 2, size - 1)) assertEquals((probe + 1).toString(), c.getMediaItemAt(probe).mediaId)
            }
            val physical = h.rig.player.mediaItemCount
            if (bounded) assertTrue("size=$size physical window bounded, was $physical", physical in 1..PhysicalWindowPolicy.W_MAX)
            else assertEquals("Full mode keeps the whole queue physical", size, physical)
            report.append("size=$size bounded=$bounded controllerCount=${a.mediaItemCount} physical=$physical windowReads=${LogicalQueueTimelineStats.windowReads}\n")
            h.release(); hs.remove(h)
        }
        println("LOGICAL-SIZES\n$report")
    }

    @Test fun duplicateSongsAreDistinctOccurrencesWithStableIdentityWithinAGeneration() {
        val h = h()
        val q = List(2_000) { RealMedia3QueueRig.song(longArrayOf(1, 2, 1, 3, 1)[it % 5]) }
        h.pc.playFromQueue(q, 1_000)
        h.quiesce()
        val ext = h.externalController()
        val window = Timeline.Window()
        val uids = listOf(1_000, 1_002, 1_004).map { ext.currentTimeline.getWindow(it, window).uid }
        assertEquals("three entries of the same song", 3, uids.toSet().size)
        val before = ext.currentTimeline.getWindow(1_000, window).uid
        repeat(60) { h.pc.skipToNext(); h.quiesce() } // the physical window refills several times
        ext.awaitIndex(1_060)
        assertEquals("identity of an entry survives physical refills", before, ext.currentTimeline.getWindow(1_000, window).uid)
    }

    @Test fun physicalRefillProducesNoQueueChangeEventForAnyController() {
        val h = h()
        h.pc.playFromQueue(queue(25_000), 5_000)
        h.quiesce()
        val ext = h.externalController()
        val events = mutableListOf<String>()
        ext.addListener(object : Player.Listener {
            override fun onTimelineChanged(timeline: Timeline, reason: Int) { events += "timeline($reason,${timeline.windowCount})" }
        })
        val installsBefore = h.rig.recording.count("setMediaItems")
        val opsBefore = h.rig.recording.calls.size
        LogicalQueueTimelineStats.reset()
        repeat(90) { h.pc.skipToNext(); h.quiesce() }
        ext.awaitIndex(5_090)
        val physicalOps = h.rig.recording.calls.drop(opsBefore)
        assertTrue("the physical window really did refill: $physicalOps", physicalOps.any { it.startsWith("add") || it.startsWith("append") || it.startsWith("remove") })
        assertEquals(installsBefore, h.rig.recording.count("setMediaItems"))
        // 90 navigation steps with a refill: Media3 never pulls the 25k-entry queue again, and nothing here builds it either.
        assertTrue("windows produced during steady navigation (${LogicalQueueTimelineStats.windowReads}) must not scale with the queue", LogicalQueueTimelineStats.windowReads < 90 * 100L)
        println("STEADY-NAV windowReads=${LogicalQueueTimelineStats.windowReads} for 90 steps at 25000")
        assertFalse("refill/trim must not look like a queue change: $events", events.any { it.startsWith("timeline(0,") })
        assertTrue(events.all { it.endsWith(",25000)") })
    }

    // ── external commands ──────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun externalNextPreviousAndSeekToIndexAreLogicalCommands() {
        val h = h()
        h.pc.playFromQueue(queue(25_000), 12_000)
        h.quiesce()
        val ext = h.externalController()
        ext.seekToNextMediaItem(); ext.awaitIndex(12_001); h.quiesce()
        assertEquals(12_001, h.pc.nowPlayingState.value.currentIndex)
        assertEquals("12002", h.rig.player.currentMediaItem?.mediaId)
        ext.seekToPreviousMediaItem(); ext.awaitIndex(12_000); h.quiesce()
        assertEquals(12_000, h.pc.nowPlayingState.value.currentIndex)
        // A logical index far outside the physical window: the app re-anchors, the controller never sees a physical index.
        ext.seekTo(20_000, 0L); ext.awaitIndex(20_000); h.quiesce()
        assertEquals(20_000, h.pc.nowPlayingState.value.currentIndex)
        assertEquals("20001", h.rig.player.currentMediaItem?.mediaId)
        assertTrue(h.rig.player.mediaItemCount <= PhysicalWindowPolicy.W_MAX)
        assertEquals(25_000, ext.mediaItemCount)
    }

    @Test fun externalSeekInsideTheCurrentItemReachesThePhysicalPlayerAtItsOwnIndex() {
        val h = h()
        h.pc.playFromQueue(queue(25_000), 12_000)
        h.quiesce()
        val ext = h.externalController()
        val before = h.rig.recording.calls.size
        ext.seekTo(30_000L)
        TestMediaSource.awaitCondition { h.rig.player.currentPosition >= 29_000L }
        val seeks = h.rig.recording.calls.drop(before).filter { it.startsWith("seekTo(") }
        assertTrue("one physical seek at the PHYSICAL current index: $seeks", seeks.any { it.startsWith("seekTo(${h.rig.player.currentMediaItemIndex},30000)") })
        assertEquals(12_000, ext.currentMediaItemIndex)
    }

    @Test fun externalRepeatAndShuffleAreRoutedToTheLogicalOwner() {
        val h = h()
        h.pc.playFromQueue(queue(5_000), 2_000)
        h.quiesce()
        val ext = h.externalController()
        ext.repeatMode = Player.REPEAT_MODE_ALL
        TestMediaSource.awaitCondition { h.pc.nowPlayingState.value.repeatMode == RepeatMode.ALL }
        h.quiesce()
        assertEquals("the controller sees the LOGICAL repeat", Player.REPEAT_MODE_ALL, ext.repeatMode)
        assertEquals("the physical mirror is never ALL", Player.REPEAT_MODE_OFF, h.rig.player.repeatMode)
        ext.repeatMode = Player.REPEAT_MODE_ONE
        TestMediaSource.awaitCondition { h.pc.nowPlayingState.value.repeatMode == RepeatMode.ONE }
        h.quiesce()
        assertEquals(Player.REPEAT_MODE_ONE, h.rig.player.repeatMode)
        ext.shuffleModeEnabled = true
        TestMediaSource.awaitCondition { h.pc.nowPlayingState.value.shuffleEnabled }
        h.quiesce()
        assertEquals(5_000, ext.mediaItemCount)
        assertFalse("native shuffle stays off on the physical player", h.rig.player.shuffleModeEnabled)
    }

    @Test fun structuralPlaylistCommandsAreUnavailableAndRejectedNeverSilentlyApplied() {
        val h = h()
        h.pc.playFromQueue(queue(5_000), 2_000)
        h.quiesce()
        val ext = h.externalController()
        for (command in listOf(Player.COMMAND_CHANGE_MEDIA_ITEMS, Player.COMMAND_SET_MEDIA_ITEM)) {
            assertFalse("command $command must not be advertised", ext.isCommandAvailable(command))
        }
        assertTrue(ext.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))
        assertTrue(ext.isCommandAvailable(Player.COMMAND_SEEK_TO_MEDIA_ITEM))
        val ops = h.rig.recording.calls.size
        ext.addMediaItem(RealMedia3QueueRig.song(99_999L).toPlaybackMediaItem())
        ext.removeMediaItem(0)
        ext.clearMediaItems()
        ext.setMediaItem(RealMedia3QueueRig.song(88_888L).toPlaybackMediaItem())
        h.rig.settle(200)
        assertEquals("no physical edit happened", ops, h.rig.recording.calls.size)
        assertEquals(5_000, ext.mediaItemCount)
        assertEquals(2_000, h.pc.nowPlayingState.value.currentIndex)
        assertEquals(5_000, h.pc.nowPlayingState.value.queue.size)
    }

    // ── lazy timeline unit behaviour ───────────────────────────────────────────────────────────────────────────────────────

    @Test fun timelineIdentityHashesDoNotCancelAndLookupsAreExact() {
        val songs = queue(50_000)
        val timeline = LogicalQueueTimeline(songs, generation = 7L, currentIndex = 25_000, currentDurationUs = C.TIME_UNSET)
        assertEquals(50_000, timeline.windowCount)
        assertEquals(50_000, timeline.periodCount)
        val window = Timeline.Window()
        val period = Timeline.Period()
        for (i in listOf(0, 1, 2, 24_999, 25_000, 49_998, 49_999)) {
            timeline.getWindow(i, window); timeline.getPeriod(i, period, true)
            assertEquals(i, timeline.getIndexOfPeriod(period.uid!!))
            assertEquals(i, window.firstPeriodIndex)
        }
        val xor = HashSet<Int>()
        for (i in 0 until 50_000) xor += (LogicalOccurrenceUid(7L, i, false).hashCode() xor LogicalOccurrenceUid(7L, i, true).hashCode())
        assertTrue("window/period XOR hashes must spread (distinct=${xor.size})", xor.size > 49_900)
        assertEquals("a uid of another generation is unknown", C.INDEX_UNSET, timeline.getIndexOfPeriod(LogicalOccurrenceUid(8L, 5, true)))
        assertEquals(timeline, LogicalQueueTimeline(songs, 7L, 25_000, C.TIME_UNSET))
        assertFalse(timeline == LogicalQueueTimeline(songs, 8L, 25_000, C.TIME_UNSET))
    }
}
