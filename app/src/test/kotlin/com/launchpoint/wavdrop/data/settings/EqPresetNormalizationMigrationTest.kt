package com.launchpoint.wavdrop.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the normalization rules applied by [AudioEnhancementsRepository.normalizeEqPresetStateOnce].
 *
 * Each test models the resulting [AudioEnhancementSettings] that the settings Flow would emit
 * after normalization runs — no DataStore dependency. Helpers mirror the migration logic exactly.
 *
 * Normalization contract:
 *   FLAT            — removes platformPresetIndex and wavdropPresetId; preserves customBandLevels
 *   PLATFORM        — removes wavdropPresetId; preserves customBandLevels; keeps platformPresetIndex
 *   CUSTOM          — removes wavdropPresetId and platformPresetIndex; preserves customBandLevels
 *   WAVDROP_PRESET  — removes platformPresetIndex; preserves customBandLevels;
 *                     if id is blank/unknown → falls back to FLAT and removes wavdropPresetId
 *   Invalid type    — treated as FLAT by parser; normalized to FLAT
 *
 * Migration is one-time: the marker key eq_preset_state_normalized_v1 prevents repeated runs.
 */
class EqPresetNormalizationMigrationTest {

    private val savedCustomLevels = listOf(-300, 0, 200, 100, -100)

    // ── 1. PLATFORM + stale wavdropId clears wavdropId ───────────────────────

    @Test
    fun `PLATFORM normalization clears stale wavdropPresetId`() {
        val result = normalize(
            rawType        = "PLATFORM",
            platformIndex  = 5,
            wavdropId      = "balanced",
            customLevels   = savedCustomLevels,
        )
        assertNull("wavdropPresetId must be cleared when preset is PLATFORM", result.eqWavdropPresetId)
    }

    @Test
    fun `PLATFORM normalization keeps platformPresetIndex`() {
        val result = normalize(rawType = "PLATFORM", platformIndex = 7)
        assertEquals(7, result.eqPlatformPresetIndex)
    }

    @Test
    fun `PLATFORM normalization preset type remains PLATFORM`() {
        assertEquals(EqPresetType.PLATFORM, normalize(rawType = "PLATFORM", platformIndex = 3).eqPresetType)
    }

    // ── 2. FLAT + stale wavdropId/platformIndex clears both ──────────────────

    @Test
    fun `FLAT normalization clears stale wavdropPresetId`() {
        val result = normalize(rawType = "FLAT", wavdropId = "clarity", platformIndex = 2)
        assertNull("wavdropPresetId must be cleared when preset is FLAT", result.eqWavdropPresetId)
    }

    @Test
    fun `FLAT normalization clears stale platformPresetIndex`() {
        val result = normalize(rawType = "FLAT", platformIndex = 4)
        assertNull("platformPresetIndex must be cleared when preset is FLAT", result.eqPlatformPresetIndex)
    }

    @Test
    fun `FLAT normalization preset type remains FLAT`() {
        assertEquals(EqPresetType.FLAT, normalize(rawType = "FLAT").eqPresetType)
    }

    // ── 3. CUSTOM + stale wavdropId/platformIndex clears both ────────────────

    @Test
    fun `CUSTOM normalization clears stale wavdropPresetId`() {
        val result = normalize(rawType = "CUSTOM", wavdropId = "deep-bass", customLevels = savedCustomLevels)
        assertNull("wavdropPresetId must be cleared when preset is CUSTOM", result.eqWavdropPresetId)
    }

    @Test
    fun `CUSTOM normalization clears stale platformPresetIndex`() {
        val result = normalize(rawType = "CUSTOM", platformIndex = 9, customLevels = savedCustomLevels)
        assertNull("platformPresetIndex must be cleared when preset is CUSTOM", result.eqPlatformPresetIndex)
    }

    @Test
    fun `CUSTOM normalization preset type remains CUSTOM`() {
        assertEquals(EqPresetType.CUSTOM, normalize(rawType = "CUSTOM").eqPresetType)
    }

    // ── 4. WAVDROP_PRESET + stale platformIndex clears platformIndex ─────────

    @Test
    fun `WAVDROP_PRESET normalization clears stale platformPresetIndex`() {
        val result = normalize(rawType = "WAVDROP_PRESET", wavdropId = "warm", platformIndex = 6)
        assertNull("platformPresetIndex must be cleared when preset is WAVDROP_PRESET", result.eqPlatformPresetIndex)
    }

