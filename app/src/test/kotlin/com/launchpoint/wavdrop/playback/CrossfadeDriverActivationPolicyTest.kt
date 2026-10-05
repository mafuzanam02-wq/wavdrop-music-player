package com.launchpoint.wavdrop.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The pure persisted-duration activation policy shared by the NEXT preparation driver and the promotion runtime. The lifecycle
 * behaviour of each owner (start once, enabled->enabled updates only, OFF cancels first) is covered where each owner is tested
 * (NextSlotInvalidationTest, CrossfadePromotionRuntimeTest, CrossfadeOverlapInterruptionTest).
 */
class CrossfadeDriverActivationPolicyTest {

    @Test fun decisionTable() {
        assertEquals(CrossfadeActivationDecision.NoOp, decideCrossfadeDriverActivation(null, 0L))
        assertEquals(CrossfadeActivationDecision.Start, decideCrossfadeDriverActivation(null, 6_000L))
        assertEquals(CrossfadeActivationDecision.Start, decideCrossfadeDriverActivation(0L, 6_000L))
        assertEquals(CrossfadeActivationDecision.UpdateOnly, decideCrossfadeDriverActivation(6_000L, 6_000L))
        assertEquals(CrossfadeActivationDecision.UpdateOnly, decideCrossfadeDriverActivation(6_000L, 3_000L))
        assertEquals(CrossfadeActivationDecision.Disable, decideCrossfadeDriverActivation(6_000L, 0L))
        assertEquals(CrossfadeActivationDecision.NoOp, decideCrossfadeDriverActivation(0L, 0L))
    }

    @Test fun aRepeatedEnabledValueNeverRestartsAndEnabledOffEnabledStartsAgain() {
        assertEquals(CrossfadeActivationDecision.Start, decideCrossfadeDriverActivation(null, 6_000L))
        assertEquals(CrossfadeActivationDecision.UpdateOnly, decideCrossfadeDriverActivation(6_000L, 6_000L))
        assertEquals(CrossfadeActivationDecision.Disable, decideCrossfadeDriverActivation(6_000L, 0L))
        assertEquals(CrossfadeActivationDecision.Start, decideCrossfadeDriverActivation(0L, 6_000L))
    }
}
