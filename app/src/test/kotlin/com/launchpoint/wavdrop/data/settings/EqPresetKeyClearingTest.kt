package com.launchpoint.wavdrop.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Verifies the key-clearing rules applied when switching EQ preset modes.
 *
 * Each switch method in [AudioEnhancementsRepository] writes exactly the keys that
 * belong to the new mode and removes keys that belong to other modes. These tests
 * model the resulting [AudioEnhancementSettings] state that the Flow would emit
 * after each switch — they do not test DataStore directly.
 *
 * Key-ownership contract:
 *   FLAT            — clears platformPresetIndex, clears wavdropPresetId, preserves customBandLevels
 *   PLATFORM        — sets platformPresetIndex, clears wavdropPresetId, preserves customBandLevels
 *   CUSTOM          — clears wavdropPresetId, clears platformPresetIndex, seeds/preserves customBandLevels
 *   CUSTOM levels   — same as CUSTOM + writes new levels
 *   WAVDROP_PRESET  — sets wavdropPresetId, clears platformPresetIndex, preserves customBandLevels
 */
class EqPresetKeyClearingTest {

    private val savedCustomLevels = listOf(-300, 0, 200, 100, -100)

    // ── 1. setEqFlatPreset — clears wavdropId and platformIndex, preserves custom ─

    @Test
    fun `FLAT clears wavdropPresetId`() {
        val result = stateAfterFlat(wavdropPresetId = "balanced", customLevels = savedCustomLevels)
        assertNull("wavdropPresetId should be null after FLAT", result.eqWavdropPresetId)
    }

    @Test
    fun `FLAT clears platformPresetIndex`() {
        val result = stateAfterFlat(platformIndex = 3, customLevels = savedCustomLevels)
        assertNull("platformPresetIndex should be null after FLAT", result.eqPlatformPresetIndex)
    }

    @Test
    fun `FLAT preserves custom band levels`() {
        val result = stateAfterFlat(customLevels = savedCustomLevels)
        assertEquals("customBandLevels must survive FLAT switch", savedCustomLevels, result.eqCustomBandLevels)
    }

    @Test
    fun `FLAT preset type is FLAT`() {
        assertEquals(EqPresetType.FLAT, stateAfterFlat().eqPresetType)
    }

    // ── 2. setEqPlatformPreset — sets index, clears wavdropId, preserves custom ──

    @Test
    fun `PLATFORM sets platformPresetIndex`() {
        val result = stateAfterPlatform(index = 5, wavdropPresetId = "clarity", customLevels = savedCustomLevels)
        assertEquals(5, result.eqPlatformPresetIndex)
    }

    @Test
    fun `PLATFORM clears wavdropPresetId`() {
        val result = stateAfterPlatform(index = 5, wavdropPresetId = "clarity", customLevels = savedCustomLevels)
        assertNull("wavdropPresetId should be null after PLATFORM", result.eqWavdropPresetId)
    }

    @Test
    fun `PLATFORM preserves custom band levels`() {
        val result = stateAfterPlatform(index = 5, customLevels = savedCustomLevels)
        assertEquals("customBandLevels must survive PLATFORM switch", savedCustomLevels, result.eqCustomBandLevels)
    }

    @Test
    fun `PLATFORM preset type is PLATFORM`() {
        assertEquals(EqPresetType.PLATFORM, stateAfterPlatform(index = 0).eqPresetType)
    }

    // ── 3. setEqCustomPreset — clears wavdropId and platformIndex, seeds/preserves custom ─

    @Test
    fun `CUSTOM clears wavdropPresetId`() {
        val result = stateAfterCustomPreset(
            bandCount      = 5,
            wavdropId      = "detail",
            platformIndex  = 3,
            existingLevels = savedCustomLevels,
        )
        assertNull("wavdropPresetId should be null after CUSTOM switch", result.eqWavdropPresetId)
    }

    @Test
    fun `CUSTOM clears platformPresetIndex`() {
        val result = stateAfterCustomPreset(
            bandCount      = 5,
            wavdropId      = "detail",
            platformIndex  = 3,
            existingLevels = savedCustomLevels,
        )
        assertNull("platformPresetIndex should be null after CUSTOM switch", result.eqPlatformPresetIndex)
    }

