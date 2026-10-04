package com.launchpoint.wavdrop.playback

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Test

/** CF-2F5: pure classification of Media3 playWhenReady-change and suppression reasons (media3 1.11.1 constants). */
class CrossfadePrimaryInterruptionPolicyTest {

    private val none = PrimaryPlaybackInterruption.None

    @Test fun audioFocusLossPauseIsAudioFocus() {
        assertEquals(
            PrimaryPlaybackInterruption.AudioFocus,
            classifyPrimaryPlayWhenReadyInterruption(false, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS),
        )
    }

    @Test fun becomingNoisyPauseIsAudioRoute() {
        assertEquals(
            PrimaryPlaybackInterruption.AudioRoute,
            classifyPrimaryPlayWhenReadyInterruption(false, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY),
        )
    }

    @Test fun userRequestPauseIsNotClassified() {
        assertEquals(none, classifyPrimaryPlayWhenReadyInterruption(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST))
    }

    @Test fun otherPlayWhenReadyReasonsAreNotClassified() {
        listOf(
            Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE,
            Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM,
            Player.PLAY_WHEN_READY_CHANGE_REASON_SUPPRESSED_TOO_LONG,
            0, 99,
        ).forEach { assertEquals("reason $it", none, classifyPrimaryPlayWhenReadyInterruption(false, it)) }
    }

    @Test fun resumeIsNeverAnInterruption() {
        listOf(
            Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS,
            Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY,
            Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
        ).forEach { assertEquals("reason $it", none, classifyPrimaryPlayWhenReadyInterruption(true, it)) }
    }

    @Test fun transientAudioFocusLossSuppressionIsAudioFocus() {
        assertEquals(
            PrimaryPlaybackInterruption.AudioFocus,
            classifyPrimarySuppressionInterruption(Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS),
        )
    }

    @Test fun unsuitableRouteAndOutputSuppressionAreAudioRoute() {
        assertEquals(
            PrimaryPlaybackInterruption.AudioRoute,
            classifyPrimarySuppressionInterruption(Player.PLAYBACK_SUPPRESSION_REASON_UNSUITABLE_AUDIO_ROUTE),
        )
        assertEquals(
            PrimaryPlaybackInterruption.AudioRoute,
            classifyPrimarySuppressionInterruption(Player.PLAYBACK_SUPPRESSION_REASON_UNSUITABLE_AUDIO_OUTPUT),
        )
    }

    @Test fun suppressionNoneAndScrubbingAndUnknownAreNotClassified() {
        assertEquals(none, classifyPrimarySuppressionInterruption(Player.PLAYBACK_SUPPRESSION_REASON_NONE))
        assertEquals(none, classifyPrimarySuppressionInterruption(Player.PLAYBACK_SUPPRESSION_REASON_SCRUBBING))
        assertEquals(none, classifyPrimarySuppressionInterruption(99))
    }

    @Test fun playbackStatesAloneAreNotInterruptions() {
        // Buffering / ready / idle / ended are playback STATES, not playWhenReady or suppression reasons; the classifiers
        // take no state input, so BUFFERING / READY can never be classified as an interruption.
        assertEquals(false, isPrimaryTerminalPlaybackState(Player.STATE_BUFFERING))
        assertEquals(false, isPrimaryTerminalPlaybackState(Player.STATE_READY))
    }
}
