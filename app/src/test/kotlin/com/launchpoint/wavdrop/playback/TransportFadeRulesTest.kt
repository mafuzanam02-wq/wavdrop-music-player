package com.launchpoint.wavdrop.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TransportFadeRulesTest {
    @Test fun `defaults are disabled and 300 ms`() {
        assertFalse(TransportFadeRules.DEFAULT_ENABLED)
        assertEquals(300L, TransportFadeRules.DEFAULT_DURATION_MS)
        assertEquals(100L, TransportFadeRules.MIN_DURATION_MS)
        assertEquals(800L, TransportFadeRules.MAX_DURATION_MS)
        assertEquals(50L, TransportFadeRules.STEP_MS)
    }

    @Test fun `valid values are kept`() {
        for (v in listOf(100L, 150L, 300L, 450L, 800L)) assertEquals(v, TransportFadeRules.normalizeDurationMs(v))
    }

    @Test fun `missing and invalid values fall back or clamp and snap to the step`() {
        assertEquals(300L, TransportFadeRules.normalizeDurationMs(null))
        assertEquals(100L, TransportFadeRules.normalizeDurationMs(0L))
        assertEquals(100L, TransportFadeRules.normalizeDurationMs(-500L))
        assertEquals(100L, TransportFadeRules.normalizeDurationMs(99L))
        assertEquals(800L, TransportFadeRules.normalizeDurationMs(801L))
        assertEquals(800L, TransportFadeRules.normalizeDurationMs(Long.MAX_VALUE))
        assertEquals(100L, TransportFadeRules.normalizeDurationMs(Long.MIN_VALUE))
        assertEquals(300L, TransportFadeRules.normalizeDurationMs(310L))
        assertEquals(350L, TransportFadeRules.normalizeDurationMs(325L))
    }

}