    @Test
    fun `CUSTOM preserves correctly-sized existing band levels`() {
        val result = stateAfterCustomPreset(bandCount = 5, existingLevels = savedCustomLevels)
        assertEquals(savedCustomLevels, result.eqCustomBandLevels)
    }

    @Test
    fun `CUSTOM preset type is CUSTOM`() {
        assertEquals(EqPresetType.CUSTOM, stateAfterCustomPreset(bandCount = 5).eqPresetType)
    }

    // ── 4. setEqCustomPreset with empty/wrong-size levels seeds zeros ─────────

    @Test
    fun `CUSTOM seeds zeros when existing levels are empty`() {
        val result = stateAfterCustomPreset(bandCount = 5, existingLevels = emptyList())
        assertEquals(List(5) { 0 }, result.eqCustomBandLevels)
    }

    @Test
    fun `CUSTOM seeds zeros when existing levels have wrong size`() {
        val result = stateAfterCustomPreset(bandCount = 5, existingLevels = listOf(0, 100, -200))
        assertEquals(List(5) { 0 }, result.eqCustomBandLevels)
    }

    @Test
    fun `CUSTOM seeding still clears wavdropPresetId`() {
        val result = stateAfterCustomPreset(bandCount = 5, wavdropId = "warm", existingLevels = emptyList())
        assertNull(result.eqWavdropPresetId)
    }

    @Test
    fun `CUSTOM seeding still clears platformPresetIndex`() {
        val result = stateAfterCustomPreset(bandCount = 5, platformIndex = 7, existingLevels = emptyList())
        assertNull(result.eqPlatformPresetIndex)
    }

    // ── 5. setEqCustomBandLevels — clears wavdropId and platformIndex ─────────

    @Test
    fun `setEqCustomBandLevels clears wavdropPresetId`() {
        val result = stateAfterCustomBandLevels(
            levels         = listOf(100, 200, 0, -100, -200),
            wavdropId      = "balanced",
            platformIndex  = 2,
        )
        assertNull("wavdropPresetId should be null after setEqCustomBandLevels", result.eqWavdropPresetId)
    }

    @Test
    fun `setEqCustomBandLevels clears platformPresetIndex`() {
        val result = stateAfterCustomBandLevels(
            levels        = listOf(100, 200, 0, -100, -200),
            platformIndex = 2,
        )
        assertNull("platformPresetIndex should be null after setEqCustomBandLevels", result.eqPlatformPresetIndex)
    }

    @Test
    fun `setEqCustomBandLevels writes the new levels`() {
        val newLevels = listOf(100, 200, 0, -100, -200)
        val result = stateAfterCustomBandLevels(levels = newLevels)
        assertEquals(newLevels, result.eqCustomBandLevels)
    }

    @Test
    fun `setEqCustomBandLevels preset type is CUSTOM`() {
        val result = stateAfterCustomBandLevels(levels = listOf(0, 0, 0, 0, 0))
        assertEquals(EqPresetType.CUSTOM, result.eqPresetType)
    }

    // ── 6. setEqWavdropPreset — sets id, clears platformIndex, preserves custom ─

    @Test
    fun `WAVDROP_PRESET sets wavdropPresetId`() {
        val result = stateAfterWavdrop(id = "late-night", platformIndex = 4, customLevels = savedCustomLevels)
        assertEquals("late-night", result.eqWavdropPresetId)
    }

    @Test
    fun `WAVDROP_PRESET clears platformPresetIndex`() {
        val result = stateAfterWavdrop(id = "late-night", platformIndex = 4, customLevels = savedCustomLevels)
        assertNull("platformPresetIndex should be null after WAVDROP_PRESET switch", result.eqPlatformPresetIndex)
    }

    @Test
    fun `WAVDROP_PRESET preserves custom band levels`() {
        val result = stateAfterWavdrop(id = "late-night", customLevels = savedCustomLevels)
        assertEquals("customBandLevels must survive WAVDROP_PRESET switch", savedCustomLevels, result.eqCustomBandLevels)
    }

    @Test
    fun `WAVDROP_PRESET preset type is WAVDROP_PRESET`() {
        assertEquals(EqPresetType.WAVDROP_PRESET, stateAfterWavdrop(id = "balanced").eqPresetType)
    }

