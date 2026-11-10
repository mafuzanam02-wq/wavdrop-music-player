package com.launchpoint.wavdrop.playback

import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.ui.components.playbackProgressPresentation
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/**
 * P5-F20G progress synchronization gate. The Now Playing counter, the seek bar and the mini player all read the SAME Media3
 * player (LocalProgressPlayer = the PlayerController's MediaController) through ProgressStateWithTickInterval, which re-reads
 * the player on POSITION_DISCONTINUITY / IS_PLAYING_CHANGED / PLAYBACK_PARAMETERS_CHANGED / AVAILABLE_COMMANDS_CHANGED and on a
 * delay-based tick while playing. The transport fade is a separate scheduler that only writes a volume, so it cannot gate any
 * of these. This file proves it on the production chain with a REAL ExoPlayer clock (codec-free source): the fade scheduler is a
 * frozen virtual clock (gain parked below 1) while REAL time passes and the real position advances.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class TransportFadeProgressSyncTest {
    private val time = VirtualFadeTime()
    private val rigs = mutableListOf<EngineChainRig>()
    @After fun tearDown() { rigs.forEach { it.release() }; rigs.clear() }
    private fun queue(n: Int): List<Song> = List(n) { RealMedia3QueueRig.song(it + 1L) }

    private fun rig(enabled: Boolean = true): EngineChainRig {
        val r = EngineChainRig(bounded = false).also { rigs += it }
        val fade = TransportFadeController({ r.engine.setTransportGain(it) }, time, time) { TransportFadeConfig(enabled, 800L) }
        r.engine.bindTransportFade(fade); r.pc.attachTransportFade(fade)
        return r
    }
    private fun EngineChainRig.vol() = engine.currentPlayer.volume
    private fun EngineChainRig.enginePos() = engine.currentPlayer.currentPosition

    /**
     * Media3 cannot extrapolate a MediaController position until its first periodic session position update (~3 s after playback
     * starts); a running app is always past that. Warm the rig so the controller view is the steady-state production view.
     */
    private fun EngineChainRig.startWarm(q: List<Song>, index: Int) { start(q, index); playFor(3_200) }
    private fun EngineChainRig.settleReal(ms: Long = 150) { quiesce(); TestMediaSource.settle(ms) }

    /** REAL playback time (the ExoPlayer clock reads the Robolectric system clock), independent of the frozen virtual fade clock. */
    private fun playFor(ms: Long) {
        var left = ms
        while (left > 0) {
            val step = minOf(50L, left)
            ShadowSystemClock.advanceBy(Duration.ofMillis(step))
            Thread.sleep(8); idleMainLooper(); left -= step
        }
        TestMediaSource.settle(40)
    }

    /** Collects the player events that make the UI progress state re-read the position. */
    private class RefreshEvents(player: Player) {
        val discontinuities = mutableListOf<Int>()
        val transitions = mutableListOf<Int>()
        init {
            player.addListener(object : Player.Listener {
                override fun onPositionDiscontinuity(o: Player.PositionInfo, n: Player.PositionInfo, reason: Int) { discontinuities += reason }
                override fun onMediaItemTransition(item: androidx.media3.common.MediaItem?, reason: Int) { transitions += reason }
            })
        }
    }

    @Test fun `real playback position advances while the transport gain is parked below one`() {
        val r = rig(); r.startWarm(queue(5), 0)
        r.pc.skipToNext(); r.settleReal(50)
        assertEquals(1, r.controller.currentMediaItemIndex)
        time.advance(400) // virtual fade clock: mid ramp, then FROZEN (no more virtual time passes)
        val g = r.vol()
        assertTrue("ramp is mid-way, gain=$g", g in 0.2f..0.8f)
        val p0 = r.controller.currentPosition
        playFor(400) // REAL time passes
        assertTrue("position advanced from $p0 to ${r.controller.currentPosition} while gain was below one", r.controller.currentPosition >= p0 + 250)
        assertEquals("gain never moved while only real time passed: the ramp does not drive progress", g, r.vol(), 0.0001f)
        assertTrue("player is really playing", r.controller.isPlaying)
    }

    @Test fun `next fires the discontinuity that refreshes the progress state without any fade time`() {
        val r = rig(); r.startWarm(queue(5), 0)
        val ev = RefreshEvents(r.controller)
        r.pc.skipToNext(); r.settleReal(50) // NO virtual time
        assertTrue("MEDIA_ITEM_TRANSITION delivered with the fade scheduler untouched: $ev", ev.transitions.isNotEmpty())
        assertTrue("POSITION_DISCONTINUITY (the ProgressState refresh trigger) delivered: ${ev.discontinuities}", ev.discontinuities.isNotEmpty())
        assertEquals(0.0f, r.vol(), 0.0001f)
    }

    @Test fun `presentation reflects the selected occurrence and counter and seek bar share one position`() {
        val r = rig(); r.startWarm(queue(5), 0)
        playFor(400)
        r.pc.skipToNext(); r.settleReal(50)
        val index = r.controller.currentMediaItemIndex
        assertEquals(1, index)
        assertEquals("authoritative state shows the same occurrence", 2L, r.pc.nowPlayingState.value.song?.id)
        val pos = r.controller.currentPosition; val dur = r.controller.duration
        assertTrue("position belongs to the NEW item (previous item was past 3.5 s): $pos", pos < 1_000)
        // Exactly what NowPlayingScreen feeds to the counter (textProgress) and the Canvas (trackProgress): one function, one position.
        val counter = playbackProgressPresentation(pos, dur)
        val bar = playbackProgressPresentation(pos, dur)
        assertEquals(counter, bar)
        assertEquals(pos / 1_000L, counter.displayElapsedSeconds)
        assertEquals(pos.toFloat() / dur, counter.fraction, 1e-6f)
    }

    @Test fun `counter keeps moving across the whole ramp not only after it`() {
        val r = rig(); r.startWarm(queue(5), 0)
        r.pc.skipToNext(); r.settleReal(30)
        val samples = mutableListOf<Long>()
        repeat(3) {
            time.advance(100) // ramp progresses (still < 800 ms)
            val before = r.controller.currentPosition
            playFor(150); assertTrue(r.controller.currentPosition >= before + 100)
            samples += r.controller.currentPosition
            assertTrue("still ramping", r.vol() < 1f)
            assertEquals("controller (UI) position equals the engine position during the ramp", r.enginePos().toDouble(), r.controller.currentPosition.toDouble(), 80.0)
        }
        assertEquals("monotonic real progress during the ramp: $samples", samples.sorted(), samples)
        assertTrue(samples.last() > samples.first())
    }

    @Test fun `paused navigation keeps progress stationary`() {
        val r = rig(); r.startWarm(queue(5), 1)
        r.pc.togglePlayPause(); time.advance(1_000); r.settleReal(50)
        assertFalse(r.engine.facade.playWhenReady)
        r.pc.skipToNext(); r.settleReal(50)
        // The existing selection policy may start the target; position must follow the real player either way.
        val playing = r.controller.playWhenReady
        val p = r.controller.currentPosition
        if (!playing) { playFor(300); assertEquals("paused: stationary", p, r.controller.currentPosition) }
        else assertEquals(2, r.controller.currentMediaItemIndex)
    }

    @Test fun `paused player position is stationary across real time`() {
        val r = rig(); r.startWarm(queue(5), 1)
        r.pc.togglePlayPause(); time.advance(1_000); r.settleReal(50)
        assertFalse(r.engine.facade.playWhenReady)
        val p = r.controller.currentPosition
        playFor(300)
        assertEquals(p, r.controller.currentPosition)
    }

    @Test fun `previous restart-current resets the real position and keeps the occurrence`() {
        val r = rig(); r.startWarm(queue(5), 2)
        r.pc.seekTo(10_000); r.settleReal(50)
        playFor(100); assertTrue("seek applied: ctrl=${r.controller.currentPosition} player=${r.engine.currentPlayer.currentPosition} state=${r.engine.currentPlayer.playbackState} idx=${r.controller.currentMediaItemIndex}", r.controller.currentPosition >= 10_000)
        r.pc.skipToPrevious(); r.settleReal(50)
        assertEquals("restart keeps the occurrence", 2, r.controller.currentMediaItemIndex)
        assertEquals(3L, r.pc.nowPlayingState.value.song?.id)
        assertTrue("real position reset: ${r.controller.currentPosition}", r.controller.currentPosition < 2_000)
        val p = r.controller.currentPosition
        playFor(250); assertTrue(r.controller.currentPosition >= p + 150) // and keeps advancing
    }

    @Test fun `rapid next then previous shows only the final occurrence and its own position`() {
        val r = rig(); r.startWarm(queue(5), 2)
        playFor(500)
        r.pc.skipToNext(); time.advance(30); r.pc.skipToNext(); time.advance(30); r.pc.skipToPrevious()
        r.settleReal(80)
        assertEquals(3, r.controller.currentMediaItemIndex)
        assertEquals(4L, r.pc.nowPlayingState.value.song?.id)
        assertTrue("position is the final item's, not a superseded one's (old items were past 3.5 s): ${r.controller.currentPosition}", r.controller.currentPosition < 1_500)
    }

    @Test fun `disabled transport fade keeps the same position behaviour`() {
        val r = rig(enabled = false); r.startWarm(queue(5), 0)
        r.pc.skipToNext(); r.settleReal(50)
        playFor(100); assertTrue(r.controller.isPlaying)
        val p = r.controller.currentPosition
        playFor(250); assertTrue("p=$p now=${r.controller.currentPosition} player=${r.engine.currentPlayer.currentPosition} playing=${r.engine.currentPlayer.isPlaying}", r.controller.currentPosition >= p + 150)
        assertEquals(1f, r.vol(), 0.0001f)
    }
}
