package com.launchpoint.wavdrop.playback

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import kotlin.math.abs

/**
 * LS-1 x ST-1: the session-facing position while the Sleep Timer is active. The PRODUCTION PlayerController drives the real
 * session chain (two real ExoPlayers -> PlayerEngine -> SessionFacade -> PreviousBehaviorPlayer -> MediaLibrarySession ->
 * MediaController). A running countdown must not disturb the position; once the finish-current-track boundary is armed the
 * position must keep advancing until the track REALLY ends, and only then stop.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class LockScreenProgressSleepTimerTest {

    private class Writer : PlayEventWriter {
        override suspend fun recordPlay(songId: Long, contentUri: String, listenedMs: Long, durationMs: Long) = Unit
        override suspend fun recordSkip(songId: Long, contentUri: String, durationMs: Long) = Unit
        override suspend fun recordListenStart(songId: Long, contentUri: String) = Unit
    }

    private inner class Harness {
        val rig = SessionProgressRig(itemCount = 3, loadQueue = false).also { rigs += it }
        val assembly = PlaybackAssembly(PlaybackTopology.TWO_SLOT_ENGINE, rig.p1, rig.engine, rig.engine.facade)
        var clockMs = 0L
        val tracker = StatsTracker(Writer()).also {
            it.scope = CoroutineScope(Dispatchers.Unconfined)
            it.clock = { clockMs }
        }
        private fun store(name: String) = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + Job()),
            produceFile = { File.createTempFile(name, ".preferences_pb").also { f -> f.delete(); f.deleteOnExit() } },
        )
        val pc = PlayerController(
            RuntimeEnvironment.getApplication() as Context, tracker, PlaybackSessionRepository(store("lssession")),
            ResumeBehaviorSettingsRepository(store("lsresume")), AppSettingsRepository(store("lsapp")),
        )
        val armEvents = mutableListOf<Boolean>()

        init {
            field("controllerConnectionState").set(pc, ControllerConnectionState.Connected)
            PlayerController::class.java.getDeclaredMethod("onControllerConnected", MediaController::class.java)
                .apply { isAccessible = true }.invoke(pc, rig.controller)
            rig.advance(100)
            // Exactly what PlaybackService does: arm/release the physical pause-at-end through the assembly (both slots).
            pc.setSleepBoundaryListener { armed -> armEvents += armed; assembly.setPauseAtEndOfMediaItems(armed) }
            armEvents.clear()
        }

        private fun field(name: String) = PlayerController::class.java.getDeclaredField(name).apply { isAccessible = true }

        fun start(index: Int = 0) {
            pc.playFromQueue((1L..3L).map { RealMedia3QueueRig.song(it) }, index)
            // the controller recorded its play() at this millisecond; a device needs an IPC hop before the session handles it
            ShadowSystemClock.advanceBy(Duration.ofMillis(2))
            // the real playback thread prepares asynchronously: wait for READY + playing before any sample
            TestMediaSource.awaitCondition { rig.engine.currentPlayer.playbackState == Player.STATE_READY && rig.engine.currentPlayer.isPlaying }
            rig.advance(300)
        }

        /** Real playback time, plus the simulated IPC hop so the controller sees the session's update as newer than its call. */
        fun advance(ms: Long) {
            var left = ms
            while (left > 0) {
                val step = minOf(50L, left)
                clockMs += step
                rig.advance(step)
                left -= step
            }
        }

        fun sample(): SessionProbe.Sample = rig.sample()
        fun series(n: Int, stepMs: Long): List<SessionProbe.Sample> = (0 until n).map { if (it > 0) advance(stepMs); sample() }
        val sleep: SleepTimerState get() = pc.sleepTimerState.value
    }

    private val rigs = mutableListOf<SessionProgressRig>()
    private val harnesses = mutableListOf<Harness>()
    private fun harness() = Harness().also { harnesses += it }
    @After fun tearDown() { harnesses.forEach { runCatching { it.pc.release() } }; rigs.forEach { runCatching { it.release() } } }

    private fun assertLive(label: String, samples: List<SessionProbe.Sample>) {
        samples.forEachIndexed { i, s ->
            assertEquals("$label[$i] session player == physical: $s", s.physical, s.sessionPlayer)
            assertTrue("$label[$i] controller within tolerance: $s", abs(s.controller - s.physical) <= TOL)
        }
        for (i in 1 until samples.size) {
            assertTrue("$label: controller advanced ${samples[i - 1]} -> ${samples[i]}", samples[i].controller - samples[i - 1].controller >= 1_000 - 2 * TOL)
        }
    }

    @Test fun `an active sleep countdown with finish OFF or ON never disturbs the session position`() {
        for (finish in listOf(false, true)) {
            val h = harness()
            h.start()
            h.advance(2_000)
            h.pc.setCustomSleepTimer(60_000L, finishCurrentTrack = finish)
            assertTrue(h.sleep.isCountingDown)
            val during = h.series(8, 1_000)
            assertTrue("still counting down", h.sleep.isCountingDown)
            assertLive("countdown(finish=$finish)", during)
            assertTrue(during.all { it.isPlaying && it.playWhenReady })
            assertEquals("a countdown arms nothing", emptyList<Boolean>(), h.armEvents.filter { it })
            assertFalse(h.assembly.currentPlayer.pauseAtEndOfMediaItems)
            h.pc.setSleepTimer(SleepTimerOption.OFF)
        }
    }

    @Test fun `with the finish-current boundary armed the position keeps advancing until the track really ends`() {
        val h = harness()
        h.start()
        h.advance(1_000)
        h.pc.setCustomSleepTimer(1_000L, finishCurrentTrack = true)
        h.advance(1_500) // countdown expires, the boundary is armed, playback goes on
        assertTrue(h.sleep.isFinishingCurrentTrack)
        assertEquals(listOf(true), h.armEvents)
        assertTrue(h.assembly.currentPlayer.pauseAtEndOfMediaItems)

        val armed = h.series(5, 1_000)
        assertLive("finishing", armed)
        assertTrue("still playing while the boundary waits for the natural end", armed.all { it.isPlaying })
        assertTrue(h.sleep.isFinishingCurrentTrack)

        // let the track genuinely play out
        h.rig.command { seekTo(h.rig.controller.duration - 2_000L) }
        val approaching = h.series(2, 800)
        assertTrue("moving right up to the end: $approaching", approaching.all { it.isPlaying } && approaching.last().controller > approaching.first().controller)
        h.advance(2_000)
        val ended = h.series(4, 1_000)
        assertEquals("stopped on the same track (no advance)", 0, ended.last().index)
        assertFalse("the physical player stopped, so the position stops", ended.last().playWhenReady || ended.last().isPlaying)
        assertTrue("position parked at the end: ${ended.last()}", ended.last().controller >= TestMediaSource.DURATION_US / 1_000 - 100)
        assertTrue("frozen after the stop", ended.all { it.controller == ended.first().controller })
        assertEquals(emptyList<Boolean>(), h.armEvents.drop(1).filter { it }) // armed exactly once
    }

    private companion object {
        const val TOL = 250L
    }
}
