package com.launchpoint.wavdrop.playback

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CF-2L4 correction: a coarse raw clock may be projected only while its stream is KNOWN to be advancing. These are the pure facts
 * that decide eligibility for the primary and the secondary (the clock itself stays pure position arithmetic).
 */
class CrossfadeAdvancementEligibilityTest {

    private val none = Player.PLAYBACK_SUPPRESSION_REASON_NONE

    @Test fun aReadyPlayingUnsuppressedPrimaryIsProjectionEligible() {
        assertTrue(isPrimaryPlaybackAdvancing(Player.STATE_READY, playWhenReady = true, playbackSuppressionReason = none))
    }

    @Test fun aReadyButNotAdvancingPrimaryIsNotEligible() {
        // READY alone is not proof: paused, or suppressed by audio focus / route
        assertFalse(isPrimaryPlaybackAdvancing(Player.STATE_READY, playWhenReady = false, playbackSuppressionReason = none))
        assertFalse(isPrimaryPlaybackAdvancing(Player.STATE_READY, true, Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS))
        assertFalse(isPrimaryPlaybackAdvancing(Player.STATE_READY, true, Player.PLAYBACK_SUPPRESSION_REASON_UNSUITABLE_AUDIO_OUTPUT))
    }

    @Test fun aBufferingOrTerminalPrimaryIsNotEligible() {
        assertFalse(isPrimaryPlaybackAdvancing(Player.STATE_BUFFERING, true, none))
        assertFalse(isPrimaryPlaybackAdvancing(Player.STATE_IDLE, true, none))
        assertFalse(isPrimaryPlaybackAdvancing(Player.STATE_ENDED, true, none))
    }

    private fun snapshot(state: Int, isPlaying: Boolean? = null) =
        if (isPlaying == null) {
            validatedSecondaryHandoffSnapshot(1, true, state, 6_000L, 180_000L)
        } else {
            validatedSecondaryHandoffSnapshot(1, true, state, 6_000L, 180_000L, isPlaying)
        }

    @Test fun anAdvancingSecondaryIsProjectionEligible() {
        assertTrue(snapshot(Player.STATE_READY, isPlaying = true)!!.isAdvancing)
        assertTrue(snapshot(Player.STATE_READY)!!.isAdvancing)
    }

    @Test fun aBufferingSecondaryStaysLifecycleValidButIsNotProjectionEligible() {
        val buffering = snapshot(Player.STATE_BUFFERING, isPlaying = false)
        assertNotNull(buffering) // valid ownership is preserved: the existing lifecycle semantics are untouched
        assertEquals(6_000L, buffering!!.positionMs)
        assertFalse(buffering.isAdvancing) // but it must never be projected through elapsed time
        assertFalse(snapshot(Player.STATE_BUFFERING)!!.isAdvancing)
    }

    @Test fun aReadySecondaryThatIsNotActuallyPlayingIsNotEligible() {
        assertFalse(snapshot(Player.STATE_READY, isPlaying = false)!!.isAdvancing)
    }

    @Test fun invalidSecondaryStatesStillFailClosed() {
        assertEquals(null, validatedSecondaryHandoffSnapshot(1, true, Player.STATE_IDLE, 6_000L, 180_000L, true))
        assertEquals(null, validatedSecondaryHandoffSnapshot(1, false, Player.STATE_READY, 6_000L, 180_000L, true))
        assertEquals(null, validatedSecondaryHandoffSnapshot(2, true, Player.STATE_READY, 6_000L, 180_000L, true))
    }

    @Test fun theClockConstantsAndTheContinuityStandardsAreUnchanged() {
        assertEquals(80L, NATURAL_TRANSFER_ENTRY_TOLERANCE_MS)
        assertEquals(150L, NATURAL_TAKEOVER_TRANSFER_DURATION_MS)
        assertEquals(200L, NATURAL_TRANSFER_ABORT_TOLERANCE_MS)
        assertEquals(350L, NATURAL_TAKEOVER_MAX_LAG_MS)
        assertEquals(500L, NATURAL_HANDOFF_MAX_PROJECTION_AGE_MS)
        assertEquals(100L, NATURAL_HANDOFF_RAW_AGREEMENT_MS)
    }

    @Test fun productionRolloutGateRemainsFalse() {
        assertFalse(CrossfadeRolloutPolicy.RUNTIME_ENABLED)
    }
}
