package com.launchpoint.wavdrop.playback

import android.content.Intent
import android.media.AudioManager
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Every system interruption (permanent / transient focus loss, noisy audio) must abort an in-flight transport fade: gain
 * back to neutral, no delayed navigation left pending, no stale callback acting later, and a later Play working normally.
 * The engine and controller are wired exactly as PlaybackService wires them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class TransportFadeInterruptionTest {
    private val time = VirtualFadeTime()
    private val writes = mutableListOf<Float>()
    private val log = mutableListOf<String>()
    private lateinit var f: PlayerEngineFixture
    private lateinit var fade: TransportFadeController

    private fun setUp(): PlayerEngineFixture {
        f = PlayerEngineFixture()
        fade = TransportFadeController(
            sink = TransportGainSink { writes += it; f.engine.setTransportGain(it) },
            clock = time, scheduler = time, config = { TransportFadeConfig(true, 300L) },
        )
        f.engine.bindTransportFade(fade)
        f.facade.play(); idleMainLooper()
        return f
    }

    private val audioManager get() = RuntimeEnvironment.getApplication().getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager
    private fun focus(change: Int) {
        shadowOf(audioManager).lastAudioFocusRequest!!.listener.onAudioFocusChange(change); idleMainLooper()
    }

    private fun assertAborted() {
        assertEquals("transport gain neutral", 1f, f.p1.volume, 0.0001f)
        assertEquals(1f, fade.gain, 0f)
        time.advance(2_000)
        assertEquals("the navigation ran once at tap time; nothing acted after the abort: $log", listOf("nav"), log)
        assertEquals(1f, f.p1.volume, 0.0001f)
    }

    @Test fun transientFocusLossDuringTheIncomingRampAbortsItAndNeverMutes() {
        setUp()
        fade.navigate(isPlaying = true) { log += "nav"; true }
        time.advance(60)
        focus(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertAborted()
    }

    @Test fun permanentFocusLossDuringTheIncomingRampAbortsItAndNeverMutes() {
        setUp()
        fade.navigate(isPlaying = true) { log += "nav"; true }
        time.advance(60)
        focus(AudioManager.AUDIOFOCUS_LOSS)
        assertFalse(f.facade.playWhenReady)
        assertAborted()
    }

    @Test fun noisyAudioDuringTheIncomingRampAbortsItAndNeverMutes() {
        setUp()
        fade.navigate(isPlaying = true) { log += "nav"; true }
        time.advance(60)
        RuntimeEnvironment.getApplication().sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY)); idleMainLooper()
        assertFalse(f.facade.playWhenReady)
        assertAborted()
    }

    @Test fun transientFocusLossDuringPauseFadeStillHonoursThePause() {
        setUp()
        fade.pause(isPlaying = true) { f.facade.pause() }
        time.advance(60)
        focus(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        focus(AudioManager.AUDIOFOCUS_GAIN)
        time.advance(2_000)
        assertFalse("the user's pause must survive a transient loss followed by regain", f.facade.playWhenReady)
        assertEquals(1f, f.p1.volume, 0.0001f)
    }

    @Test fun laterPlayWorksNormallyAfterAnInterruption() {
        setUp()
        fade.navigate(true) { log += "nav"; true }
        time.advance(60)
        focus(AudioManager.AUDIOFOCUS_LOSS)
        fade.play(isPlaying = false) { f.facade.play() }
        idleMainLooper()
        assertTrue(f.facade.playWhenReady)
        time.advance(1_000)
        assertEquals(1f, f.p1.volume, 0.0001f)
        assertEquals(listOf("nav"), log) // the Next already ran at tap time; no later callback repeated it
    }

    @Test fun duckingIsIndependentAndDoesNotAbortTheFade() {
        setUp()
        fade.navigate(true) { log += "nav"; true }
        time.advance(60)
        focus(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        time.advance(100)
        assertEquals("the incoming ramp continues through a duck", listOf("nav"), log)
        time.advance(2_000)
        assertEquals("duck x neutral transport gain", 0.2f, f.p1.volume, 0.0001f)
        assertTrue("a duck is not a pause", f.facade.playWhenReady)
    }

    @Test fun interruptWhileIdleWritesNoVolumeAndSchedulesNothing() {
        setUp()
        val before = writes.size
        fade.interrupt()
        fade.interrupt()
        assertEquals(before, writes.size)
        assertEquals(0, time.pendingCount)
    }

    @Test fun oneInterruptionProducesOneAbortNotARepeat() {
        setUp()
        fade.navigate(true) { log += "nav"; true }
        time.advance(60)
        val before = writes.size
        focus(AudioManager.AUDIOFOCUS_LOSS)
        val afterAbort = writes.size
        assertTrue("abort restores gain with at most one write", afterAbort - before <= 1)
        focus(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals("a later unrelated event must not write again", afterAbort, writes.size)
    }

    @Test fun serviceTeardownReleasesTheFadeAndStopsTheHook() {
        setUp()
        fade.pause(isPlaying = true) { f.facade.pause() }
        time.advance(60)
        fade.release()
        f.engine.onPlaybackInterrupted = null
        assertEquals(1f, f.p1.volume, 0.0001f)
        time.advance(2_000)
        assertTrue("release never completes a pause", f.facade.playWhenReady)
    }
}
