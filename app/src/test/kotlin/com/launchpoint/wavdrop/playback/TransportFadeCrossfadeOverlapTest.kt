package com.launchpoint.wavdrop.playback

import android.media.AudioManager
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
 * Transport Fade over a REAL promotion (OverlapRig: real engine, façade, focus owner and promotion runtime; no swapRolesForTest).
 * The equal-power crossfade curve is untouched: these tests only assert how the transport factor composes with it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class TransportFadeCrossfadeOverlapTest {
    private val time = VirtualFadeTime()

    private fun fadeFor(r: OverlapRig) = TransportFadeController(
        sink = TransportGainSink { r.engine.setTransportGain(it) },
        clock = time, scheduler = time, config = { TransportFadeConfig(true, 300L) },
    ).also { r.engine.bindTransportFade(it) }

    private fun focus(change: Int) {
        val audio = RuntimeEnvironment.getApplication().getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager
        shadowOf(audio).lastAudioFocusRequest!!.listener.onAudioFocusChange(change); idleMainLooper()
    }

    @Test fun transportGainMultipliesBothCrossfadeGainsAndBothPlayersStayAudible() {
        val r = OverlapRig().inOverlap()
        assertTrue("real promotion: A retiring", r.engine.promotionActive)
        r.engine.setFadeGains(outgoing = 0.6f, incoming = 0.8f)
        r.engine.setTransportGain(0.5f)
        assertEquals("retiring A = fade x transport", 0.3f, r.p1.volume, 0.0001f)
        assertEquals("incoming B = fade x transport", 0.4f, r.p2.volume, 0.0001f)
        assertTrue("both audible during the overlap", r.p1.volume > 0f && r.p2.volume > 0f)
        r.engine.setTransportGain(1f)
        assertEquals(0.6f, r.p1.volume, 0.0001f); assertEquals(0.8f, r.p2.volume, 0.0001f)
    }

    @Test fun duckingStaysIndependentOfBothTheCrossfadeAndTheTransportGain() {
        val r = OverlapRig().inOverlap()
        r.engine.setFadeGains(0.6f, 0.8f)
        focus(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        r.engine.setTransportGain(0.5f)
        assertEquals(0.2f * 0.5f * 0.8f, r.p2.volume, 0.0001f)
        assertEquals(0.2f * 0.5f * 0.6f, r.p1.volume, 0.0001f)
        focus(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(0.5f * 0.8f, r.p2.volume, 0.0001f)
    }

    @Test fun explicitPauseDuringTheOverlapCutsTheRetiringPlayerAndLeavesNeutralGain() {
        val r = OverlapRig().inOverlap()
        val fade = fadeFor(r)
        fade.pause(isPlaying = true) { r.runtime.cancel(CrossfadeCancelReason.Pause); r.facade.pause() }
        time.advance(150)
        assertTrue("both players are scaled together mid fade-out", r.p2.volume < 1f)
        time.advance(400); idleMainLooper()
        assertFalse(r.p2.playWhenReady)
        assertFalse("the retiring player is not left audible", r.p1.playWhenReady)
        assertFalse(r.engine.promotionActive)
        assertEquals(1f, r.engine.transportGain, 0f)
        assertEquals(1f, r.p2.volume, 0.0001f)
    }

    @Test fun explicitNextDuringTheOverlapSettlesUnderTheExistingPolicyThenNavigatesImmediately() {
        val r = OverlapRig().inOverlap()
        val fade = fadeFor(r)
        // The service cancels the overlap at the command boundary (explicit navigation), then the fade runs the unchanged navigation.
        r.runtime.cancel(CrossfadeCancelReason.ManualNavigation)
        assertFalse("settled before any navigation", r.engine.promotionActive)
        fade.navigate(isPlaying = true) { r.facade.seekToNext(); true }
        time.advance(500); idleMainLooper()
        assertEquals(3, r.p2.currentMediaItemIndex)
        assertEquals(1, r.p2.commands.count { it.startsWith("P2.seek(") })
        assertTrue(r.p2.playWhenReady)
        assertEquals(1f, r.engine.transportGain, 0f); assertEquals(1f, r.p2.volume, 0.0001f)
        assertEquals("A never receives navigation", 0, r.p1.commands.count { it.startsWith("P1.seek(") })
    }

    @Test fun anInterruptedOverlapRestoresNeutralTransportGainAndSettlesToB() {
        val r = OverlapRig().inOverlap()
        val fade = fadeFor(r)
        fade.navigate(isPlaying = true) { r.facade.seekToNext(); true }
        time.advance(60)
        focus(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        time.advance(1_000); idleMainLooper()
        assertEquals("transport gain neutral", 1f, r.engine.transportGain, 0f)
        assertFalse("overlap settled", r.engine.promotionActive)
        assertEquals("Next navigated at tap time and is not undone", 3, r.p2.currentMediaItemIndex)
        assertEquals(1f, r.p2.volume, 0.0001f)
    }

    @Test fun retirementAndCleanupLeaveNoMutedOrOrphanedPlayer() {
        val r = OverlapRig().inOverlap()
        val fade = fadeFor(r)
        fade.pause(isPlaying = true) { r.facade.pause() }
        time.advance(100)
        r.runtime.settleOverlap(PromotionInterruption.Other) // cleanup mid transport fade
        fade.interrupt()
        assertEquals(1f, r.engine.transportGain, 0f)
        assertEquals(1f, r.p2.volume, 0.0001f)
        assertEquals("the retired player is emptied and neutral, ready for reuse", 0, r.p1.mediaItemCount)
        assertEquals(1f, r.p1.volume, 0.0001f)
        assertFalse(r.engine.promotionActive)
        time.advance(2_000)
        assertEquals(1f, r.p2.volume, 0.0001f)
    }
}