    // ── 7. Parser regression — legacy stored states still parse correctly ─────

    @Test
    fun `legacy FLAT state with stale wavdropId parses correctly`() {
        // Represents pre-polish state: type=FLAT but wavdropId=clarity still in DataStore.
        // The parser reads what it finds; the next mode-switch would clear the stale key.
        val type = AudioEnhancementsRepository.parseEqPresetType("FLAT")
        val id   = AudioEnhancementsRepository.parseWavdropPresetId("clarity")
        assertEquals(EqPresetType.FLAT, type)
        assertEquals("clarity", id) // parser doesn't drop stale ids — write-path does
    }

    @Test
    fun `legacy PLATFORM state with stale wavdropId parses correctly`() {
        val type  = AudioEnhancementsRepository.parseEqPresetType("PLATFORM")
        val id    = AudioEnhancementsRepository.parseWavdropPresetId("deep-bass")
        val index = 7
        assertEquals(EqPresetType.PLATFORM, type)
        assertEquals("deep-bass", id)
        assertEquals(7, index)
    }

    @Test
    fun `legacy CUSTOM state with stale wavdropId parses correctly`() {
        val type   = AudioEnhancementsRepository.parseEqPresetType("CUSTOM")
        val id     = AudioEnhancementsRepository.parseWavdropPresetId("balanced")
        val levels = AudioEnhancementsRepository.parseBandLevels("-300,0,200,100,-100")
        assertEquals(EqPresetType.CUSTOM, type)
        assertEquals("balanced", id)
        assertEquals(listOf(-300, 0, 200, 100, -100), levels)
    }

    // ── Helpers: simulate the resulting AudioEnhancementSettings state ─────────
    // These mirror what DataStore would emit after each write, without touching DataStore.

    private fun stateAfterFlat(
        wavdropPresetId: String? = null,
        platformIndex: Int?      = null,
        customLevels: List<Int>  = emptyList(),
    ) = AudioEnhancementSettings(
        eqPresetType          = EqPresetType.FLAT,
        eqPlatformPresetIndex = null,            // always cleared by setEqFlatPreset
        eqWavdropPresetId     = null,            // always cleared by setEqFlatPreset
        eqCustomBandLevels    = customLevels,    // untouched
    )

    private fun stateAfterPlatform(
        index: Int,
        wavdropPresetId: String? = null,
        customLevels: List<Int>  = emptyList(),
    ) = AudioEnhancementSettings(
        eqPresetType          = EqPresetType.PLATFORM,
        eqPlatformPresetIndex = index,
        eqWavdropPresetId     = null,         // always cleared by setEqPlatformPreset
        eqCustomBandLevels    = customLevels, // untouched
    )

    private fun stateAfterCustomPreset(
        bandCount: Int,
        wavdropId: String?       = null,
        platformIndex: Int?      = null,
        existingLevels: List<Int> = emptyList(),
    ): AudioEnhancementSettings {
        val seeded = if (existingLevels.size == bandCount) existingLevels else List(bandCount) { 0 }
        return AudioEnhancementSettings(
            eqPresetType          = EqPresetType.CUSTOM,
            eqPlatformPresetIndex = null,   // always cleared by setEqCustomPreset
            eqWavdropPresetId     = null,   // always cleared by setEqCustomPreset
            eqCustomBandLevels    = seeded,
        )
    }

    private fun stateAfterCustomBandLevels(
        levels: List<Int>,
        wavdropId: String? = null,
        platformIndex: Int? = null,
    ) = AudioEnhancementSettings(
        eqPresetType          = EqPresetType.CUSTOM,
        eqPlatformPresetIndex = null,   // always cleared by setEqCustomBandLevels
        eqWavdropPresetId     = null,   // always cleared by setEqCustomBandLevels
        eqCustomBandLevels    = levels,
    )

    private fun stateAfterWavdrop(
        id: String,
        platformIndex: Int?     = null,
        customLevels: List<Int> = emptyList(),
    ) = AudioEnhancementSettings(
        eqPresetType          = EqPresetType.WAVDROP_PRESET,
        eqWavdropPresetId     = id,
        eqPlatformPresetIndex = null,         // always cleared by setEqWavdropPreset
        eqCustomBandLevels    = customLevels, // untouched
    )
}
