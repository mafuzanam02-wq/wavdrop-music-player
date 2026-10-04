package com.launchpoint.wavdrop.ui.screen.settings

import com.launchpoint.wavdrop.playback.CrossfadeRolloutPolicy
import com.launchpoint.wavdrop.playback.CrossfadeRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** CF-2J1: pure option, formatting and presentation policy for the rollout-gated Crossfade setting. */
class CrossfadeSettingsUiPolicyTest {

    // ── options ──────────────────────────────────────────────────────────────

    @Test fun optionsIncludeOffAndExpectedProductChoices() {
        assertEquals(
            listOf(0L, 2_000L, 4_000L, 6_000L, 8_000L, 10_000L, 12_000L),
            CROSSFADE_DURATION_OPTIONS.map { it.durationMs },
        )
        assertEquals(
            listOf("Off", "2 seconds", "4 seconds", "6 seconds", "8 seconds", "10 seconds", "12 seconds"),
            CROSSFADE_DURATION_OPTIONS.map { it.label },
        )
    }

    @Test fun enabledOptionsAreUniqueAscendingAndWithinCf1Range() {
        val enabled = CROSSFADE_DURATION_OPTIONS.map { it.durationMs }.filter { it != CrossfadeRules.OFF_MS }
        assertEquals(enabled.size, enabled.toSet().size)
        assertEquals(enabled.sorted(), enabled)
        assertTrue(enabled.all { it in CrossfadeRules.MIN_ENABLED_DURATION_MS..CrossfadeRules.MAX_DURATION_MS })
        assertEquals(12_000L, enabled.max())
        assertEquals(CrossfadeRules.OFF_MS, CROSSFADE_DURATION_OPTIONS.first().durationMs)
    }

    // ── formatting ───────────────────────────────────────────────────────────

    @Test fun formatting() {
        assertEquals("Off", formatCrossfadeDurationMs(0L))
        assertEquals("1 second", formatCrossfadeDurationMs(1_000L))
        assertEquals("2 seconds", formatCrossfadeDurationMs(2_000L))
        assertEquals("6.5 seconds", formatCrossfadeDurationMs(6_500L))
        assertEquals("1.25 seconds", formatCrossfadeDurationMs(1_250L))
        assertEquals("1.5 seconds", formatCrossfadeDurationMs(1_500L))
        assertEquals("12 seconds", formatCrossfadeDurationMs(12_000L))
    }

    @Test fun formattingNormalizesBeforeDisplay() {
        assertEquals("Off", formatCrossfadeDurationMs(-5L))
        assertEquals("Off", formatCrossfadeDurationMs(Long.MIN_VALUE))
        assertEquals("12 seconds", formatCrossfadeDurationMs(60_000L))
        assertEquals("1 second", formatCrossfadeDurationMs(1L)) // positive values are bounded up to the 1 s minimum
    }

    @Test fun formattingKeepsExactFractionWithoutTrailingZeros() {
        assertEquals("6.05 seconds", formatCrossfadeDurationMs(6_050L))
        assertEquals("6.001 seconds", formatCrossfadeDurationMs(6_001L))
    }

    // ── selection ────────────────────────────────────────────────────────────

    @Test fun exactProductDurationSelectsThatOption() {
        assertEquals(6_000L, selectedCrossfadeOption(6_000L)?.durationMs)
        assertEquals(CrossfadeRules.OFF_MS, selectedCrossfadeOption(0L)?.durationMs)
        assertEquals(12_000L, selectedCrossfadeOption(12_000L)?.durationMs)
    }

    @Test fun nonProductDurationSelectsNothingAndIsNeverRounded() {
        assertNull(selectedCrossfadeOption(6_500L))
        assertNull(selectedCrossfadeOption(1_000L))
        assertNull(selectedCrossfadeOption(11_999L))
    }

    // ── presentation policy ─────────────────────────────────────────────────

    @Test fun runtimeUnavailableHidesTheSettingRegardlessOfEq() {
        assertEquals(CrossfadeSettingUiState(false, false, ""), buildCrossfadeSettingUiState(false, false, 6_000L))
        assertEquals(CrossfadeSettingUiState(false, false, ""), buildCrossfadeSettingUiState(false, true, 6_000L))
        assertFalse(shouldShowCrossfadeSetting(false))
    }

    @Test fun runtimeAvailableWithEqOffIsVisibleEnabledAndShowsSavedValue() {
        assertEquals(CrossfadeSettingUiState(true, true, "Off"), buildCrossfadeSettingUiState(true, false, 0L))
        assertEquals(CrossfadeSettingUiState(true, true, "6 seconds"), buildCrossfadeSettingUiState(true, false, 6_000L))
        assertEquals(CrossfadeSettingUiState(true, true, "6.5 seconds"), buildCrossfadeSettingUiState(true, false, 6_500L))
        assertTrue(shouldShowCrossfadeSetting(true))
    }

    @Test fun runtimeAvailableWithEqOnIsVisibleDisabledWithExplanation() {
        val unavailable = CrossfadeSettingUiState(true, false, "Unavailable while Equalizer is on")
        assertEquals(unavailable, buildCrossfadeSettingUiState(true, true, 0L))
        assertEquals(unavailable, buildCrossfadeSettingUiState(true, true, 6_000L))
        assertEquals(unavailable, buildCrossfadeSettingUiState(true, true, 6_500L))
    }

    @Test fun savedDurationIsNotAffectedByEqPresentation() {
        // The policy is a pure function of its inputs: the stored value is untouched and resumes when EQ turns off.
        val saved = 6_000L
        buildCrossfadeSettingUiState(true, true, saved)
        assertEquals("6 seconds", buildCrossfadeSettingUiState(true, false, saved).summary)
    }

    @Test fun productionRolloutIsStillOffSoNothingIsShown() {
        assertFalse(CrossfadeRolloutPolicy.RUNTIME_ENABLED)
        assertFalse(buildCrossfadeSettingUiState(CrossfadeRolloutPolicy.RUNTIME_ENABLED, false, 6_000L).visible)
    }
}
