package com.launchpoint.wavdrop.playback

import android.content.Context
import android.media.AudioManager
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioFocusManager
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAudioManager

/**
 * CF-2M3: (1) proves the actual Media3 1.11.1 [AudioFocusManager] behaviour OUTSIDE ExoPlayer against Robolectric's real
 * AudioManager shadow (the policy under test is Media3's own, not a fake); (2) proves the [PlayerEngine] focus mapping on top
 * of it, observed through the stable [SessionFacade], with hand-written physical players.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class PlayerEngineAudioFocusTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val audioManager: AudioManager get() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val shadow: ShadowAudioManager get() = shadowOf(audioManager)
    private val music = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build()

    // ── AudioFocusManager spike (Media3 policy, no engine) ───────────────────────────────────────────────────────────────

    private class RecordingControl : AudioFocusManager.PlayerControl {
        val commands = mutableListOf<Int>()
        val multipliers = mutableListOf<Float>()
        override fun setVolumeMultiplier(volumeMultiplier: Float) { multipliers += volumeMultiplier }
        override fun executePlayerCommand(playerCommand: Int) { commands += playerCommand }
    }

    private fun lastFocusListener(): AudioManager.OnAudioFocusChangeListener {
        val request = shadow.lastAudioFocusRequest
        assertNotNull("no audio focus request was made", request)
        return request.listener
    }

    private fun spikeManager(control: RecordingControl) =
        AudioFocusManager(context, android.os.Looper.getMainLooper(), control).also { it.setAudioAttributes(music) }

    @Test fun spike_playWhenReadyRequestsFocusOnceAndGrantsPlay() {
        val manager = spikeManager(RecordingControl())
        assertNull(shadow.lastAudioFocusRequest)
        assertEquals(AudioFocusManager.PLAYER_COMMAND_PLAY_WHEN_READY, manager.updateAudioFocus(true, Player.STATE_READY))
        val first = shadow.lastAudioFocusRequest
        assertNotNull(first)
        // Re-evaluating while focus is held must not issue a second request.
        assertEquals(AudioFocusManager.PLAYER_COMMAND_PLAY_WHEN_READY, manager.updateAudioFocus(true, Player.STATE_READY))
        assertSame(first, shadow.lastAudioFocusRequest)
    }

    @Test fun spike_idleStateNeverRequestsFocus() {
        val manager = spikeManager(RecordingControl())
        assertEquals(AudioFocusManager.PLAYER_COMMAND_PLAY_WHEN_READY, manager.updateAudioFocus(true, Player.STATE_IDLE))
        assertNull(shadow.lastAudioFocusRequest)
    }

    @Test fun spike_pauseKeepsFocusButStoppingAbandonsIt() {
        val manager = spikeManager(RecordingControl())
        manager.updateAudioFocus(true, Player.STATE_READY)
        val held = shadow.lastAudioFocusRequest
        val pauseCommand = manager.updateAudioFocus(false, Player.STATE_READY)
        assertEquals(AudioFocusManager.PLAYER_COMMAND_PLAY_WHEN_READY, pauseCommand)
        assertNull("a paused-but-prepared player keeps focus in Media3 1.11.1", shadow.lastAbandonedAudioFocusRequest)
        assertSame(held, shadow.lastAudioFocusRequest)
        manager.updateAudioFocus(false, Player.STATE_IDLE)
        assertNotNull("IDLE abandons focus", shadow.lastAbandonedAudioFocusRequest)
    }

    @Test fun spike_transientLossAndRegainAreDeliveredAsPlayerCommands() {
        val control = RecordingControl()
        val manager = spikeManager(control)
        manager.updateAudioFocus(true, Player.STATE_READY)
        lastFocusListener().onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        idleMainLooper()
        assertEquals(listOf(AudioFocusManager.PLAYER_COMMAND_WAIT_FOR_CALLBACK), control.commands)
        lastFocusListener().onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        idleMainLooper()
        assertEquals(
            listOf(AudioFocusManager.PLAYER_COMMAND_WAIT_FOR_CALLBACK, AudioFocusManager.PLAYER_COMMAND_PLAY_WHEN_READY),
            control.commands,
        )
    }

    @Test fun spike_permanentLossIsDoNotPlay() {
        val control = RecordingControl()
        val manager = spikeManager(control)
        manager.updateAudioFocus(true, Player.STATE_READY)
        lastFocusListener().onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        idleMainLooper()
        assertEquals(listOf(AudioFocusManager.PLAYER_COMMAND_DO_NOT_PLAY), control.commands)
    }

    @Test fun spike_duckIsAVolumeMultiplierNotAPlayerCommand() {
        val control = RecordingControl()
        val manager = spikeManager(control)
        manager.updateAudioFocus(true, Player.STATE_READY)
        lastFocusListener().onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        idleMainLooper()
        assertTrue(control.commands.isEmpty())
        assertEquals(0.2f, manager.volumeMultiplier, 0.0001f)
        assertEquals(listOf(0.2f), control.multipliers)
        lastFocusListener().onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        idleMainLooper()
        assertEquals(1f, manager.volumeMultiplier, 0.0001f)
    }

    @Test fun spike_releaseAbandonsHeldFocusAndIsHarmlessTwice() {
        val manager = spikeManager(RecordingControl())
        manager.updateAudioFocus(true, Player.STATE_READY)
        manager.release()
        assertNotNull(shadow.lastAbandonedAudioFocusRequest)
        manager.release() // must not throw
    }

    @Test fun spike_deniedRequestIsDoNotPlay() {
        val manager = spikeManager(RecordingControl())
        shadow.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        assertEquals(AudioFocusManager.PLAYER_COMMAND_DO_NOT_PLAY, manager.updateAudioFocus(true, Player.STATE_READY))
    }

    @Test fun spike_delayedPlatformAnswerIsNotAWaitCommand() {
        val manager = spikeManager(RecordingControl())
        shadow.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_DELAYED)
        // Media3 1.11.1 does not opt into delayed focus gain, so a DELAYED platform answer is not a WAIT command here.
        assertEquals(AudioFocusManager.PLAYER_COMMAND_PLAY_WHEN_READY, manager.updateAudioFocus(true, Player.STATE_READY))
    }
    // ── PlayerEngine focus mapping, observed through the stable SessionFacade ────────────────────────────────────────────

    private fun fixture() = PlayerEngineFixture()

    private fun PlayerEngineFixture.play() { facade.play(); idleMainLooper() }

    @Test fun engine_oneFocusOwner_playRequestsFocusExactlyOnceAndNextNeverDoes() {
        val f = fixture()
        assertNull(shadow.lastAudioFocusRequest)
        f.play()
        val held = shadow.lastAudioFocusRequest
        assertNotNull(held)
        assertTrue(f.facade.isPlaying)
        assertEquals(listOf("P1.setPlayWhenReady(true)"), f.p1.commands)
        // A misbehaving NEXT that "plays" on its own is not observed by the engine, so it can never request focus.
        f.p2.mutate { setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) }
        idleMainLooper()
        assertSame(held, shadow.lastAudioFocusRequest)
        assertTrue(f.p2.commands.isEmpty())
    }

    @Test fun engine_stateChangeFromIdleRequestsFocusLikeExoPlayer() {
        val f = fixture()
        f.p1.mutate { setPlaybackState(Player.STATE_IDLE) }
        idleMainLooper()
        f.play() // pwr true while IDLE: no request yet (Media3 never holds focus for IDLE)
        assertNull(shadow.lastAudioFocusRequest)
        f.p1.mutate { setPlaybackState(Player.STATE_BUFFERING) }
        idleMainLooper()
        assertNotNull(shadow.lastAudioFocusRequest)
    }

    @Test fun engine_transientLossSuppressesLogicalCurrentAndRegainRestoresIt() {
        val f = fixture()
        f.play()
        f.events.events.clear()
        lastFocusListener().onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        idleMainLooper()
        // Logical state exactly as ExoPlayer reports a transient loss: playWhenReady stays true, suppression TRANSIENT.
        assertTrue(f.facade.playWhenReady)
        assertEquals(Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS, f.facade.playbackSuppressionReason)
        assertFalse(f.facade.isPlaying)
        assertFalse("physical CURRENT is held paused", f.p1.playWhenReady)
        assertEquals(listOf("suppression(1)", "isPlaying(false)"), f.events.events)
        assertEquals(
            PrimaryPlaybackInterruption.AudioFocus,
            classifyPrimarySuppressionInterruption(f.facade.playbackSuppressionReason),
        )
        f.events.events.clear()
        lastFocusListener().onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        idleMainLooper()
        assertTrue(f.facade.isPlaying)
        assertEquals(Player.PLAYBACK_SUPPRESSION_REASON_NONE, f.facade.playbackSuppressionReason)
        assertTrue(f.p1.playWhenReady)
        assertEquals(listOf("suppression(0)", "isPlaying(true)"), f.events.events)
        // Exactly one resume write: no duplicated play.
        assertEquals(listOf("P1.setPlayWhenReady(true)", "P1.setPlayWhenReady(false)", "P1.setPlayWhenReady(true)"), f.p1.commands)
    }

    @Test fun engine_permanentLossPausesLogicallyWithAudioFocusLossReason() {
        val f = fixture()
        f.play()
        f.events.events.clear()
        lastFocusListener().onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        idleMainLooper()
        assertFalse(f.facade.playWhenReady)
        assertFalse(f.facade.isPlaying)
        assertEquals(listOf("playWhenReady(false,${Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS})", "isPlaying(false)"), f.events.events)
        assertEquals(
            PrimaryPlaybackInterruption.AudioFocus,
            classifyPrimaryPlayWhenReadyInterruption(false, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS),
        )
        // A later platform GAIN must NOT resume: the user's intent was lost with the permanent loss.
        f.p1.commands.clear()
        lastFocusListener().onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        idleMainLooper()
        assertFalse(f.facade.playWhenReady)
        assertTrue(f.p1.commands.isEmpty())
        // An explicit user play afterwards works and requests focus again.
        f.play()
        assertTrue(f.facade.isPlaying)
    }

    @Test fun engine_userPauseIsAUserRequestNotAFocusLoss() {
        val f = fixture()
        f.play()
        f.events.events.clear()
        f.facade.pause(); idleMainLooper()
        assertFalse(f.facade.playWhenReady)
        assertEquals(listOf("playWhenReady(false,${Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST})", "isPlaying(false)"), f.events.events)
    }

    @Test fun engine_pauseDuringTransientLossIsRespectedAndRegainDoesNotResume() {
        val f = fixture()
        f.play()
        lastFocusListener().onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        idleMainLooper()
        f.facade.pause(); idleMainLooper()
        assertFalse(f.facade.playWhenReady)
        f.p1.commands.clear()
        lastFocusListener().onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        idleMainLooper()
        assertFalse(f.facade.playWhenReady)
        assertFalse(f.facade.isPlaying)
        assertTrue("regain must not start a paused player", f.p1.commands.isEmpty())
    }

    @Test fun engine_deniedFocusRefusesPlayWithFocusLossReason() {
        val f = fixture()
        shadow.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        f.play()
        assertFalse(f.facade.playWhenReady)
        assertFalse(f.facade.isPlaying)
        assertTrue(f.p1.commands.isEmpty())
    }

    @Test fun engine_duckMultiplierFollowsLogicalCurrentNotThePhysicalSlot() {
        val f = fixture()
        f.play()
        lastFocusListener().onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        idleMainLooper()
        assertEquals(0.2f, f.p1.volume, 0.0001f)
        assertEquals(1f, f.p2.volume, 0.0001f)
        assertTrue("a duck is not a pause", f.facade.isPlaying)
        val held = shadow.lastAudioFocusRequest
        f.engine.swapRolesForTest()
        assertEquals("duck moved with the logical CURRENT role", 0.2f, f.p2.volume, 0.0001f)
        assertEquals("old CURRENT returns to neutral", 1f, f.p1.volume, 0.0001f)
        assertSame("a role swap must not create a second focus request", held, shadow.lastAudioFocusRequest)
        lastFocusListener().onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        idleMainLooper()
        assertEquals(1f, f.p2.volume, 0.0001f)
        assertEquals(1f, f.p1.volume, 0.0001f)
    }

    @Test fun engine_focusLossAfterRoleSwapAffectsTheNewLogicalCurrent() {
        val f = fixture()
        f.play()
        f.engine.swapRolesForTest()
        f.p1.commands.clear()
        f.p2.commands.clear()
        lastFocusListener().onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        idleMainLooper()
        assertFalse(f.facade.playWhenReady)
        assertTrue("the old physical CURRENT is not driven after the swap", f.p1.commands.isEmpty())
    }

    @Test fun engine_releaseAbandonsFocusExactlyOnce() {
        val f = fixture()
        f.play()
        assertNull(shadow.lastAbandonedAudioFocusRequest)
        f.engine.release()
        assertNotNull(shadow.lastAbandonedAudioFocusRequest)
        assertEquals(1, f.engine.focusReleaseCount)
        f.engine.release()
        assertEquals(1, f.engine.focusReleaseCount)
    }
}
