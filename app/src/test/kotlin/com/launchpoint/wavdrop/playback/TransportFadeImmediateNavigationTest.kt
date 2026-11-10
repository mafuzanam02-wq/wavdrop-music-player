package com.launchpoint.wavdrop.playback

import android.media.AudioManager
import androidx.media3.common.util.UnstableApi
import com.launchpoint.wavdrop.data.model.Song
import org.junit.After
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
 * P5-F20G: manual Next/Previous with Transport Fade navigate IMMEDIATELY (no outgoing fade wait); the incoming audio then ramps
 * 0 -> 1 over the configured duration, overlapping the visible track change and the real playback progress.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class TransportFadeImmediateNavigationTest {
    private val time = VirtualFadeTime()
    private val gains = mutableListOf<Float>()
    private val log = mutableListOf<String>()
    private var config = TransportFadeConfig(true, 300L)
    private val controller = TransportFadeController({ gains += it; log += "gain=$it@${time.now}" }, time, time) { config }
    private fun log(what: String) { log += "$what@${time.now}" }

    // 1/2/4/5/6. Immediate navigation, incoming ramp from zero to normal over the configured duration ----------------
    @Test fun `next navigates at tap time with no scheduler wait and starts the incoming ramp at zero`() {
        controller.navigate(isPlaying = true) { log("nav"); true }
        assertEquals("navigation ran before any timer fired", "nav@0", log.first())
        assertEquals("incoming audio is silent at the moment of the change", 0f, controller.gain, 0f)
        assertEquals(1, time.pendingCount) // only the ramp tick is scheduled
    }

    @Test fun `incoming gain rises monotonically and reaches one at 100 300 and 800 ms`() {
        for (d in listOf(100L, 300L, 800L)) {
            val t = VirtualFadeTime(); val g = mutableListOf<Float>()
            val c = TransportFadeController({ g += it }, t, t) { TransportFadeConfig(true, d) }
            c.navigate(true) { true }
            assertEquals("d=$d starts silent", 0f, g.first(), 0f)
            t.advance(d / 2); assertTrue("d=$d midway", g.last() in 0.3f..0.7f)
            t.advance(d - d / 2 - 1); assertTrue("d=$d not yet full one ms early", g.last() < 1f)
            t.advance(1); assertEquals("d=$d full at the configured duration", 1f, g.last(), 0f)
            assertEquals(g.sorted(), g.toList()) // never dips
            assertEquals(0, t.pendingCount)
        }
    }

    @Test fun `previous that selects another item behaves exactly like next`() {
        controller.navigate(isPlaying = true) { log("prev"); true }
        assertEquals("prev@0", log.first()); assertEquals(0f, controller.gain, 0f)
        time.advance(300); assertEquals(1f, controller.gain, 0f)
    }

    // 19/8. No-op, restart and failure never mute ------------------------------------------------------------------
    @Test fun `no-op or restart navigation never touches the gain`() {
        controller.navigate(isPlaying = true) { log("nav"); false }
        assertTrue("no gain write at all", gains.isEmpty()); assertEquals(0, time.pendingCount)
    }

    @Test fun `failed navigation never leaves the player muted and the controller stays usable`() {
        controller.navigate(isPlaying = true) { error("boom") }
        assertEquals(1f, controller.gain, 0f); assertEquals(0, time.pendingCount)
        controller.navigate(isPlaying = true) { log("nav2"); true }
        time.advance(300); assertEquals(1f, controller.gain, 0f)
    }

    @Test fun `paused navigation is immediate with no gain write and no ramp`() {
        controller.navigate(isPlaying = false) { log("nav"); true }
        assertEquals(listOf("nav@0"), log); assertTrue(gains.isEmpty()); assertEquals(0, time.pendingCount)
    }

    @Test fun `disabled restores the original immediate behaviour`() {
        config = TransportFadeConfig(false, 300L)
        controller.navigate(isPlaying = true) { log("nav"); true }
        assertEquals(listOf("nav@0"), log); assertTrue(gains.isEmpty()); assertEquals(0, time.pendingCount)
    }

    // 14/15. Rapid presses: each navigates once, immediately, the latest owns the ramp -----------------------------
    @Test fun `rapid next presses navigate once each at tap time and the last ramp owns the gain`() {
        controller.navigate(true) { log("n1"); true }
        time.advance(40)
        controller.navigate(true) { log("n2"); true }
        time.advance(40)
        controller.navigate(true) { log("n3"); true }
        assertEquals(listOf("n1@0", "n2@40", "n3@80"), log.filter { it.startsWith("n") })
        assertEquals(0f, controller.gain, 0f)
        time.advance(299); assertTrue(controller.gain < 1f)
        time.advance(1); assertEquals("ramp measured from the LAST press", 1f, controller.gain, 0f)
        assertEquals(0, time.pendingCount)
    }

    @Test fun `a superseded ramp can never write a stale gain`() {
        controller.navigate(true) { true }
        time.advance(100)
        controller.navigate(true) { true }
        val before = gains.size
        time.everPosted.first()() // the first press's tick fires late
        assertEquals("stale callback inert", before, gains.size)
    }

    // 16/17. Pause during the incoming fade, then Play ------------------------------------------------------------
    @Test fun `pause during the incoming fade fades down from the current gain then pauses and Play recovers`() {
        controller.navigate(true) { true }
        time.advance(100); val mid = controller.gain
        controller.pause(isPlaying = true) { log("pause") }
        assertTrue("no upward jump", controller.gain <= mid + 1e-6f)
        time.advance(1_000)
        assertTrue(log.any { it.startsWith("pause") }); assertEquals(1f, controller.gain, 0f)
        controller.play(isPlaying = false) { log("play") }
        time.advance(1_000); assertEquals(1f, controller.gain, 0f)
    }

    // 18. Interruptions ------------------------------------------------------------------------------------------
    @Test fun `interrupt during the incoming fade restores full gain and cancels the ramp`() {
        controller.navigate(true) { true }
        time.advance(50)
        controller.interrupt()
        assertEquals(1f, controller.gain, 0f)
        val n = gains.size; time.advance(1_000); assertEquals(n, gains.size); assertEquals(0, time.pendingCount)
    }

    // an in-navigation Play (selection start) is part of the navigation: not a second fade
    @Test fun `a play issued by the navigation itself is not faded separately`() {
        controller.navigate(true) { controller.play(isPlaying = true) { log("start") }; true }
        assertEquals("start@0", log.first { it.startsWith("start") })
        time.advance(300); assertEquals(1f, controller.gain, 0f)
    }

    // ── production path: real PlayerController -> MediaController -> session -> engine ────────────────────────────────
    private val rigs = mutableListOf<EngineChainRig>()
    @After fun tearDown() { rigs.forEach { it.release() }; rigs.clear() }
    private fun queue(n: Int): List<Song> = List(n) { RealMedia3QueueRig.song(it + 1L) }
    private val writes = mutableListOf<Float>()
    private fun rig(enabled: Boolean = true, bounded: Boolean = false): EngineChainRig {
        val r = EngineChainRig(bounded).also { rigs += it }
        val fade = TransportFadeController({ writes += it; r.engine.setTransportGain(it) }, time, time) { TransportFadeConfig(enabled, 300L) }
        r.engine.bindTransportFade(fade); r.pc.attachTransportFade(fade)
        return r
    }
    private fun EngineChainRig.index() = engine.facade.currentMediaItemIndex
    private fun EngineChainRig.flushNoTime() { quiesce(); TestMediaSource.settle(100); quiesce() }
    private fun EngineChainRig.volume() = engine.currentPlayer.volume

    @Test fun `production next selects the occurrence and publishes it without advancing the fade scheduler`() {
        val r = rig(); r.start(queue(5), 0)
        r.pc.skipToNext(); r.flushNoTime() // NO time.advance
        assertEquals("authoritative player moved immediately", 1, r.index())
        assertEquals("presentation shows the same occurrence", 2L, r.pc.nowPlayingState.value.song?.id)
        assertTrue("playback never waited for the fade", r.engine.facade.playWhenReady)
        assertEquals("incoming audio begins silent", 0f, r.volume(), 0.0001f)
        r.settleAll(); assertEquals(1f, r.volume(), 0.0001f)
    }

    @Test fun `production previous selects the prior occurrence immediately`() {
        val r = rig(); r.start(queue(5), 2)
        r.pc.skipToPrevious(); r.flushNoTime()
        assertEquals(1, r.index()); assertEquals(2L, r.pc.nowPlayingState.value.song?.id)
        assertTrue(r.engine.facade.playWhenReady); assertEquals(0f, r.volume(), 0.0001f)
    }

    @Test fun `production bounded queue navigation is immediate across the window`() {
        val r = rig(bounded = true); r.start(queue(40), 10)
        r.pc.skipToNext(); r.flushNoTime()
        assertEquals(11, r.index())
        assertEquals(0f, r.volume(), 0.0001f)
    }

    @Test fun `production position follows the real selected item and is not held by the fade`() {
        val r = rig(); r.start(queue(5), 0)
        r.pc.skipToNext(); r.flushNoTime()
        assertEquals(1, r.controller.currentMediaItemIndex)
        assertEquals("starts at the head of the new item", 0L, r.controller.currentPosition)
        r.pc.skipToNext(); r.flushNoTime()
        assertEquals(2, r.controller.currentMediaItemIndex)
        assertTrue("player reports it is wanted playing during the ramp", r.controller.playWhenReady)
    }

    @Test fun `production paused navigation is immediate with no gain write and no ramp`() {
        val r = rig(); r.start(queue(5), 1)
        r.pc.togglePlayPause(); time.advance(400); r.flushNoTime()
        assertFalse(r.engine.facade.playWhenReady)
        writes.clear()
        r.pc.skipToNext(); r.flushNoTime()
        assertEquals(2, r.index()) // existing selection policy decides whether the selected item starts; the fade never adds a ramp
        assertTrue(writes.isEmpty()); assertEquals(0L, r.controller.currentPosition)
    }

    @Test fun `production restart-current previous resets the real position without muting`() {
        val r = rig(); r.start(queue(3), 0)
        r.pc.skipToPrevious(); r.flushNoTime()
        assertEquals(0, r.index()); assertEquals(1f, r.volume(), 0.0001f); assertTrue(r.engine.facade.playWhenReady)
    }

    @Test fun `production no-op next at the final repeat-off item keeps playing at full volume`() {
        val r = rig(); r.start(queue(3), 2)
        r.pc.skipToNext(); r.flushNoTime()
        assertEquals(2, r.index()); assertEquals(1f, r.volume(), 0.0001f); assertTrue(r.engine.facade.playWhenReady)
    }

    @Test fun `production next then pause during the incoming fade pauses and ends at full gain`() {
        val r = rig(); r.start(queue(5), 0)
        r.pc.skipToNext(); r.flushNoTime(); time.advance(100)
        r.pc.togglePlayPause(); r.settleAll()
        assertEquals(1, r.index()); assertFalse(r.engine.facade.playWhenReady)
        assertEquals(1f, r.volume(), 0.0001f)
    }

    @Test fun `production focus loss during the incoming fade leaves no mute and no pending work`() {
        val r = rig(); r.start(queue(5), 0)
        r.pc.skipToNext(); r.flushNoTime(); time.advance(60)
        val audio = RuntimeEnvironment.getApplication().getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager
        shadowOf(audio).lastAudioFocusRequest!!.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        idleMainLooper(); r.settleAll()
        assertEquals("the Next already happened and is not undone", 1, r.index())
        assertEquals(1f, r.volume(), 0.0001f); assertEquals(0, time.pendingCount)
    }

    @Test fun `production disabled navigation writes no gain`() {
        val r = rig(enabled = false); r.start(queue(5), 0)
        r.pc.skipToNext(); r.flushNoTime()
        assertEquals(1, r.index()); assertTrue(writes.isEmpty()); assertEquals(1f, r.volume(), 0.0001f)
    }

    private fun EngineChainRig.settleAll(ms: Long = 3_000L) { time.advance(ms); quiesce(); TestMediaSource.settle(100); quiesce() }
}
