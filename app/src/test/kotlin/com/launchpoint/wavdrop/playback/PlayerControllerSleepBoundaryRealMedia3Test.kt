package com.launchpoint.wavdrop.playback

import android.content.Context
import android.os.Looper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.playback.PlaybackSessionRepository
import com.launchpoint.wavdrop.data.repository.PlayEventWriter
import com.launchpoint.wavdrop.data.settings.AppSettingsRepository
import com.launchpoint.wavdrop.data.settings.ResumeBehaviorSettingsRepository
import java.io.File
import java.time.Duration
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

/**
 * ST-1: the PRODUCTION [PlayerController] sleep timer against REAL Media3 (a genuine ExoPlayer behind a genuine MediaSession,
 * driven through a genuine MediaController) with the Robolectric clock advanced so tracks REALLY play to their natural end.
 *
 * The service that hosts the session does not exist under Robolectric, so the test registers the same boundary listener
 * PlaybackService registers (arm/release the physical pause-at-end on the player); PlaybackServiceSleepBoundaryWiringTest pins
 * that the service does exactly that. One test deliberately omits it to prove the fail-closed fallback.
 *
 * Boundary (what remains for physical QA): real codecs and files, the PlaybackService lifecycle, audio focus, notification and
 * Bluetooth controls, and the gated two-slot crossfade engine live on a device (the engine rules are proven separately in
 * SleepBoundaryCrossfadeTest).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class PlayerControllerSleepBoundaryRealMedia3Test {

    private class Writer : PlayEventWriter {
        val plays = mutableListOf<Long>()
        val skips = mutableListOf<Long>()
        val listenStarts = mutableListOf<Long>()
        override suspend fun recordPlay(songId: Long, contentUri: String, listenedMs: Long, durationMs: Long) { plays += songId }
        override suspend fun recordSkip(songId: Long, contentUri: String, durationMs: Long) { skips += songId }
        override suspend fun recordListenStart(songId: Long, contentUri: String) { listenStarts += songId }
    }

    private inner class Harness(wireService: Boolean = true) {
        val context: Context = RuntimeEnvironment.getApplication()
        val rig = RealMedia3QueueRig(emptyList(), 0, loadInitialQueue = false, sessionName = "sleep")
        val writer = Writer()
        var clockMs = 0L
        val tracker = StatsTracker(writer).also {
            it.scope = CoroutineScope(Dispatchers.Unconfined)
            it.clock = { clockMs }
        }
        private fun store(name: String) = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + Job()),
            produceFile = { File.createTempFile(name, ".preferences_pb").also { f -> f.delete(); f.deleteOnExit() } },
        )
        val pc = PlayerController(
            context, tracker, PlaybackSessionRepository(store("sleepsession")), ResumeBehaviorSettingsRepository(store("sleepresume")), AppSettingsRepository(store("sleepapp")),
        )
        val controller: MediaController get() = rig.controller

        /** Every arm (true) / release (false) the service would have received. */
        val armEvents = mutableListOf<Boolean>()

        init {
            field("controllerConnectionState").set(pc, ControllerConnectionState.Connected)
            invoke("onControllerConnected", arrayOf(MediaController::class.java), arrayOf(rig.controller))
            rig.settle(100)
            if (wireService) {
                // Exactly what PlaybackService does for the physical player.
                pc.setSleepBoundaryListener { armed ->
                    armEvents += armed
                    rig.player.pauseAtEndOfMediaItems = armed
                }
                // Registering replays the current (unarmed) state once, so a recreated service re-applies a held boundary.
                assertEquals(listOf(false), armEvents)
                armEvents.clear()
            }
        }

        fun field(name: String) = PlayerController::class.java.getDeclaredField(name).apply { isAccessible = true }
        fun invoke(name: String, types: Array<Class<*>>, args: Array<Any?>): Any? =
            PlayerController::class.java.getDeclaredMethod(name, *types).apply { isAccessible = true }.invoke(pc, *args)

        fun start(queue: List<Song>, index: Int) {
            pc.playFromQueue(queue, index)
            rig.settle(300)
        }

        /** Advances the fake clock so real playback time (and coroutine delays) pass. */
        fun advance(ms: Long) {
            var left = ms
            while (left > 0) {
                val step = minOf(50L, left)
                ShadowSystemClock.advanceBy(Duration.ofMillis(step))
                clockMs += step
                Thread.sleep(8)
                shadowOf(Looper.getMainLooper()).idle()
                left -= step
            }
            rig.settle(40)
        }

        /** Jumps the CURRENT item to just before its natural end (a user scrub), then lets it play out. */
        fun playToEnd() {
            controller.seekTo(controller.duration - 400L)
            rig.settle(100)
            advance(1_500)
        }

        val sleep: SleepTimerState get() = pc.sleepTimerState.value
        val index: Int get() = controller.currentMediaItemIndex
        fun releaseAll() { runCatching { pc.release() }; rig.releaseAll() }
    }

    private val harnesses = mutableListOf<Harness>()
    private fun harness(wireService: Boolean = true) = Harness(wireService).also { harnesses += it }
    @After fun tearDown() { harnesses.forEach { it.releaseAll() }; harnesses.clear() }

    private fun song(id: Long) = RealMedia3QueueRig.song(id)
    private fun songs(vararg ids: Long) = ids.map(::song)

    // ── A. duration + Finish OFF: immediate pause ───────────────────────────────────────────────────────────────────────

    @Test fun `finish OFF pauses immediately at expiry and clears the timer`() {
        val h = harness()
        h.start(songs(1, 2, 3), 1)
        assertTrue(h.controller.isPlaying)
        h.pc.setCustomSleepTimer(1_000L, finishCurrentTrack = false)
        assertTrue(h.sleep.isCountingDown)
        h.advance(1_300)
        assertFalse("paused at expiry", h.controller.playWhenReady)
        assertEquals(SleepTimerState(), h.sleep)
        assertEquals("no boundary was ever armed", emptyList<Boolean>(), h.armEvents.filter { it })
        assertEquals(1, h.index)
    }

    // ── B. duration + Finish ON: keeps playing, stops at the natural end ────────────────────────────────────────────────

    @Test fun `finish ON keeps playing at expiry, arms the exact occurrence and stops at its natural end`() {
        val h = harness()
        h.start(songs(1, 2, 3), 1)
        h.pc.setCustomSleepTimer(1_000L, finishCurrentTrack = true)
        assertTrue(h.sleep.finishCurrentTrack)
        h.advance(1_300)

        assertTrue("still playing: no immediate pause", h.controller.playWhenReady)
        assertTrue(h.sleep.isFinishingCurrentTrack)
        assertNull("the countdown is over", h.sleep.endsAtMs)
        assertEquals(1, h.sleep.terminal!!.playbackIndex)
        assertEquals(2L, h.sleep.terminal!!.songId)
        assertEquals(listOf(true), h.armEvents)
        assertTrue(h.pc.captureCrossfadeRuntimeSnapshot().sleepBoundaryArmed)
        assertTrue(h.rig.player.pauseAtEndOfMediaItems)

        h.playToEnd()
        assertFalse("stopped", h.controller.playWhenReady)
        assertEquals("stopped ON the terminal occurrence, not advanced", 1, h.index)
        assertEquals(SleepTimerState(), h.sleep)
        assertEquals(listOf(true, false), h.armEvents)
        assertFalse(h.rig.player.pauseAtEndOfMediaItems)
        assertFalse(h.pc.captureCrossfadeRuntimeSnapshot().sleepBoundaryArmed)
        assertEquals("the next occurrence is not the logical Now Playing", 2L, h.pc.nowPlayingState.value.song?.id)
        assertEquals(1, h.pc.nowPlayingState.value.currentIndex)
        assertFalse("stopping at the boundary is not a skip", h.writer.skips.contains(2L))
        assertFalse("the next track never received a selection", h.writer.listenStarts.contains(3L))
    }

    @Test fun `later ordinary play after the boundary continues normally`() {
        val h = harness()
        h.start(songs(1, 2, 3), 0)
        h.pc.setCustomSleepTimer(1_000L, true)
        h.advance(1_300)
        h.playToEnd()
        assertEquals(0, h.index)
        h.controller.play()
        h.rig.settle(300)
        h.advance(400)
        assertEquals("the expired timer does not resurrect; playback moved on", 1, h.index)
        assertTrue(h.controller.playWhenReady)
        assertEquals(SleepTimerState(), h.sleep)
    }

    // ── C. standalone End of current song ───────────────────────────────────────────────────────────────────────────────

    @Test fun `standalone end of current song arms immediately and stops at the natural end`() {
        val h = harness()
        h.start(songs(1, 2, 3), 1)
        h.pc.setSleepTimer(SleepTimerOption.END_OF_CURRENT_SONG)
        assertTrue(h.sleep.isFinishingCurrentTrack)
        assertEquals(1, h.sleep.terminal!!.playbackIndex)
        assertEquals(listOf(true), h.armEvents)
        h.playToEnd()
        assertFalse(h.controller.playWhenReady)
        assertEquals(1, h.index)
        assertEquals(SleepTimerState(), h.sleep)
    }

    @Test fun `standalone with nothing loaded does not arm anything`() {
        val h = harness()
        h.pc.setSleepTimer(SleepTimerOption.END_OF_CURRENT_SONG)
        assertEquals(SleepTimerState(), h.sleep)
    }

    @Test fun `standalone armed while paused stops at the end once playback is resumed`() {
        val h = harness()
        h.start(songs(1, 2, 3), 1)
        h.controller.pause(); h.rig.settle(150)
        h.pc.setSleepTimer(SleepTimerOption.END_OF_CURRENT_SONG)
        assertTrue("armed against the paused current occurrence", h.sleep.isFinishingCurrentTrack)
        h.controller.play(); h.rig.settle(200)
        h.playToEnd()
        assertEquals(1, h.index)
        assertFalse(h.controller.playWhenReady)
        assertEquals(SleepTimerState(), h.sleep)
    }

    // ── repeat modes: the boundary outranks them without touching the saved mode ────────────────────────────────────────

    private fun Harness.setRepeat(mode: RepeatMode) {
        while (pc.nowPlayingState.value.repeatMode != mode) pc.cycleRepeatMode()
        rig.settle(100)
    }

    @Test fun `repeat ONE does not replay the armed occurrence and the saved mode is untouched`() {
        val h = harness()
        h.start(songs(1, 2, 3), 1)
        h.setRepeat(RepeatMode.ONE)
        h.pc.setSleepTimer(SleepTimerOption.END_OF_CURRENT_SONG)
        h.playToEnd()
        assertFalse(h.controller.playWhenReady)
        assertEquals(1, h.index)
        assertTrue("paused at the END, not restarted", h.controller.currentPosition >= h.controller.duration - 1_000L)
        assertEquals(RepeatMode.ONE, h.pc.nowPlayingState.value.repeatMode)
        assertEquals(Player.REPEAT_MODE_ONE, h.controller.repeatMode)
    }

    @Test fun `repeat ALL in the middle does not advance`() {
        val h = harness()
        h.start(songs(1, 2, 3), 1)
        h.setRepeat(RepeatMode.ALL)
        h.pc.setCustomSleepTimer(1_000L, true)
        h.advance(1_300)
        h.playToEnd()
        assertFalse(h.controller.playWhenReady)
        assertEquals(1, h.index)
        assertEquals(RepeatMode.ALL, h.pc.nowPlayingState.value.repeatMode)
    }

    @Test fun `repeat ALL on the final item does not wrap`() {
        val h = harness()
        h.start(songs(1, 2, 3), 2)
        h.setRepeat(RepeatMode.ALL)
        h.pc.setSleepTimer(SleepTimerOption.END_OF_CURRENT_SONG)
        h.playToEnd()
        assertFalse(h.controller.playWhenReady)
        assertEquals("did not wrap to the first item", 2, h.index)
    }

    @Test fun `repeat ALL on the first item does not advance`() {
        val h = harness()
        h.start(songs(1, 2, 3), 0)
        h.setRepeat(RepeatMode.ALL)
        h.pc.setSleepTimer(SleepTimerOption.END_OF_CURRENT_SONG)
        h.playToEnd()
        assertEquals(0, h.index)
        assertFalse(h.controller.playWhenReady)
    }

    @Test fun `repeat OFF on the final item stops there`() {
        val h = harness()
        h.start(songs(1, 2, 3), 2)
        h.pc.setSleepTimer(SleepTimerOption.END_OF_CURRENT_SONG)
        h.playToEnd()
        assertEquals(2, h.index)
        assertFalse(h.controller.playWhenReady)
        assertEquals(SleepTimerState(), h.sleep)
    }

    @Test fun `a single item queue stops at its end`() {
        val h = harness()
        h.start(songs(1), 0)
        h.setRepeat(RepeatMode.ONE)
        h.pc.setSleepTimer(SleepTimerOption.END_OF_CURRENT_SONG)
        h.playToEnd()
        assertEquals(0, h.index)
        assertFalse(h.controller.playWhenReady)
    }

    // ── duplicates and shuffle ──────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `a duplicate song binds the occurrence that was playing at expiry`() {
        val h = harness()
        h.start(songs(1, 2, 1, 3), 2) // song 1 occurs at positions 0 and 2; position 2 is playing
        h.pc.setCustomSleepTimer(1_000L, true)
        h.advance(1_300)
        assertEquals(2, h.sleep.terminal!!.playbackIndex)
        h.playToEnd()
        assertEquals("the second occurrence is terminal; not the first, not the next", 2, h.index)
        assertFalse(h.controller.playWhenReady)
        assertEquals(SleepTimerState(), h.sleep)
    }

    @Test fun `shuffle toggle while armed keeps the same song as the terminal occurrence`() {
        val h = harness()
        h.start(songs(1, 2, 3, 4, 5), 2)
        h.pc.setSleepTimer(SleepTimerOption.END_OF_CURRENT_SONG)
        val songBefore = h.sleep.terminal!!.songId
        h.pc.toggleShuffle()
        h.rig.settle(400)
        assertTrue("still armed after the shuffle", h.sleep.isFinishingCurrentTrack)
        assertEquals(songBefore, h.sleep.terminal!!.songId)
        assertEquals("re-bound to the exact occurrence now playing", h.pc.nowPlayingState.value.currentIndex, h.sleep.terminal!!.playbackIndex)
        val indexAtEnd = h.index
        h.playToEnd()
        assertFalse(h.controller.playWhenReady)
        assertEquals("stopped on the same occurrence", indexAtEnd, h.index)
        assertEquals(songBefore, h.pc.nowPlayingState.value.song?.id)
    }

    // ── user intent after the boundary is armed ─────────────────────────────────────────────────────────────────────────

    @Test fun `manual pause clears the boundary and a later play continues normally`() {
        val h = harness()
        h.start(songs(1, 2, 3), 1)
        h.pc.setSleepTimer(SleepTimerOption.END_OF_CURRENT_SONG)
        h.pc.togglePlayPause() // pause
        h.rig.settle(300)
        assertFalse(h.controller.playWhenReady)
        assertEquals(SleepTimerState(), h.sleep)
        assertFalse(h.rig.player.pauseAtEndOfMediaItems)
        h.pc.togglePlayPause() // play
        h.rig.settle(300)
        h.playToEnd()
        assertEquals("ordinary playback advanced past the song: the expired boundary did not resurrect", 2, h.index)
    }

    @Test fun `manual next clears the boundary then navigates`() {
        val h = harness()
        h.start(songs(1, 2, 3), 0)
        h.pc.setSleepTimer(SleepTimerOption.END_OF_CURRENT_SONG)
        h.pc.skipToNext()
        h.rig.settle(300)
        assertEquals(1, h.index)
        assertEquals(SleepTimerState(), h.sleep)
        assertFalse(h.rig.player.pauseAtEndOfMediaItems)
        h.playToEnd()
        assertEquals("not carried onto the newly selected track", 2, h.index)
    }

    @Test fun `manual previous clears the boundary then navigates`() {
        val h = harness()
        h.start(songs(1, 2, 3), 2)
        h.pc.setCustomSleepTimer(1_000L, true)
        h.advance(1_300)
        assertTrue(h.sleep.isFinishingCurrentTrack)
        h.controller.seekTo(h.controller.duration / 2) // past the restart threshold? position far beyond it
        h.rig.settle(100)
        h.pc.skipToPrevious() // restarts or moves back; either way explicit navigation supersedes the boundary
        h.rig.settle(300)
        assertEquals(SleepTimerState(), h.sleep)
        assertFalse(h.rig.player.pauseAtEndOfMediaItems)
    }

    @Test fun `a seek inside the armed occurrence keeps the boundary`() {
        val h = harness()
        h.start(songs(1, 2, 3), 1)
        h.pc.setSleepTimer(SleepTimerOption.END_OF_CURRENT_SONG)
        h.pc.seekTo(30_000L)
        h.rig.settle(200)
        assertTrue(h.sleep.isFinishingCurrentTrack)
        h.pc.seekTo(5_000L)
        h.rig.settle(200)
        assertTrue(h.sleep.isFinishingCurrentTrack)
        h.playToEnd()
        assertEquals(1, h.index)
        assertFalse(h.controller.playWhenReady)
    }

    @Test fun `selecting another song or replacing the queue clears the boundary`() {
        val h = harness()
        h.start(songs(1, 2, 3), 1)
        h.pc.setSleepTimer(SleepTimerOption.END_OF_CURRENT_SONG)
        h.pc.playFromQueue(songs(7, 8, 9), 2)
        h.rig.settle(300)
        assertEquals(SleepTimerState(), h.sleep)
        assertFalse(h.rig.player.pauseAtEndOfMediaItems)
        assertEquals(2, h.index)
    }

    @Test fun `changing repeat while armed retains the boundary`() {
        val h = harness()
        h.start(songs(1, 2, 3), 1)
        h.pc.setSleepTimer(SleepTimerOption.END_OF_CURRENT_SONG)
        repeat(3) { h.pc.cycleRepeatMode(); h.rig.settle(100) }
        assertTrue(h.sleep.isFinishingCurrentTrack)
        h.setRepeat(RepeatMode.ALL)
        h.playToEnd()
        assertEquals(1, h.index)
        assertFalse(h.controller.playWhenReady)
    }

    @Test fun `off clears an armed boundary and Off while counting down never arms`() {
        val h = harness()
        h.start(songs(1, 2, 3), 1)
        h.pc.setSleepTimer(SleepTimerOption.END_OF_CURRENT_SONG)
        h.pc.setSleepTimer(SleepTimerOption.OFF)
        assertEquals(SleepTimerState(), h.sleep)
        assertFalse(h.rig.player.pauseAtEndOfMediaItems)
        h.pc.setCustomSleepTimer(1_000L, true)
        h.pc.setSleepTimer(SleepTimerOption.OFF)
        h.advance(1_500)
        assertTrue("an Off timer never fires", h.controller.playWhenReady)
        assertEquals(SleepTimerState(), h.sleep)
    }

    @Test fun `a new duration replaces an armed boundary and re-arming binds the current occurrence`() {
        val h = harness()
        h.start(songs(1, 2, 3), 1)
        h.pc.setCustomSleepTimer(1_000L, true)
        h.advance(1_300)
        assertTrue(h.sleep.isFinishingCurrentTrack)
        h.pc.setSleepTimer(SleepTimerOption.MINUTES_15, finishCurrentTrack = false)
        assertTrue(h.sleep.isCountingDown)
        assertFalse(h.sleep.finishCurrentTrack)
        assertFalse("the physical hold is released", h.rig.player.pauseAtEndOfMediaItems)
        h.pc.skipToNext(); h.rig.settle(300)
        h.pc.setSleepTimer(SleepTimerOption.END_OF_CURRENT_SONG)
        assertEquals(2, h.sleep.terminal!!.playbackIndex)
    }

    @Test fun `expiry while paused clears instead of arming a dormant boundary`() {
        val h = harness()
        h.start(songs(1, 2, 3), 1)
        h.controller.pause(); h.rig.settle(150)
        h.pc.setCustomSleepTimer(1_000L, true)
        h.advance(1_300)
        assertEquals(SleepTimerState(), h.sleep)
        assertEquals(emptyList<Boolean>(), h.armEvents.filter { it })
    }

    // ── fail-closed fallback when the physical hold is not in effect ────────────────────────────────────────────────────

    @Test fun `if the physical hold is missing the boundary still stops playback and the next track gets no stats`() {
        val h = harness(wireService = false) // no service: the player is NOT told to pause at the end of items
        h.start(songs(1, 2, 3), 1)
        h.pc.setSleepTimer(SleepTimerOption.END_OF_CURRENT_SONG)
        h.playToEnd()
        h.advance(500)
        assertFalse("stopped by the defensive path", h.controller.playWhenReady)
        assertEquals(SleepTimerState(), h.sleep)
        assertFalse("no selection/play was fabricated for the track that must not have started", h.writer.listenStarts.contains(3L))
        assertFalse(h.writer.skips.contains(3L))
    }

    @Test fun `registering the service listener replays an already armed boundary once`() {
        val h = harness(wireService = false)
        h.start(songs(1, 2, 3), 1)
        h.pc.setSleepTimer(SleepTimerOption.END_OF_CURRENT_SONG)
        val seen = mutableListOf<Boolean>()
        h.pc.setSleepBoundaryListener { seen += it }
        assertEquals(listOf(true), seen)
        h.pc.setSleepBoundaryListener(null)
        assertEquals(listOf(true), seen)
    }

    // ── process-lifetime only ───────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `a new controller instance (process recreation) has no timer and no boundary`() {
        val h = harness()
        h.start(songs(1, 2, 3), 1)
        h.pc.setSleepTimer(SleepTimerOption.END_OF_CURRENT_SONG)
        val fresh = harness()
        assertEquals(SleepTimerState(), fresh.sleep)
        assertNotNull(h.sleep.terminal)
    }
}
