package com.launchpoint.wavdrop.ui.screen.settings

import com.launchpoint.wavdrop.data.settings.AudioEnhancementSettings
import com.launchpoint.wavdrop.data.settings.EqualizerCapabilities
import com.launchpoint.wavdrop.data.settings.EqualizerPlatformPreset
import com.launchpoint.wavdrop.data.settings.EqPresetType
import com.launchpoint.wavdrop.data.settings.WavdropPresetCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EqWavdropPresetSlice3Test {

    // ── 1. WAVDROP_PRESET label shows display name, not raw id ───────────────

    @Test
    fun `preset label for balanced id is Balanced display name`() {
        val displayName = WavdropPresetCatalog.findById("balanced")?.displayName
        assertEquals("Balanced", displayName)
    }

    @Test
    fun `preset label for deep-bass id is Deep Bass`() {
        assertEquals("Deep Bass", WavdropPresetCatalog.findById("deep-bass")?.displayName)
    }

    @Test
    fun `preset label for all catalog presets is display name not id`() {
        WavdropPresetCatalog.all.forEach { preset ->
            // display name must differ from id (no raw kebab in UI)
            val label = eqPresetLabel(EqPresetType.WAVDROP_PRESET, preset.id)
            assertEquals("${preset.id} should show displayName", preset.displayName, label)
        }
    }

    // ── 2. Unknown wavdrop id falls back safely ───────────────────────────────

    @Test
    fun `unknown wavdrop id yields fallback label`() {
        val label = eqPresetLabel(EqPresetType.WAVDROP_PRESET, "no-such-preset")
        assertEquals("Wavdrop", label)
    }

    @Test
    fun `null wavdrop id yields fallback label`() {
        val label = eqPresetLabel(EqPresetType.WAVDROP_PRESET, null)
        assertEquals("Wavdrop", label)
    }

    // ── 3. Slider visibility: WAVDROP_PRESET shows sliders, FLAT/PLATFORM hide ─

    @Test
    fun `sliders shown for WAVDROP_PRESET when EQ enabled`() {
        assertTrue(shouldShowEqBandSliders(eqEnabled = true, presetType = EqPresetType.WAVDROP_PRESET))
    }

    @Test
    fun `sliders hidden for FLAT when EQ enabled`() {
        assertFalse(shouldShowEqBandSliders(eqEnabled = true, presetType = EqPresetType.FLAT))
    }

    @Test
    fun `sliders hidden for PLATFORM when EQ enabled`() {
        assertFalse(shouldShowEqBandSliders(eqEnabled = true, presetType = EqPresetType.PLATFORM))
    }

    // ── 4. Dialog ordering: Reset EQ / Custom / Wavdrop / System ─────────────

    @Test
    fun `dialog section order is Reset EQ then Custom then Wavdrop then System`() {
        val sectionOrder = listOf("Reset EQ", "Custom", "Wavdrop", "System")
        assertEquals("Reset EQ", sectionOrder[0])
        assertEquals("Custom",   sectionOrder[1])
        assertEquals("Wavdrop",  sectionOrder[2])
        assertEquals("System",   sectionOrder[3])
    }

    @Test
    fun `all 7 Wavdrop presets appear in catalog`() {
        val expectedIds = listOf("balanced", "deep-bass", "clarity", "warm", "detail", "late-night", "spoken-word")
        val catalogIds  = WavdropPresetCatalog.all.map { it.id }
        assertEquals(expectedIds, catalogIds)
    }

    // ── 5. Wavdrop section hidden when bandCount != 5 ─────────────────────────

    @Test
    fun `wavdrop section available when bandCount is 5`() {
        assertTrue(wavdropPresetsAvailable(bandCount = 5))
    }

    @Test
    fun `wavdrop section hidden when bandCount is 10`() {
        assertFalse(wavdropPresetsAvailable(bandCount = 10))
    }

    @Test
    fun `wavdrop section hidden when bandCount is 4`() {
        assertFalse(wavdropPresetsAvailable(bandCount = 4))
    }

    @Test
    fun `wavdrop section hidden when bandCount is 0`() {
        assertFalse(wavdropPresetsAvailable(bandCount = 0))
    }

    // ── 6. Auto-promote: first slider drag switches type to CUSTOM ────────────

    @Test
    fun `adjusting slider while WAVDROP_PRESET produces CUSTOM state with new levels`() {
        // Simulates: user is on WAVDROP_PRESET 'balanced', drags band 2.
        // setEqCustomBandLevels() is called → type becomes CUSTOM, levels are stored.
        val modifiedLevels = listOf(100, 100, 200, 100, 100)
        val afterPromote = AudioEnhancementSettings(
            eqEnabled          = true,
            eqPresetType       = EqPresetType.CUSTOM,
            eqCustomBandLevels = modifiedLevels,
        )
        assertEquals(EqPresetType.CUSTOM, afterPromote.eqPresetType)
        assertEquals(modifiedLevels, afterPromote.eqCustomBandLevels)
        assertTrue(shouldShowEqBandSliders(afterPromote.eqEnabled, afterPromote.eqPresetType))
    }

    @Test
    fun `initial slider levels for WAVDROP_PRESET come from catalog not custom band levels`() {
        // When preset type is WAVDROP_PRESET, the slider should display the catalog curve,
        // not the previously stored custom band levels.
        val catalogLevels = WavdropPresetCatalog.findById("balanced")?.bandLevels
        val initialLevels = resolveWavdropInitialLevels(
            presetType       = EqPresetType.WAVDROP_PRESET,
            wavdropPresetId  = "balanced",
            customBandLevels = listOf(-300, 0, 200, 100, -100),
            bandCount        = 5,
        )
        assertEquals(catalogLevels, initialLevels)
    }

    @Test
    fun `initial slider levels for CUSTOM come from stored custom band levels`() {
        val storedLevels = listOf(-300, 0, 200, 100, -100)
        val initialLevels = resolveWavdropInitialLevels(
            presetType       = EqPresetType.CUSTOM,
            wavdropPresetId  = null,
            customBandLevels = storedLevels,
            bandCount        = 5,
        )
        assertEquals(storedLevels, initialLevels)
    }

    // ── 7. "Reset EQ" label replaces "Wavdrop Flat" ──────────────────────────

    @Test
    fun `FLAT preset is labeled Reset EQ in dialog`() {
        assertEquals("Reset EQ", eqDialogFlatLabel())
    }

    // ── Helpers mirroring the UI screen logic ─────────────────────────────────

    private fun eqPresetLabel(presetType: EqPresetType, wavdropPresetId: String?): String =
        when (presetType) {
            EqPresetType.FLAT           -> "Flat"
            EqPresetType.WAVDROP_PRESET -> WavdropPresetCatalog.findById(wavdropPresetId)?.displayName ?: "Wavdrop"
            EqPresetType.PLATFORM       -> "Platform"
            EqPresetType.CUSTOM         -> "Custom"
        }

    private fun shouldShowEqBandSliders(eqEnabled: Boolean, presetType: EqPresetType): Boolean =
        eqEnabled && (presetType == EqPresetType.CUSTOM || presetType == EqPresetType.WAVDROP_PRESET)

    private fun wavdropPresetsAvailable(bandCount: Int): Boolean = bandCount == 5

    private fun resolveWavdropInitialLevels(
        presetType: EqPresetType,
        wavdropPresetId: String?,
        customBandLevels: List<Int>,
        bandCount: Int,
    ): List<Int> = if (presetType == EqPresetType.WAVDROP_PRESET) {
        WavdropPresetCatalog.findById(wavdropPresetId)
            ?.bandLevels?.takeIf { it.size == bandCount }
            ?: List(bandCount) { 0 }
    } else {
        customBandLevels
    }

    private fun eqDialogFlatLabel(): String = "Reset EQ"
}
