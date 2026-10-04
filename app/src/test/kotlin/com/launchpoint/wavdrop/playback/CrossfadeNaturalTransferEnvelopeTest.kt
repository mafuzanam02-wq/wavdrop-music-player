package com.launchpoint.wavdrop.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** CF-2L2: the pure parts of the internal soft ownership transfer (constants, progress, complementary equal-power gains). */
class CrossfadeNaturalTransferEnvelopeTest {

    @Test fun constantsAreTheDocumentedEngineeringValues() {
        assertEquals(150L, NATURAL_TAKEOVER_TRANSFER_DURATION_MS)
        assertEquals(80L, NATURAL_TRANSFER_ENTRY_TOLERANCE_MS)
        assertEquals(200L, NATURAL_TRANSFER_ABORT_TOLERANCE_MS)
        assertEquals(350L, NATURAL_TAKEOVER_MAX_LAG_MS) // unchanged reconciliation bound, a different concept
    }

    @Test fun entryIsStricterThanAbortWhichIsStricterThanTheReconciliationBound() {
        assertTrue(NATURAL_TRANSFER_ENTRY_TOLERANCE_MS < NATURAL_TRANSFER_ABORT_TOLERANCE_MS)
        assertTrue(NATURAL_TRANSFER_ABORT_TOLERANCE_MS < NATURAL_TAKEOVER_MAX_LAG_MS)
    }

    @Test fun progressFollowsTheMonotonicClockAndIsClamped() {
        assertEquals(0f, naturalTransferProgress(1_000L, 1_000L), 0f)
        assertEquals(0.5f, naturalTransferProgress(1_000L, 1_075L), 1e-6f)
        assertEquals(1f, naturalTransferProgress(1_000L, 1_150L), 0f)
        assertEquals(1f, naturalTransferProgress(1_000L, 90_000L), 0f)
        assertEquals(0f, naturalTransferProgress(1_000L, 900L), 0f) // a clock that went backwards is progress 0, never negative
    }

    @Test fun gainsStartPrimarySilentSecondaryFullAndEndExactlySwapped() {
        assertEquals(NaturalTransferGains(primary = 0f, secondary = 1f), naturalTransferGains(0f))
        assertEquals(NaturalTransferGains(primary = 1f, secondary = 0f), naturalTransferGains(1f))
        assertEquals(NaturalTransferGains(primary = 0f, secondary = 1f), naturalTransferGains(Float.NaN))
        assertEquals(NaturalTransferGains(primary = 1f, secondary = 0f), naturalTransferGains(7f))
    }

    @Test fun gainsReuseTheSingleEqualPowerCurveAndStayComplementary() {
        var previous = naturalTransferGains(0f)
        for (i in 1..99) {
            val p = i / 100f
            val gains = naturalTransferGains(p)
            val curve = CrossfadeGainCurve.equalPower(p)
            assertEquals(curve.incoming, gains.primary, 0f)
            assertEquals(curve.outgoing, gains.secondary, 0f)
            assertEquals(1.0, (gains.primary * gains.primary + gains.secondary * gains.secondary).toDouble(), 1e-5)
            assertTrue(isValidCrossfadeGain(gains.primary) && isValidCrossfadeGain(gains.secondary))
            assertTrue(gains.primary >= previous.primary)
            assertTrue(gains.secondary <= previous.secondary)
            previous = gains
        }
    }

    @Test fun transferIsNotAUserSettingAndTheGateStaysFalse() {
        assertFalse(CrossfadeRolloutPolicy.RUNTIME_ENABLED)
    }
}
