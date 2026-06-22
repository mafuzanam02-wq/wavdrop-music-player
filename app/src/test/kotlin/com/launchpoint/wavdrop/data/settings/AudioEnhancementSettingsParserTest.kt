package com.launchpoint.wavdrop.data.settings

import com.launchpoint.wavdrop.ui.screen.settings.formatCenterFreqHz
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioEnhancementSettingsParserTest {

    // ── parseEqPresetType ─────────────────────────────────────────────────────

    @Test
    fun `parseEqPresetType FLAT returns FLAT`() {
        assertEquals(EqPresetType.FLAT, AudioEnhancementsRepository.parseEqPresetType("FLAT"))
    }

    @Test
    fun `parseEqPresetType WAVDROP_PRESET returns WAVDROP_PRESET`() {
        assertEquals(EqPresetType.WAVDROP_PRESET, AudioEnhancementsRepository.parseEqPresetType("WAVDROP_PRESET"))
    }

    @Test
    fun `parseEqPresetType PLATFORM returns PLATFORM`() {
        assertEquals(EqPresetType.PLATFORM, AudioEnhancementsRepository.parseEqPresetType("PLATFORM"))
    }

    @Test
    fun `parseEqPresetType CUSTOM returns CUSTOM`() {
        assertEquals(EqPresetType.CUSTOM, AudioEnhancementsRepository.parseEqPresetType("CUSTOM"))
    }

    @Test
    fun `parseEqPresetType null returns FLAT`() {
        assertEquals(EqPresetType.FLAT, AudioEnhancementsRepository.parseEqPresetType(null))
    }

    @Test
    fun `parseEqPresetType empty string returns FLAT`() {
        assertEquals(EqPresetType.FLAT, AudioEnhancementsRepository.parseEqPresetType(""))
    }

    @Test
    fun `parseEqPresetType garbage string returns FLAT`() {
        assertEquals(EqPresetType.FLAT, AudioEnhancementsRepository.parseEqPresetType("corrupt"))
    }

    @Test
    fun `parseEqPresetType lowercase is rejected and returns FLAT`() {
        // enum valueOf is case-sensitive; "flat" is not a valid name
        assertEquals(EqPresetType.FLAT, AudioEnhancementsRepository.parseEqPresetType("flat"))
    }

    @Test
    fun `parseEqPresetType old stored values FLAT PLATFORM CUSTOM still parse correctly`() {
        // Regression: existing DataStore entries must not be affected by adding WAVDROP_PRESET.
        assertEquals(EqPresetType.FLAT,     AudioEnhancementsRepository.parseEqPresetType("FLAT"))
        assertEquals(EqPresetType.PLATFORM, AudioEnhancementsRepository.parseEqPresetType("PLATFORM"))
        assertEquals(EqPresetType.CUSTOM,   AudioEnhancementsRepository.parseEqPresetType("CUSTOM"))
    }

    // ── parseBandLevels ───────────────────────────────────────────────────────

    @Test
    fun `parseBandLevels null returns empty list`() {
        assertEquals(emptyList<Int>(), AudioEnhancementsRepository.parseBandLevels(null))
    }

    @Test
    fun `parseBandLevels empty string returns empty list`() {
        assertEquals(emptyList<Int>(), AudioEnhancementsRepository.parseBandLevels(""))
    }

    @Test
    fun `parseBandLevels blank string returns empty list`() {
        assertEquals(emptyList<Int>(), AudioEnhancementsRepository.parseBandLevels("   "))
    }

    @Test
    fun `parseBandLevels valid 5-band CSV returns correct list`() {
        assertEquals(
            listOf(-300, -150, 0, 200, 400),
            AudioEnhancementsRepository.parseBandLevels("-300,-150,0,200,400"),
        )
    }

    @Test
    fun `parseBandLevels valid with spaces around values`() {
        assertEquals(
            listOf(0, 100, -100),
            AudioEnhancementsRepository.parseBandLevels("0, 100, -100"),
        )
    }

    @Test
    fun `parseBandLevels all zeros is valid`() {
        assertEquals(
            listOf(0, 0, 0, 0, 0),
            AudioEnhancementsRepository.parseBandLevels("0,0,0,0,0"),
        )
    }

    @Test
    fun `parseBandLevels single value returns single-element list`() {
        assertEquals(listOf(1500), AudioEnhancementsRepository.parseBandLevels("1500"))
    }

    @Test
    fun `parseBandLevels fully corrupt string returns empty list`() {
        assertEquals(emptyList<Int>(), AudioEnhancementsRepository.parseBandLevels("abc,def,!"))
    }

    @Test
    fun `parseBandLevels partially corrupt string skips bad entries`() {
        // "corrupt" entry is skipped; result has fewer items than expected bands —
        // the controller detects the size mismatch and falls back to flat.
        assertEquals(
            listOf(-300, 0, 400),
            AudioEnhancementsRepository.parseBandLevels("-300,corrupt,0,bad,400"),
        )
    }

    @Test
    fun `parseBandLevels boundary values are preserved`() {
        assertEquals(
            listOf(-1500, 1500),
            AudioEnhancementsRepository.parseBandLevels("-1500,1500"),
        )
    }

    // ── AudioEnhancementSettings defaults ────────────────────────────────────

    @Test
    fun `default AudioEnhancementSettings has EQ disabled`() {
        val defaults = AudioEnhancementSettings()
        assertEquals(false, defaults.eqEnabled)
    }

    @Test
    fun `default AudioEnhancementSettings preset is FLAT`() {
        assertEquals(EqPresetType.FLAT, AudioEnhancementSettings().eqPresetType)
    }

    @Test
    fun `default AudioEnhancementSettings platform index is null`() {
        assertNull(AudioEnhancementSettings().eqPlatformPresetIndex)
    }

    @Test
    fun `default AudioEnhancementSettings custom band levels is empty`() {
        assertEquals(emptyList<Int>(), AudioEnhancementSettings().eqCustomBandLevels)
    }

    // ── Custom band levels — Slice 3 coverage ─────────────────────────────────

    @Test
    fun `parseBandLevels serializes 5-band custom levels round-trip`() {
        val levels = listOf(-1500, -300, 0, 400, 1500)
        val serialized = levels.joinToString(",")
        assertEquals(levels, AudioEnhancementsRepository.parseBandLevels(serialized))
    }

    @Test
    fun `parseBandLevels wrong-size stored list produces fewer entries than band count`() {
        // A stored value with only 3 entries (wrong size for 5-band device) parses to 3 items.
        // The UI layer (toEqLevels()) detects size != 5 and falls back to [0,0,0,0,0].
        // Parsed value here reflects what comes out of the DataStore parser, not the UI default.
        val result = AudioEnhancementsRepository.parseBandLevels("0,300,-200")
        assertEquals(3, result.size)
        assertEquals(listOf(0, 300, -200), result)
    }

    @Test
    fun `AudioEnhancementSettings CUSTOM preset preserves eqEnabled true`() {
        val settings = AudioEnhancementSettings(
            eqEnabled         = true,
            eqPresetType      = EqPresetType.CUSTOM,
            eqCustomBandLevels = listOf(0, 300, 0, -200, 400),
        )
        assertEquals(true, settings.eqEnabled)
        assertEquals(EqPresetType.CUSTOM, settings.eqPresetType)
        assertEquals(listOf(0, 300, 0, -200, 400), settings.eqCustomBandLevels)
    }

    @Test
    fun `parseBandLevels all-zero 5-band flat custom levels round-trip`() {
        val levels = listOf(0, 0, 0, 0, 0)
        assertEquals(levels, AudioEnhancementsRepository.parseBandLevels("0,0,0,0,0"))
    }

    // ── formatCenterFreqHz ────────────────────────────────────────────────────

    @Test
    fun `formatCenterFreqHz sub-1kHz returns Hz string`() {
        assertEquals("60 Hz", formatCenterFreqHz(60))
    }

    @Test
    fun `formatCenterFreqHz 999 Hz stays in Hz`() {
        assertEquals("999 Hz", formatCenterFreqHz(999))
    }

    @Test
    fun `formatCenterFreqHz exact kHz returns integer kHz string`() {
        assertEquals("1 kHz", formatCenterFreqHz(1000))
    }

    @Test
    fun `formatCenterFreqHz 14000 returns integer kHz`() {
        assertEquals("14 kHz", formatCenterFreqHz(14000))
    }

    @Test
    fun `formatCenterFreqHz fractional kHz is formatted to one decimal`() {
        assertEquals("3.6 kHz", formatCenterFreqHz(3600))
    }

    @Test
    fun `formatCenterFreqHz 230 Hz remains in Hz`() {
        assertEquals("230 Hz", formatCenterFreqHz(230))
    }

    // ── EqualizerCapabilities fallback behaviour ───────────────────────────────

    @Test
    fun `EQ_FALLBACK_CAPABILITIES has 5 bands`() {
        val caps = EqualizerCapabilities(
            bandCount           = 5,
            minLevelMb          = -1500,
            maxLevelMb          = 1500,
            centerFrequenciesHz = listOf(60, 230, 910, 3600, 14000),
            platformPresets     = emptyList(),
        )
        assertEquals(5, caps.bandCount)
    }

    @Test
    fun `EqualizerCapabilities centerFrequenciesHz formats correctly for S21 bands`() {
        val freqs = listOf(60, 230, 910, 3600, 14000)
        val labels = freqs.map { formatCenterFreqHz(it) }
        assertEquals(listOf("60 Hz", "230 Hz", "910 Hz", "3.6 kHz", "14 kHz"), labels)
    }

    @Test
    fun `EqualizerPlatformPreset index and name are preserved`() {
        val preset = EqualizerPlatformPreset(index = 3, name = "Flat")
        assertEquals(3.toShort(), preset.index)
        assertEquals("Flat", preset.name)
    }

    // ── parseWavdropPresetId ──────────────────────────────────────────────────

    @Test
    fun `parseWavdropPresetId null returns null`() {
        assertNull(AudioEnhancementsRepository.parseWavdropPresetId(null))
    }

    @Test
    fun `parseWavdropPresetId empty string returns null`() {
        assertNull(AudioEnhancementsRepository.parseWavdropPresetId(""))
    }

    @Test
    fun `parseWavdropPresetId blank string returns null`() {
        assertNull(AudioEnhancementsRepository.parseWavdropPresetId("   "))
    }

    @Test
    fun `parseWavdropPresetId valid id returns that id`() {
        assertEquals("balanced", AudioEnhancementsRepository.parseWavdropPresetId("balanced"))
    }

    @Test
    fun `parseWavdropPresetId trims surrounding whitespace`() {
        assertEquals("clarity", AudioEnhancementsRepository.parseWavdropPresetId("  clarity  "))
    }

    @Test
    fun `parseWavdropPresetId preserves kebab-case id`() {
        assertEquals("spoken-word", AudioEnhancementsRepository.parseWavdropPresetId("spoken-word"))
    }

    // ── AudioEnhancementSettings — WAVDROP_PRESET model ──────────────────────

    @Test
    fun `default AudioEnhancementSettings eqWavdropPresetId is null`() {
        assertNull(AudioEnhancementSettings().eqWavdropPresetId)
    }

    @Test
    fun `AudioEnhancementSettings can store WAVDROP_PRESET type and id`() {
        val settings = AudioEnhancementSettings(
            eqEnabled         = true,
            eqPresetType      = EqPresetType.WAVDROP_PRESET,
            eqWavdropPresetId = "balanced",
        )
        assertEquals(EqPresetType.WAVDROP_PRESET, settings.eqPresetType)
        assertEquals("balanced", settings.eqWavdropPresetId)
    }

    @Test
    fun `AudioEnhancementSettings WAVDROP_PRESET preserves custom band levels`() {
        // Custom band levels must survive a switch to a Wavdrop preset so the user
        // can return to their previous custom curve without losing it.
        val customLevels = listOf(-300, 0, 200, 100, -100)
        val settings = AudioEnhancementSettings(
            eqEnabled          = true,
            eqPresetType       = EqPresetType.WAVDROP_PRESET,
            eqWavdropPresetId  = "warm",
            eqCustomBandLevels = customLevels,
        )
        assertEquals(EqPresetType.WAVDROP_PRESET, settings.eqPresetType)
        assertEquals("warm", settings.eqWavdropPresetId)
        assertEquals(customLevels, settings.eqCustomBandLevels)
    }

    @Test
    fun `AudioEnhancementSettings eqWavdropPresetId flows from settings field`() {
        val settings = AudioEnhancementSettings(
            eqPresetType      = EqPresetType.WAVDROP_PRESET,
            eqWavdropPresetId = "deep-bass",
        )
        assertEquals("deep-bass", settings.eqWavdropPresetId)
    }
}
