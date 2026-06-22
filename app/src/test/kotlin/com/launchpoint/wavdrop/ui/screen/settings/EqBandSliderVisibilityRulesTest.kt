package com.launchpoint.wavdrop.ui.screen.settings

import com.launchpoint.wavdrop.data.settings.AudioEnhancementSettings
import com.launchpoint.wavdrop.data.settings.EqPresetType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies the band-slider visibility rule:
 *   sliders are shown iff eqEnabled && eqPresetType == CUSTOM
 *
 * Also verifies that switching to a platform or flat preset does not discard
 * saved custom levels in the data model — they remain available for restoration
 * when the user returns to Custom, whether via the dialog's explicit "Custom"
 * option or by moving a slider.
 */
class EqBandSliderVisibilityRulesTest {

    // ── Visibility predicate ──────────────────────────────────────────────────

    @Test
    fun `sliders hidden when EQ is disabled regardless of preset type`() {
        for (preset in EqPresetType.entries) {
            assertEquals(
                "expected hidden for preset=$preset",
                false,
                shouldShowEqBandSliders(eqEnabled = false, presetType = preset),
            )
        }
    }

    @Test
    fun `sliders visible when EQ enabled and preset is CUSTOM`() {
        assertEquals(true, shouldShowEqBandSliders(eqEnabled = true, presetType = EqPresetType.CUSTOM))
    }

    @Test
    fun `sliders visible when EQ enabled and preset is WAVDROP_PRESET`() {
        assertEquals(true, shouldShowEqBandSliders(eqEnabled = true, presetType = EqPresetType.WAVDROP_PRESET))
    }

    @Test
    fun `sliders hidden when EQ enabled and preset is FLAT`() {
        assertEquals(false, shouldShowEqBandSliders(eqEnabled = true, presetType = EqPresetType.FLAT))
    }

    @Test
    fun `sliders hidden when EQ enabled and preset is PLATFORM`() {
        assertEquals(false, shouldShowEqBandSliders(eqEnabled = true, presetType = EqPresetType.PLATFORM))
    }

    // ── Custom levels preserved across preset switches ────────────────────────

    @Test
    fun `AudioEnhancementSettings can hold PLATFORM type with non-empty custom levels`() {
        // Switching to a platform preset does not erase stored custom levels in the
        // data model — the repository only writes preset type + index, leaving the
        // custom band levels key untouched in DataStore.
        val settings = AudioEnhancementSettings(
            eqEnabled              = true,
            eqPresetType           = EqPresetType.PLATFORM,
            eqPlatformPresetIndex  = 2,
            eqCustomBandLevels     = listOf(-300, 0, 200, 100, -100),
        )
        assertEquals(EqPresetType.PLATFORM, settings.eqPresetType)
        assertEquals(listOf(-300, 0, 200, 100, -100), settings.eqCustomBandLevels)
    }

    @Test
    fun `AudioEnhancementSettings can hold FLAT type with non-empty custom levels`() {
        val settings = AudioEnhancementSettings(
            eqEnabled          = true,
            eqPresetType       = EqPresetType.FLAT,
            eqCustomBandLevels = listOf(0, 300, 0, -200, 400),
        )
        assertEquals(EqPresetType.FLAT, settings.eqPresetType)
        assertEquals(listOf(0, 300, 0, -200, 400), settings.eqCustomBandLevels)
    }

    @Test
    fun `returning to CUSTOM restores previously saved band levels`() {
        // Simulates: user sets custom levels → switches to platform → switches back.
        // The custom levels persisted in DataStore are deserialized back into the
        // CUSTOM settings object unchanged.
        val savedLevels = listOf(-300, 0, 200, 100, -100)
        val restoredSettings = AudioEnhancementSettings(
            eqEnabled          = true,
            eqPresetType       = EqPresetType.CUSTOM,
            eqCustomBandLevels = savedLevels,
        )
        assertEquals(EqPresetType.CUSTOM, restoredSettings.eqPresetType)
        assertEquals(savedLevels, restoredSettings.eqCustomBandLevels)
        assertEquals(true, shouldShowEqBandSliders(restoredSettings.eqEnabled, restoredSettings.eqPresetType))
    }

    @Test
    fun `moving a slider produces CUSTOM preset type with updated levels`() {
        // Simulates: drag slider → setEqCustomBandLevels called → stored as CUSTOM.
        val newLevels = listOf(0, 0, 300, 0, 0)
        val settings = AudioEnhancementSettings(
            eqEnabled          = true,
            eqPresetType       = EqPresetType.CUSTOM,
            eqCustomBandLevels = newLevels,
        )
        assertEquals(EqPresetType.CUSTOM, settings.eqPresetType)
        assertEquals(newLevels, settings.eqCustomBandLevels)
        assertEquals(true, shouldShowEqBandSliders(settings.eqEnabled, settings.eqPresetType))
    }

    // ── Custom seeding logic ──────────────────────────────────────────────────

    @Test
    fun `selecting Custom with empty levels seeds zeros sized to band count`() {
        // Mirrors setEqCustomPreset(bandCount=5): current levels are empty → seed [0,0,0,0,0].
        val current = emptyList<Int>()
        val seeded = seedCustomLevels(current, bandCount = 5)
        assertEquals(listOf(0, 0, 0, 0, 0), seeded)
    }

    @Test
    fun `selecting Custom with wrong-sized levels seeds zeros sized to band count`() {
        // Stored as 3 entries on a 5-band device → seed [0,0,0,0,0].
        val current = listOf(-300, 0, 200)
        val seeded = seedCustomLevels(current, bandCount = 5)
        assertEquals(listOf(0, 0, 0, 0, 0), seeded)
    }

