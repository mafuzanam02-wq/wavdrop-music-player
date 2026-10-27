package com.launchpoint.wavdrop.playback

import android.content.Context
import android.os.Bundle
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.media3.common.Player
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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Explicit song selection starts the selected song, even from a paused state, in Full and Bounded modes; everything that is NOT a
 * deliberate selection of another occurrence keeps its paused state. Real Media3, the real physical acknowledgement. Each test
 * asserts the LOGICAL Now Playing state and the PHYSICAL player together.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class ExplicitSelectionAutoplayTest {

    private class Writer : PlayEventWriter {
        override suspend fun recordPlay(songId: Long, contentUri: String, listenedMs: Long, durationMs: Long) = Unit
        override suspend fun recordSkip(songId: Long, contentUri: String, durationMs: Long) = Unit
        override suspend fun recordListenStart(songId: Long, contentUri: String) = Unit
    }

    private fun store(name: String) = PreferenceDataStoreFactory.create(
        scope = CoroutineScope(Dispatchers.IO + Job()),
        produceFile = { File.createTempFile(name, ".preferences_pb").also { f -> f.delete(); f.deleteOnExit() } },
    )

    private fun newController(context: Context) = PlayerController(
        context,
        StatsTracker(Writer()).also { it.scope = CoroutineScope(Dispatchers.Unconfined); it.clock = { 0L } },
        PlaybackSessionRepository(store("sessionstore")),
        ResumeBehaviorSettingsRepository(store("resumestore")),
        AppSettingsRepository(store("appstore")),
    )

    // ── connected ───────────────────────────────────────────────────────────────────────────────────────────────────────

    private inner class Harness(val bounded: Boolean) {
        val context: Context = RuntimeEnvironment.getApplication()
        val rig = RealMedia3QueueRig(emptyList(), 0, loadInitialQueue = false, sessionName = "esa", physicalAck = true)
        val pc = newController(context)
        val controller: MediaController get() = rig.controller
        val calls get() = rig.recording.calls

        init {
            pc.boundedInstallEnabledForTesting = bounded
            pc.attachPhysicalWindowPort(rig.port)
            field("controllerConnectionState").set(pc, ControllerConnectionState.Connected)
            PlayerController::class.java.getDeclaredMethod("onControllerConnected", MediaController::class.java)
                .apply { isAccessible = true }.invoke(pc, rig.controller)
            rig.settle(100)
        }

        fun field(name: String) = PlayerController::class.java.getDeclaredField(name).apply { isAccessible = true }
        val adapter: PhysicalWindowAdapter get() = field("physicalWindowAdapter").get(pc) as PhysicalWindowAdapter

        fun settle() { TestMediaSource.awaitCondition { !adapter.isBusy }; idleMainLooper(); rig.settle(100) }
        fun start(queue: List<Song>, index: Int, playing: Boolean) {
            pc.playFromQueue(queue, index)
            settle()
            if (!playing) { controller.pause(); rig.settle(100) }
            assertEquals("precondition", playing, rig.player.playWhenReady)
        }

        val logical: Int get() = pc.nowPlayingState.value.currentIndex
        val currentId: String? get() = controller.currentMediaItem?.mediaId
        fun physicalPlaying() = rig.player.playWhenReady
        fun releaseAll() { runCatching { pc.release() }; rig.releaseAll() }

        /** Logical Now Playing and the physical player agree on the selected occurrence and its play state. */
        fun assertSelectedAndPlaying(index: Int, songId: Long, playing: Boolean = true) {
            assertEquals("logical index", index, logical)
            assertEquals("logical song", songId, pc.nowPlayingState.value.song?.id)
            assertEquals("physical item", songId.toString(), currentId)
            assertEquals("physical playWhenReady", playing, physicalPlaying())
            assertEquals("controller playWhenReady", playing, controller.playWhenReady)
        }
    }

    private val harnesses = mutableListOf<Harness>()
    private val dharnesses = mutableListOf<DHarness>()
    private fun harness(bounded: Boolean) = Harness(bounded).also { harnesses += it }
    private fun dharness() = DHarness().also { dharnesses += it }

    @After fun tearDown() {
        harnesses.forEach { it.releaseAll() }; harnesses.clear()
        dharnesses.forEach { it.releaseAll() }; dharnesses.clear()
        authorityRigs.forEach { it.releaseAll() }; authorityRigs.clear()
    }

    private fun queue(n: Int) = List(n) { RealMedia3QueueRig.song(it + 1L) }
    private fun both(body: (Boolean) -> Unit) { for (bounded in listOf(false, true)) body(bounded) }

    @Test fun pausedNextSelectsTheNextOccurrenceAndPlaysIt() = both { bounded ->
        val h = harness(bounded)
        h.start(queue(30), 5, playing = false)
        h.pc.skipToNext(); h.settle()
        h.assertSelectedAndPlaying(6, 7L)
    }

    @Test fun pausedPreviousToADifferentOccurrencePlaysIt() = both { bounded ->
        val h = harness(bounded)
        h.start(queue(30), 5, playing = false)
        h.pc.skipToPrevious(); h.settle()
        h.assertSelectedAndPlaying(4, 5L)
    }

    @Test fun pausedPreviousThatRestartsTheCurrentSongStaysPaused() = both { bounded ->
        val h = harness(bounded)
        h.start(queue(30), 0, playing = false)
        h.pc.skipToPrevious(); h.settle()                      // index 0, Repeat OFF: restart-current, not a selection
        h.assertSelectedAndPlaying(0, 1L, playing = false)
    }

    @Test fun pausedQueueRowSelectionOfAnotherOccurrencePlays() = both { bounded ->
        val h = harness(bounded)
        h.start(queue(30), 5, playing = false)
        h.pc.jumpToQueueItem(12); h.settle()
        h.assertSelectedAndPlaying(12, 13L)
    }

    @Test fun pausedSelectionOfADuplicateAtAnotherIndexPlaysThatOccurrence() = both { bounded ->
        val a = RealMedia3QueueRig.song(1L)
        val q = listOf(a, RealMedia3QueueRig.song(2L), a, RealMedia3QueueRig.song(3L), a)
        val h = harness(bounded)
        h.start(q, 0, playing = false)
        h.pc.jumpToQueueItem(2); h.settle()
        h.assertSelectedAndPlaying(2, 1L)                     // same song, DIFFERENT occurrence
    }

    @Test fun pausedSeekWithinTheCurrentSongStaysPaused() = both { bounded ->
        val h = harness(bounded)
        h.start(queue(30), 5, playing = false)
        h.pc.seekTo(15_000L); h.settle()
        h.assertSelectedAndPlaying(5, 6L, playing = false)
    }

    @Test fun pausedSelectionOfTheCurrentOccurrenceIsARestartNotASelection() = both { bounded ->
        val h = harness(bounded)
        h.start(queue(30), 5, playing = false)
        h.pc.jumpToQueueItem(5); h.settle()
        h.assertSelectedAndPlaying(5, 6L, playing = false)
    }

    @Test fun pausedNextAtTheTrueQueueEndWithRepeatOffDoesNotStartAnything() = both { bounded ->
        val h = harness(bounded)
        h.start(queue(5), 4, playing = false)
        h.pc.skipToNext(); h.settle()
        h.assertSelectedAndPlaying(4, 5L, playing = false)
    }

    @Test fun pausedNextWrapsWithRepeatAllAndPlaysTheWrappedItem() = both { bounded ->
        val h = harness(bounded)
        h.start(queue(5), 4, playing = false)
        h.controller.repeatMode = Player.REPEAT_MODE_ALL; h.settle()
        h.pc.skipToNext(); h.settle()
        h.assertSelectedAndPlaying(0, 1L)
    }

    @Test fun pausedNextWithShuffleSelectsTheCorrectOccurrenceAndPlays() = both { bounded ->
        val h = harness(bounded)
        h.start(queue(30), 5, playing = false)
        h.pc.toggleShuffle(); h.settle()
        val before = h.logical
        h.pc.skipToNext(); h.settle()
        assertEquals(before + 1, h.logical)
        val song = h.pc.nowPlayingState.value.song!!
        h.assertSelectedAndPlaying(before + 1, song.id)
    }

    @Test fun pausedBoundedInWindowSelectionPlaysWithoutReinstalling() {
        val h = harness(true)
        h.start(queue(25_000), 12_500, playing = false)
        val before = h.calls.size
        h.pc.jumpToQueueItem(12_520); h.settle()
        h.assertSelectedAndPlaying(12_520, 12_521L)
        assertEquals("a mapped seek, no install", 0, h.calls.drop(before).count { it.startsWith("setMediaItems") })
    }

    @Test fun pausedBoundedOutOfWindowSelectionPlaysOnlyAfterTheReanchoredWindowIsVerified() {
        val h = harness(true)
        h.start(queue(25_000), 12_500, playing = false)
        h.pc.jumpToQueueItem(3)
        assertFalse("nothing plays while the new window is still unverified", h.physicalPlaying())
        h.settle()
        h.assertSelectedAndPlaying(3, 4L)
        assertTrue("bounded window, never the full queue", h.rig.player.mediaItemCount <= PhysicalWindowPolicy.W_MAX)
    }

    @Test fun aPauseDuringAPendingReanchorWins() {
        val h = harness(true)
        h.start(queue(25_000), 12_500, playing = false)
        h.pc.jumpToQueueItem(3)                                // re-anchor in flight, Play armed behind verification
        h.pc.togglePlayPause()                                 // the listener sees "about to play": this is a Pause
        h.settle()
        h.assertSelectedAndPlaying(3, 4L, playing = false)
    }

    @Test fun aNewerSelectionSupersedesAnOlderPendingOne() {
        val h = harness(true)
        h.start(queue(25_000), 12_500, playing = false)
        h.pc.jumpToQueueItem(3)
        h.pc.jumpToQueueItem(20_000)
        h.settle()
        h.assertSelectedAndPlaying(20_000, 20_001L)
    }

    @Test fun anExternalPauseDuringAPendingReanchorWins() {
        val h = harness(true)
        h.start(queue(25_000), 12_500, playing = false)
        h.pc.jumpToQueueItem(3)
        h.pc.onExplicitExternalTransport()
        h.controller.pause()
        h.settle()
        h.assertSelectedAndPlaying(3, 4L, playing = false)
    }

    private val authorityRigs = mutableListOf<AuthorityRig>()

    @Test fun externalNextPreviousAndSeekToAnotherItemPlayFromPausedButSameItemSeekDoesNot() {
        val rig = AuthorityRig("esa-ext").also { authorityRigs += it }
        rig.pc.playFromQueue(rig.queue, startIndex = 1)
        rig.connectApp()
        val external = rig.newExternalController()             // notification / lock screen / Bluetooth / widget
        external.pause(); rig.settle(150)
        assertFalse(rig.physical.playWhenReady)
        external.seekTo(7_000L); rig.settle(150)                // seek within the current song
        assertFalse("same-item seek stays paused", rig.physical.playWhenReady)
        external.seekToNextMediaItem(); rig.settle(200)
        assertEquals(2, rig.physical.currentMediaItemIndex)
        assertTrue("external Next starts the selected song", rig.physical.playWhenReady)
        external.pause(); rig.settle(150)
        external.seekToPreviousMediaItem(); rig.settle(200)
        assertEquals(1, rig.physical.currentMediaItemIndex)
        assertTrue("external Previous-to-another starts it", rig.physical.playWhenReady)
        external.pause(); rig.settle(150)
        external.seekTo(4, 0L); rig.settle(200)                 // seek to ANOTHER media item
        assertEquals(4, rig.physical.currentMediaItemIndex)
        assertTrue(rig.physical.playWhenReady)
        external.pause(); rig.settle(150)
        external.seekTo(4, 3_000L); rig.settle(200)             // seek to the CURRENT item's position
        assertFalse("seek to the current item is not a selection", rig.physical.playWhenReady)
    }

    @Test fun anExternalNextAtTheTrueQueueEndStartsNothing() {
        val rig = AuthorityRig("esa-end").also { authorityRigs += it }
        rig.pc.playFromQueue(rig.queue, startIndex = 4)
        rig.connectApp()
        val external = rig.newExternalController()
        external.pause(); rig.settle(150)
        external.seekToNextMediaItem(); rig.settle(200)
        assertEquals(4, rig.physical.currentMediaItemIndex)
        assertFalse(rig.physical.playWhenReady)
    }

    @Test fun backgroundRefillAndTrimNeverStartAPausedSession() {
        val h = harness(true)
        h.start(queue(25_000), 12_500, playing = false)
        val maintain = PlayerController::class.java.getDeclaredMethod("maintainBoundedWindow").apply { isAccessible = true }
        repeat(3) { maintain.invoke(h.pc); h.settle() }
        h.assertSelectedAndPlaying(12_500, 12_501L, playing = false)
    }

    @Test fun aQueueMutationThatDoesNotSelectNeverStartsAPausedSession() {
        val h = harness(true)
        h.start(queue(25_000), 12_500, playing = false)
        h.pc.moveQueueItemTo(12_510, 12_520); h.settle()
        h.assertSelectedAndPlaying(12_500, 12_501L, playing = false)
    }

    @Test fun reconnectingAPausedControllerWithoutASelectionDoesNotAutoplay() = both { bounded ->
        val h = harness(bounded)
        h.start(queue(30), 5, playing = false)
        PlayerController::class.java.getDeclaredMethod("onControllerConnected", MediaController::class.java)
            .apply { isAccessible = true }.invoke(h.pc, h.controller)
        h.settle()
        h.assertSelectedAndPlaying(5, 6L, playing = false)
    }

    // ── disconnected ────────────────────────────────────────────────────────────────────────────────────────────────────

    private inner class DHarness {
        val context: Context = RuntimeEnvironment.getApplication()
        val rig = RealMedia3QueueRig(emptyList(), 0, loadInitialQueue = false, sessionName = "esd")
        val pc = newController(context)
        val attempts = mutableListOf<SettableFuture<MediaController>>()
        private val controllers = mutableListOf<MediaController>()

        init {
            field("controllerConnectionState").set(pc, ControllerConnectionState.Connected)
            rig.settle(50)
            pc.controllerFutureFactory = { SettableFuture.create<MediaController>().also { attempts += it } }
            field("controllerConnectionState").set(pc, ControllerConnectionState.Disconnected)
        }

        fun field(name: String) = PlayerController::class.java.getDeclaredField(name).apply { isAccessible = true }
        fun complete(): MediaController {
            rig.settle(50)
            val future = MediaController.Builder(context, rig.session.token).setConnectionHints(Bundle()).buildAsync()
            var guard = 0
            while (!future.isDone && guard++ < 200) rig.settle(20)
            val c = future.get().also { controllers += it }
            attempts.last().set(c)
            rig.settle(400)
            return c
        }
        fun disconnect(c: MediaController) {
            (field("controllerLifecycleListener").get(pc) as MediaController.Listener).onDisconnected(c)
            rig.settle(50)
        }
        /** Starts playing, connects, then pauses and disconnects: the physical player is paused with no usable controller. */
        fun pausedAndDisconnected(): MediaController {
            pc.playFromQueue(queue(8), startIndex = 2)
            val c = complete()
            c.pause(); rig.settle(100)
            disconnect(c)
            return c
        }
        fun releaseAll() { runCatching { pc.release() }; controllers.forEach { runCatching { it.release() } }; rig.releaseAll() }
    }

    @Test fun aDeferredExplicitNextWhilePausedKeepsItsPlayIntentAndPlaysOnReconnect() {
        val d = dharness()
        d.pausedAndDisconnected()
        d.pc.skipToNext()
        d.complete()
        assertEquals(3, d.pc.nowPlayingState.value.currentIndex)
        assertEquals(3, d.rig.player.currentMediaItemIndex)
        assertTrue("plays on reconnect", d.rig.player.playWhenReady)
    }

    @Test fun aDeferredQueueRowSelectionWhilePausedPlaysOnReconnect() {
        val d = dharness()
        d.pausedAndDisconnected()
        d.pc.jumpToQueueItem(5)
        d.complete()
        assertEquals(5, d.rig.player.currentMediaItemIndex)
        assertTrue(d.rig.player.playWhenReady)
    }

    @Test fun anOlderDeferredPauseCannotOverrideANewerSelection() {
        val d = dharness()
        d.pc.playFromQueue(queue(8), startIndex = 2)
        val c = d.complete()
        d.disconnect(c)
        d.pc.togglePlayPause()                                  // deferred Pause (it was playing)
        d.pc.skipToNext()                                       // newer selection
        d.complete()
        assertEquals(3, d.rig.player.currentMediaItemIndex)
        assertTrue("the newer selection wins over the older Pause", d.rig.player.playWhenReady)
    }

    @Test fun aNewerDeferredPauseOverridesAnOlderSelectionsPlayIntent() {
        val d = dharness()
        d.pausedAndDisconnected()
        d.pc.skipToNext()                                       // older selection
        d.pc.togglePlayPause()                                  // newer explicit Pause
        d.complete()
        assertEquals(3, d.rig.player.currentMediaItemIndex)
        assertFalse(d.rig.player.playWhenReady)
    }

    @Test fun aNewerDeferredSelectionSupersedesOlderQueuedNavigation() {
        val d = dharness()
        d.pausedAndDisconnected()
        d.pc.skipToNext()
        d.pc.jumpToQueueItem(6)
        d.complete()
        assertEquals(6, d.rig.player.currentMediaItemIndex)
        assertTrue(d.rig.player.playWhenReady)
    }

    @Test fun aRejectedDeferredNavigationNeverPlaysThePreviousSong() {
        val d = dharness()
        d.pc.playFromQueue(queue(3), startIndex = 2)           // the last item
        val c = d.complete()
        c.pause(); d.rig.settle(100)
        d.disconnect(c)
        d.pc.skipToNext()                                       // no next item with Repeat OFF
        d.complete()
        assertEquals(2, d.rig.player.currentMediaItemIndex)
        assertFalse(d.rig.player.playWhenReady)
    }
}
