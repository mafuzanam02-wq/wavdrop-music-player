package com.launchpoint.wavdrop.playback

import android.media.AudioManager
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The transport gain is ONE more factor of the engine's single gain composer: physical volume =
 * duck x crossfade fade x transport gain. It must never overwrite (or be overwritten by) another owner.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class PlayerEngineTransportGainTest {
    private val audioManager get() = RuntimeEnvironment.getApplication().getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager

    private fun focusListener(): AudioManager.OnAudioFocusChangeListener =
        shadowOf(audioManager).lastAudioFocusRequest!!.listener

    @Test fun transportGainScalesTheCurrentPlayerAndDefaultsToNeutral() {
        val f = PlayerEngineFixture()
        assertEquals(1f, f.p1.volume, 0f)
        f.engine.setTransportGain(0.5f)
        assertEquals(0.5f, f.p1.volume, 0.0001f)
        f.engine.setTransportGain(1f)
        assertEquals(1f, f.p1.volume, 0f)
    }

    @Test fun transportGainComposesWithAudioFocusDuckingInsteadOfReplacingIt() {
        val f = PlayerEngineFixture()
        f.facade.play(); idleMainLooper()
        focusListener().onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK); idleMainLooper()
        assertEquals(0.2f, f.p1.volume, 0.0001f)
        f.engine.setTransportGain(0.5f)
        assertEquals("duck x transport", 0.1f, f.p1.volume, 0.0001f)
        focusListener().onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN); idleMainLooper()
        assertEquals("duck released, transport kept", 0.5f, f.p1.volume, 0.0001f)
        f.engine.setTransportGain(1f)
        assertEquals(1f, f.p1.volume, 0f)
    }

    @Test fun duckingWhileTransportGainIsNeutralIsUnchanged() {
        val f = PlayerEngineFixture()
        f.facade.play(); idleMainLooper()
        focusListener().onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK); idleMainLooper()
        assertEquals(0.2f, f.p1.volume, 0.0001f)
    }

    @Test fun transportGainComposesWithTheCrossfadeFadeOfTheRetiringAndIncomingSlots() {
        val f = PlayerEngineFixture()
        f.engine.swapRolesForTest()
        f.engine.setTransportGain(0.5f)
        assertEquals(0.5f, f.p2.volume, 0.0001f) // new CURRENT
        assertEquals("the non-audible slot is not scaled by the transport gain", 1f, f.p1.volume, 0.0001f)
    }

    @Test fun transportGainDoesNotDisturbPlaybackState() {
        val f = PlayerEngineFixture()
        f.facade.play(); idleMainLooper()
        f.p1.commands.clear()
        f.engine.setTransportGain(0f)
        f.engine.setTransportGain(1f)
        assertTrue("gain changes must not issue transport commands: ${f.p1.commands}", f.p1.commands.all { it.startsWith("P1.setVolume(") })
        assertTrue(f.facade.playWhenReady)
    }

    @Test fun invalidTransportGainIsRejectedAndKeepsThePreviousValue() {
        val f = PlayerEngineFixture()
        f.engine.setTransportGain(0.5f)
        for (bad in listOf(Float.NaN, -0.1f, 1.1f, Float.POSITIVE_INFINITY)) {
            runCatching { f.engine.setTransportGain(bad) }
            assertEquals(0.5f, f.p1.volume, 0.0001f)
        }
    }
}