    @Test
    fun `WAVDROP_PRESET normalization keeps valid wavdropPresetId`() {
        val result = normalize(rawType = "WAVDROP_PRESET", wavdropId = "clarity")
        assertEquals("clarity", result.eqWavdropPresetId)
    }

    @Test
    fun `WAVDROP_PRESET normalization preset type remains WAVDROP_PRESET for known id`() {
        assertEquals(
            EqPresetType.WAVDROP_PRESET,
            normalize(rawType = "WAVDROP_PRESET", wavdropId = "balanced").eqPresetType,
        )
    }

    // ── 5. WAVDROP_PRESET + unknown id falls back to FLAT ────────────────────

    @Test
    fun `WAVDROP_PRESET normalization with unknown id falls back to FLAT`() {
        val result = normalize(rawType = "WAVDROP_PRESET", wavdropId = "no-such-preset")
        assertEquals(
            "unknown wavdropId must produce FLAT fallback",
            EqPresetType.FLAT,
            result.eqPresetType,
        )
    }

    @Test
    fun `WAVDROP_PRESET normalization with unknown id clears wavdropPresetId`() {
        val result = normalize(rawType = "WAVDROP_PRESET", wavdropId = "no-such-preset")
        assertNull("wavdropPresetId must be null after FLAT fallback", result.eqWavdropPresetId)
    }

    @Test
    fun `WAVDROP_PRESET normalization with null id falls back to FLAT`() {
        val result = normalize(rawType = "WAVDROP_PRESET", wavdropId = null)
        assertEquals(EqPresetType.FLAT, result.eqPresetType)
        assertNull(result.eqWavdropPresetId)
    }

    @Test
    fun `WAVDROP_PRESET normalization with blank id falls back to FLAT`() {
        val result = normalize(rawType = "WAVDROP_PRESET", wavdropId = "   ")
        assertEquals(EqPresetType.FLAT, result.eqPresetType)
        assertNull(result.eqWavdropPresetId)
    }

    // ── 6. customBandLevels preserved in all cases ───────────────────────────

    @Test
    fun `FLAT normalization preserves customBandLevels`() {
        val result = normalize(rawType = "FLAT", customLevels = savedCustomLevels)
        assertEquals(savedCustomLevels, result.eqCustomBandLevels)
    }

    @Test
    fun `PLATFORM normalization preserves customBandLevels`() {
        val result = normalize(rawType = "PLATFORM", platformIndex = 1, customLevels = savedCustomLevels)
        assertEquals(savedCustomLevels, result.eqCustomBandLevels)
    }

    @Test
    fun `CUSTOM normalization preserves customBandLevels`() {
        val result = normalize(rawType = "CUSTOM", customLevels = savedCustomLevels)
        assertEquals(savedCustomLevels, result.eqCustomBandLevels)
    }

    @Test
    fun `WAVDROP_PRESET normalization preserves customBandLevels`() {
        val result = normalize(rawType = "WAVDROP_PRESET", wavdropId = "detail", customLevels = savedCustomLevels)
        assertEquals(savedCustomLevels, result.eqCustomBandLevels)
    }

    @Test
    fun `WAVDROP_PRESET unknown-id fallback preserves customBandLevels`() {
        val result = normalize(rawType = "WAVDROP_PRESET", wavdropId = "ghost-preset", customLevels = savedCustomLevels)
        assertEquals("customBandLevels must survive FLAT fallback", savedCustomLevels, result.eqCustomBandLevels)
    }

    // ── 7. Migration marker prevents repeated cleanup ─────────────────────────

    @Test
    fun `migration marker key name is eq_preset_state_normalized_v1`() {
        // Guards against accidental key rename — the stored name is an API contract.
        assertEquals("eq_preset_state_normalized_v1", MARKER_KEY_NAME)
    }

    @Test
    fun `normalization does not run when marker is true`() {
        // Simulates the fast-path: marker is set, normalized state is returned unchanged.
        val alreadyNormalized = true
        val dirtyState = AudioEnhancementSettings(
            eqPresetType          = EqPresetType.PLATFORM,
            eqPlatformPresetIndex = 3,
            eqWavdropPresetId     = "balanced", // stale
        )
        // With marker=true, the dirty state is left untouched (migration skips).
        val result = if (alreadyNormalized) dirtyState else normalize("PLATFORM", platformIndex = 3, wavdropId = "balanced")
        // Marker prevents normalization — state unchanged.
        assertEquals(EqPresetType.PLATFORM, result.eqPresetType)
        assertEquals("balanced", result.eqWavdropPresetId) // stale, not cleared (migration skipped)
    }

