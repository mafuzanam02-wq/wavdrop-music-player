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
 * The PRODUCTION command path (real PlayerController -> MediaController -> session -> engine with real ExoPlayers) with a
 * TransportFadeController attached exactly as PlaybackService attaches it, driven on virtual time. Codec-free: asserts queue
 * position, play state and the composed physical volume, never audible output.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class TransportFadeProductionPathTest {
    private val rigs = mutableListOf<EngineChainRig>()
    @After fun tearDown() { rigs.forEach { it.release() }; rigs.clear() }

    private val time = VirtualFadeTime()
    private val writes = mutableListOf<Float>()
    private fun queue(n: Int): List<Song> = List(n) { RealMedia3QueueRig.song(it + 1L) }

    private fun rig(enabled: Boolean = true, bounded: Boolean = false): Pair<EngineChainRig, TransportFadeController> {
        val r = EngineChainRig(bounded).also { rigs += it }
        val fade = TransportFadeController(
            sink = TransportGainSink { writes += it; r.engine.setTransportGain(it) },
            clock = time, scheduler = time, config = { TransportFadeConfig(enabled, 300L) },
        )
        r.engine.bindTransportFade(fade)
        r.pc.attachTransportFade(fade)
        return r to fade
    }

    private fun EngineChainRig.index() = engine.facade.currentMediaItemIndex
    private fun EngineChainRig.settleAll(ms: Long = 3_000L) {
        time.advance(ms); quiesce(); TestMediaSource.settle(100); quiesce()
    }
    private fun EngineChainRig.volume() = engine.currentPlayer.volume

    @Test fun nextThenNextRapidlyMovesTwoItemsAndEndsAtFullGain() {
        val (r, _) = rig(); r.start(queue(5), 0)
        r.pc.skipToNext(); time.advance(40); r.pc.skipToNext()
        r.settleAll()
        assertEquals(2, r.index()); assertEquals(1f, r.volume(), 0.0001f); assertTrue(r.engine.facade.playWhenReady)
    }

    @Test fun nextThenPreviousEndsOnTheStartingItem() {
        val (r, _) = rig(); r.start(queue(5), 2)
        r.pc.skipToNext(); time.advance(40); r.pc.skipToPrevious()
        r.settleAll()
        assertEquals(2, r.index()); assertEquals(1f, r.volume(), 0.0001f); assertTrue(r.engine.facade.playWhenReady)
    }

    @Test fun previousThenNextEndsOnTheStartingItem() {
        val (r, _) = rig(); r.start(queue(5), 2)
        r.pc.skipToPrevious(); time.advance(40); r.pc.skipToNext()
        r.settleAll()
        assertEquals(2, r.index()); assertEquals(1f, r.volume(), 0.0001f)
    }

    @Test fun navigationHappensAtTapTimeNotAtAFadeMidpoint() {
        val (r, _) = rig(); r.start(queue(5), 0)
        r.pc.skipToNext()
        time.advance(100); r.quiesce()
        assertEquals("navigated immediately, nothing waited for a fade-out", 1, r.index())
        r.settleAll()
        assertEquals(1, r.index())
    }

    @Test fun disabledNavigatesImmediatelyWithNoGainWrites() {
        val (r, _) = rig(enabled = false); r.start(queue(5), 0)
        r.pc.skipToNext(); r.quiesce(); TestMediaSource.settle(100)
        assertEquals(1, r.index()); assertTrue(writes.isEmpty()); assertEquals(0, time.pendingCount)
    }

    @Test fun nextAtTheEndOfAnUnrepeatedQueueRestoresGainAndKeepsPlaying() {
        val (r, _) = rig(); r.start(queue(3), 2)
        r.pc.skipToNext()
        r.settleAll()
        assertEquals(2, r.index()); assertEquals(1f, r.volume(), 0.0001f); assertTrue(r.engine.facade.playWhenReady)
    }

    @Test fun navigationWhilePausedDoesNotFadeOrWriteGain() {
        val (r, _) = rig(); r.start(queue(5), 1)
        r.pc.togglePlayPause(); time.advance(400); r.quiesce(); TestMediaSource.settle(100)
        assertFalse("the in-app Pause completed after its fade", r.engine.facade.playWhenReady)
        writes.clear()
        r.pc.skipToNext(); r.settleAll()
        assertTrue("no transport gain write for a paused navigation", writes.isEmpty())
        assertEquals(1f, r.volume(), 0.0001f)
    }

    @Test fun boundedQueueNavigationAlsoFadesAndCompletes() {
        val (r, _) = rig(bounded = true); r.start(queue(40), 10)
        r.pc.skipToNext(); time.advance(30); r.pc.skipToNext()
        r.settleAll()
        assertEquals(12, r.index()); assertEquals(1f, r.volume(), 0.0001f)
    }

    @Test fun transientFocusLossDuringTheIncomingRampKeepsTheAlreadyDoneNavigation() {
        val (r, _) = rig(); r.start(queue(5), 0)
        r.pc.skipToNext(); time.advance(60)
        val audio = RuntimeEnvironment.getApplication().getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager
        shadowOf(audio).lastAudioFocusRequest!!.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        idleMainLooper()
        r.settleAll()
        assertEquals("the Next ran at tap time and is not undone or repeated", 1, r.index())
        assertEquals(1f, r.volume(), 0.0001f)
        assertEquals(0, time.pendingCount)
    }

    // ── queue boundaries on the real production path ─────────────────────────────────────────────────────────────────

    @Test fun repeatAllWrapsFromTheLastItemAndEndsAtFullGain() {
        val (r, _) = rig(); r.start(queue(3), 2)
        r.pc.cycleRepeatMode() // OFF -> ALL
        r.pc.skipToNext(); r.settleAll()
        assertEquals(0, r.index()); assertEquals(1f, r.volume(), 0.0001f); assertTrue(r.engine.facade.playWhenReady)
    }

    @Test fun repeatAllWrapAcrossABoundedWindowBoundary() {
        val (r, _) = rig(bounded = true); r.start(queue(40), 38)
        r.pc.cycleRepeatMode()
        r.pc.skipToNext(); r.settleAll()
        assertEquals(39, r.index())
        r.pc.skipToNext(); r.settleAll()
        assertEquals("wrapped to the first item of the full logical queue", 0, r.index())
        assertEquals(1f, r.volume(), 0.0001f); assertTrue(r.engine.facade.playWhenReady)
    }

    @Test fun repeatOneStillAdvancesOnAnExplicitNextMidQueue() {
        val (r, _) = rig(); r.start(queue(5), 1)
        r.pc.cycleRepeatMode(); r.pc.cycleRepeatMode() // ONE
        r.pc.skipToNext(); r.settleAll()
        assertEquals(2, r.index()); assertEquals(1f, r.volume(), 0.0001f)
    }

    @Test fun repeatOneAtTheLastItemKeepsTheItemAndRestoresGain() {
        val (r, _) = rig(); r.start(queue(3), 2)
        r.pc.cycleRepeatMode(); r.pc.cycleRepeatMode()
        r.pc.skipToNext(); r.settleAll()
        assertEquals(2, r.index()); assertEquals(1f, r.volume(), 0.0001f); assertTrue(r.engine.facade.playWhenReady)
    }

    @Test fun previousAtTheFirstItemOfAnUnrepeatedQueueRestoresGain() {
        val (r, _) = rig(); r.start(queue(3), 0)
        r.pc.skipToPrevious(); r.settleAll()
        assertEquals(0, r.index()); assertEquals(1f, r.volume(), 0.0001f); assertTrue(r.engine.facade.playWhenReady)
    }
}
