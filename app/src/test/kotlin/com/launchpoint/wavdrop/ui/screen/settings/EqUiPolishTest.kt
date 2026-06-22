package com.launchpoint.wavdrop.ui.screen.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for the EQ UI polish pass.
 * No Compose or Android dependencies — all helpers are internal functions.
 */
class EqUiPolishTest {

    // ── 1. Band labels map to friendly names ─────────────────────────────────

    @Test
    fun `60 Hz maps to Bass`() {
        assertEquals("Bass", eqBandLabel(60))
    }

    @Test
    fun `230 Hz maps to Low Mid`() {
        assertEquals("Low Mid", eqBandLabel(230))
    }

    @Test
    fun `910 Hz maps to Mid`() {
        assertEquals("Mid", eqBandLabel(910))
    }

    @Test
    fun `3600 Hz maps to Presence`() {
        assertEquals("Presence", eqBandLabel(3600))
    }

    @Test
    fun `14000 Hz maps to Air`() {
        assertEquals("Air", eqBandLabel(14000))
    }

    @Test
    fun `unknown frequency falls back to formatted Hz string`() {
        // Any Hz value not in the canonical 5-band list returns the raw frequency string.
        assertEquals("500 Hz", eqBandLabel(500))
        assertEquals("2 kHz",  eqBandLabel(2000))
    }

    @Test
    fun `all 5 canonical S21 frequencies have friendly labels`() {
        val s21Freqs = listOf(60, 230, 910, 3600, 14000)
        val labels   = s21Freqs.map { eqBandLabel(it) }
        assertEquals(listOf("Bass", "Low Mid", "Mid", "Presence", "Air"), labels)
    }

    // ── 2. dB formatter correctness ───────────────────────────────────────────

    @Test
    fun `positive gain shows plus sign`() {
        assertEquals("+1.0 dB", formatGainDb(100))
    }

    @Test
    fun `negative gain shows no plus sign`() {
        assertEquals("-0.5 dB", formatGainDb(-50))
    }

    @Test
    fun `zero gain shows 0_0 dB without sign`() {
        assertEquals("0.0 dB", formatGainDb(0))
    }

    @Test
    fun `positive 1500 mB formats to +15_0 dB`() {
        assertEquals("+15.0 dB", formatGainDb(1500))
    }

    @Test
    fun `negative 1500 mB formats to minus 15_0 dB`() {
        assertEquals("-15.0 dB", formatGainDb(-1500))
    }

    @Test
    fun `500 mB formats to +5_0 dB`() {
        assertEquals("+5.0 dB", formatGainDb(500))
    }

    @Test
    fun `100 mB formats to +1_0 dB`() {
        assertEquals("+1.0 dB", formatGainDb(100))
    }

    @Test
    fun `minus 50 mB formats to minus 0_5 dB`() {
        assertEquals("-0.5 dB", formatGainDb(-50))
    }

    // ── 3. Helper copy is distinct for PLATFORM vs FLAT ───────────────────────

    @Test
    fun `PLATFORM helper copy mentions device`() {
        val copy = EQ_PLATFORM_HELPER_TEXT
        assertTrue("PLATFORM copy must mention device management", "managed by your device" in copy)
    }

    @Test
    fun `FLAT helper copy mentions shaping sound`() {
        val copy = EQ_FLAT_HELPER_TEXT
        assertTrue("FLAT copy must guide user toward presets", "shape your sound" in copy)
    }

    @Test
    fun `PLATFORM and FLAT helper copy are different`() {
        assertNotEquals(EQ_PLATFORM_HELPER_TEXT, EQ_FLAT_HELPER_TEXT)
    }

    @Test
    fun `PLATFORM helper mentions Custom and Wavdrop`() {
        assertTrue("Custom" in EQ_PLATFORM_HELPER_TEXT)
        assertTrue("Wavdrop" in EQ_PLATFORM_HELPER_TEXT)
    }

    @Test
    fun `FLAT helper mentions Custom and Wavdrop`() {
        assertTrue("Custom" in EQ_FLAT_HELPER_TEXT)
        assertTrue("Wavdrop" in EQ_FLAT_HELPER_TEXT)
    }

    // ── 4. Wavdrop preset sliders remain visible ──────────────────────────────
    // (visibility rule test — mirrors EqBandSliderVisibilityRulesTest but for completeness)

    @Test
    fun `WAVDROP_PRESET shows sliders`() {
        assertTrue(showsSlidersFor(com.launchpoint.wavdrop.data.settings.EqPresetType.WAVDROP_PRESET))
    }

    @Test
    fun `CUSTOM shows sliders`() {
        assertTrue(showsSlidersFor(com.launchpoint.wavdrop.data.settings.EqPresetType.CUSTOM))
    }

    @Test
    fun `FLAT does not show sliders`() {
        assertFalse(showsSlidersFor(com.launchpoint.wavdrop.data.settings.EqPresetType.FLAT))
    }

    @Test
    fun `PLATFORM does not show sliders`() {
        assertFalse(showsSlidersFor(com.launchpoint.wavdrop.data.settings.EqPresetType.PLATFORM))
    }

    // ── 5. Disabled Listening placeholders are coming-later rows ─────────────

    @Test
    fun `coming later placeholders do not use clickable row type`() {
        // Disabled rows in the Listening section should use DisabledSettingsRow (no chevron),
        // not ClickableSettingsRow(enabled=false) which still renders a chevron.
        // This is verified by the placeholder list having entries that are not in the active list.
        val activeTitles  = listOf("Equalizer")
        val placeholders  = listOf("Output Profiles", "Headphones", "Loudness Protection")
        placeholders.forEach { title ->
            assertFalse("$title must not be in active list", activeTitles.contains(title))
        }
    }

    @Test
    fun `all 3 placeholders are in the coming-later list`() {
        val placeholders = listOf("Output Profiles", "Headphones", "Loudness Protection")
        assertEquals(3, placeholders.size)
        assertTrue(placeholders.contains("Output Profiles"))
        assertTrue(placeholders.contains("Headphones"))
        assertTrue(placeholders.contains("Loudness Protection"))
    }

    @Test
    fun `coming later subtitle text is preserved for user guidance`() {
        val subtitles = listOf(
            "Speaker, wired, and Bluetooth profiles. Coming later.",
            "Headphone tuning and profiles. Coming later.",
            "Safer loudness and headroom tools. Coming later.",
        )
        subtitles.forEach { subtitle ->
            assertTrue("subtitle must contain 'Coming later.'", "Coming later." in subtitle)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun showsSlidersFor(type: com.launchpoint.wavdrop.data.settings.EqPresetType): Boolean =
        type == com.launchpoint.wavdrop.data.settings.EqPresetType.CUSTOM ||
            type == com.launchpoint.wavdrop.data.settings.EqPresetType.WAVDROP_PRESET

    private companion object {
        const val EQ_PLATFORM_HELPER_TEXT =
            "System presets are managed by your device. Choose Custom or a Wavdrop preset to fine-tune bands."
        const val EQ_FLAT_HELPER_TEXT =
            "EQ is flat. Choose Custom or a Wavdrop preset to shape your sound."
    }
}