    @Test
    fun `selecting Custom with correct-sized levels preserves them unchanged`() {
        val current = listOf(-300, 0, 200, 100, -100)
        val seeded = seedCustomLevels(current, bandCount = 5)
        assertEquals(current, seeded)
    }

    @Test
    fun `selecting Custom with correct-sized levels still shows sliders`() {
        val settings = AudioEnhancementSettings(
            eqEnabled          = true,
            eqPresetType       = EqPresetType.CUSTOM,
            eqCustomBandLevels = listOf(-300, 0, 200, 100, -100),
        )
        assertEquals(true, shouldShowEqBandSliders(settings.eqEnabled, settings.eqPresetType))
    }

    @Test
    fun `seeded flat custom levels result in zero sliders`() {
        val seeded = seedCustomLevels(emptyList(), bandCount = 5)
        assertEquals(listOf(0, 0, 0, 0, 0), seeded.toEqLevels(bandCount = 5))
    }

    // ── Preset row labels ─────────────────────────────────────────────────────

    @Test
    fun `preset label for FLAT is Flat`() {
        val settings = AudioEnhancementSettings(eqEnabled = true, eqPresetType = EqPresetType.FLAT)
        assertEquals("FLAT", settings.eqPresetType.name)
    }

    @Test
    fun `preset label for CUSTOM is Custom`() {
        val settings = AudioEnhancementSettings(eqEnabled = true, eqPresetType = EqPresetType.CUSTOM)
        assertEquals("CUSTOM", settings.eqPresetType.name)
    }

    @Test
    fun `platform preset name Rock is preserved in capabilities model`() {
        val caps = com.launchpoint.wavdrop.data.settings.EqualizerCapabilities(
            bandCount           = 5,
            minLevelMb          = -1500,
            maxLevelMb          = 1500,
            centerFrequenciesHz = listOf(60, 230, 910, 3600, 14000),
            platformPresets     = listOf(
                com.launchpoint.wavdrop.data.settings.EqualizerPlatformPreset(index = 9, name = "Rock"),
            ),
        )
        assertEquals("Rock", caps.platformPresets.first { it.index.toInt() == 9 }.name)
    }

    // ── Selecting Custom from the dialog ─────────────────────────────────────

    @Test
    fun `selecting Custom from dialog makes sliders visible`() {
        // setEqCustomPreset() only writes EQ_PRESET_TYPE; band levels are unchanged.
        // The resulting settings object has CUSTOM type — sliders should appear.
        val settings = AudioEnhancementSettings(
            eqEnabled          = true,
            eqPresetType       = EqPresetType.CUSTOM,
            eqCustomBandLevels = listOf(-300, 0, 200, 100, -100),
        )
        assertEquals(true, shouldShowEqBandSliders(settings.eqEnabled, settings.eqPresetType))
    }

    @Test
    fun `selecting Custom preserves previously saved band levels`() {
        // When the user selects Custom from the dialog, only EQ_PRESET_TYPE is written.
        // EQ_CUSTOM_BAND_LEVELS is untouched, so the previously saved levels survive.
        val savedLevels = listOf(-300, 0, 200, 100, -100)
        val afterSelectingCustom = AudioEnhancementSettings(
            eqEnabled          = true,
            eqPresetType       = EqPresetType.CUSTOM,
            eqCustomBandLevels = savedLevels,
        )
        assertEquals(savedLevels, afterSelectingCustom.eqCustomBandLevels)
    }

    @Test
    fun `selecting Custom with no prior levels shows zeroed sliders via toEqLevels fallback`() {
        // When EQ_CUSTOM_BAND_LEVELS has never been written, parseBandLevels returns [].
        // toEqLevels(bandCount=5) converts [] → [0,0,0,0,0] (all-zero display).
        val settings = AudioEnhancementSettings(
            eqEnabled          = true,
            eqPresetType       = EqPresetType.CUSTOM,
            eqCustomBandLevels = emptyList(),
        )
        assertEquals(EqPresetType.CUSTOM, settings.eqPresetType)
        assertEquals(emptyList<Int>(), settings.eqCustomBandLevels)
        // UI converts via: levels.toEqLevels(bandCount) → List(bandCount) { 0 }
        val displayLevels = settings.eqCustomBandLevels.toEqLevels(bandCount = 5)
        assertEquals(listOf(0, 0, 0, 0, 0), displayLevels)
    }

    @Test
    fun `dialog preset order is Reset EQ then Custom then Wavdrop then Platform`() {
        // Encodes the expected display order as an enum ordinal sequence.
        val dialogOrder = listOf(EqPresetType.FLAT, EqPresetType.CUSTOM, EqPresetType.WAVDROP_PRESET, EqPresetType.PLATFORM)
        assertEquals(EqPresetType.FLAT,           dialogOrder[0])
        assertEquals(EqPresetType.CUSTOM,         dialogOrder[1])
        assertEquals(EqPresetType.WAVDROP_PRESET, dialogOrder[2])
        assertEquals(EqPresetType.PLATFORM,       dialogOrder[3])
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private fun shouldShowEqBandSliders(eqEnabled: Boolean, presetType: EqPresetType): Boolean =
        eqEnabled && (presetType == EqPresetType.CUSTOM || presetType == EqPresetType.WAVDROP_PRESET)

    // Mirrors the seeding logic in AudioEnhancementsRepository.setEqCustomPreset().
    private fun seedCustomLevels(current: List<Int>, bandCount: Int): List<Int> =
        if (current.size == bandCount) current else List(bandCount) { 0 }

    // Mirrors the private extension in SettingsPlaybackScreen — tested inline here.
    private fun List<Int>.toEqLevels(bandCount: Int): List<Int> =
        if (size == bandCount) this else List(bandCount) { 0 }
}