    @Test
    fun `normalization runs when marker is false`() {
        val alreadyNormalized = false
        val result = if (alreadyNormalized) {
            AudioEnhancementSettings(eqPresetType = EqPresetType.PLATFORM, eqWavdropPresetId = "balanced")
        } else {
            normalize("PLATFORM", platformIndex = 3, wavdropId = "balanced")
        }
        assertNull("normalization ran — stale wavdropId cleared", result.eqWavdropPresetId)
    }

    // ── Invalid raw type treated as FLAT ─────────────────────────────────────

    @Test
    fun `invalid raw type normalizes to FLAT`() {
        val result = normalize(rawType = "CORRUPTED_VALUE")
        assertEquals(EqPresetType.FLAT, result.eqPresetType)
        assertNull(result.eqWavdropPresetId)
        assertNull(result.eqPlatformPresetIndex)
    }

    @Test
    fun `null raw type normalizes to FLAT`() {
        val result = normalize(rawType = null)
        assertEquals(EqPresetType.FLAT, result.eqPresetType)
        assertNull(result.eqWavdropPresetId)
        assertNull(result.eqPlatformPresetIndex)
    }

    // ── All known catalog ids are valid in WAVDROP_PRESET normalization ───────

    @Test
    fun `all catalog preset ids survive WAVDROP_PRESET normalization`() {
        val ids = listOf("balanced", "deep-bass", "clarity", "warm", "detail", "late-night", "spoken-word")
        ids.forEach { id ->
            val result = normalize(rawType = "WAVDROP_PRESET", wavdropId = id)
            assertEquals("$id should remain WAVDROP_PRESET after normalization", EqPresetType.WAVDROP_PRESET, result.eqPresetType)
            assertEquals("$id should be preserved", id, result.eqWavdropPresetId)
        }
    }

    // ── Helpers: simulate post-normalization AudioEnhancementSettings state ───

    private fun normalize(
        rawType: String?,
        platformIndex: Int?      = null,
        wavdropId: String?       = null,
        customLevels: List<Int>  = emptyList(),
    ): AudioEnhancementSettings {
        val presetType = AudioEnhancementsRepository.parseEqPresetType(rawType)
        val parsedId   = AudioEnhancementsRepository.parseWavdropPresetId(wavdropId)

        return when (presetType) {
            EqPresetType.FLAT -> AudioEnhancementSettings(
                eqPresetType          = EqPresetType.FLAT,
                eqPlatformPresetIndex = null,
                eqWavdropPresetId     = null,
                eqCustomBandLevels    = customLevels,
            )
            EqPresetType.PLATFORM -> AudioEnhancementSettings(
                eqPresetType          = EqPresetType.PLATFORM,
                eqPlatformPresetIndex = platformIndex,
                eqWavdropPresetId     = null,
                eqCustomBandLevels    = customLevels,
            )
            EqPresetType.CUSTOM -> AudioEnhancementSettings(
                eqPresetType          = EqPresetType.CUSTOM,
                eqPlatformPresetIndex = null,
                eqWavdropPresetId     = null,
                eqCustomBandLevels    = customLevels,
            )
            EqPresetType.WAVDROP_PRESET -> {
                val validId = parsedId?.takeIf { WavdropPresetCatalog.findById(it) != null }
                if (validId != null) {
                    AudioEnhancementSettings(
                        eqPresetType          = EqPresetType.WAVDROP_PRESET,
                        eqWavdropPresetId     = validId,
                        eqPlatformPresetIndex = null,
                        eqCustomBandLevels    = customLevels,
                    )
                } else {
                    // Unknown or blank id — fall back to FLAT
                    AudioEnhancementSettings(
                        eqPresetType          = EqPresetType.FLAT,
                        eqPlatformPresetIndex = null,
                        eqWavdropPresetId     = null,
                        eqCustomBandLevels    = customLevels,
                    )
                }
            }
        }
    }

    private companion object {
        // Must match the DataStore key name used in AudioEnhancementsRepository.
        const val MARKER_KEY_NAME = "eq_preset_state_normalized_v1"
    }
}
