package com.launchpoint.wavdrop.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WavdropPresetResolverTest {

    // S21 capabilities used for all tests
    private val bandCount  = 5
    private val minLevelMb = -1500
    private val maxLevelMb = 1500

    // ── Null / missing id ─────────────────────────────────────────────────────

    @Test
    fun `null id returns null`() {
        assertNull(resolveWavdropBandLevels(null, bandCount, minLevelMb, maxLevelMb))
    }

    @Test
    fun `unknown id returns null`() {
        assertNull(resolveWavdropBandLevels("unknown-preset", bandCount, minLevelMb, maxLevelMb))
    }

    @Test
    fun `empty id returns null`() {
        // Empty string is not a valid catalog id
        assertNull(resolveWavdropBandLevels("", bandCount, minLevelMb, maxLevelMb))
    }

    // ── Band count mismatch ───────────────────────────────────────────────────

    @Test
    fun `wrong band count returns null`() {
        // Catalog has 5-band presets; device reporting 10 bands should get null (flat fallback)
        assertNull(resolveWavdropBandLevels("balanced", bandCount = 10, minLevelMb, maxLevelMb))
    }

    @Test
    fun `wrong band count of 4 returns null`() {
        assertNull(resolveWavdropBandLevels("warm", bandCount = 4, minLevelMb, maxLevelMb))
    }

    // ── Valid resolution ──────────────────────────────────────────────────────

    @Test
    fun `valid preset with matching band count returns levels`() {
        val result = resolveWavdropBandLevels("balanced", bandCount, minLevelMb, maxLevelMb)
        assertNotNull(result)
        assertEquals(listOf(100, 100, 0, 100, 100), result)
    }

    @Test
    fun `deep-bass preset resolves correctly`() {
        val result = resolveWavdropBandLevels("deep-bass", bandCount, minLevelMb, maxLevelMb)
        assertNotNull(result)
        assertEquals(listOf(500, 300, -100, 0, 0), result)
    }

    @Test
    fun `clarity preset resolves correctly`() {
        val result = resolveWavdropBandLevels("clarity", bandCount, minLevelMb, maxLevelMb)
        assertNotNull(result)
        assertEquals(listOf(-200, -100, 200, 500, 200), result)
    }

    @Test
    fun `spoken-word preset resolves correctly`() {
        val result = resolveWavdropBandLevels("spoken-word", bandCount, minLevelMb, maxLevelMb)
        assertNotNull(result)
        assertEquals(listOf(-400, -200, 200, 500, 100), result)
    }

    @Test
    fun `resolved levels have correct size`() {
        val result = resolveWavdropBandLevels("warm", bandCount, minLevelMb, maxLevelMb)
        assertNotNull(result)
        assertEquals(bandCount, result!!.size)
    }

    // ── Clamping ──────────────────────────────────────────────────────────────

    @Test
    fun `levels within range are not clamped`() {
        // All catalog values are ≤ ±500 mB; S21 range is ±1500 mB — no clamping expected
        val result = resolveWavdropBandLevels("deep-bass", bandCount, minLevelMb, maxLevelMb)
        assertNotNull(result)
        assertEquals(listOf(500, 300, -100, 0, 0), result)
    }

    @Test
    fun `levels above maxLevelMb are clamped down`() {
        // Use a very tight device range to force clamping of catalog values
        val tightMax = 200
        val result = resolveWavdropBandLevels("detail", bandCount, minLevelMb = -200, maxLevelMb = tightMax)
        assertNotNull(result)
        // detail = [-100, 0, 100, 300, 500] — 300 and 500 must clamp to 200
        assertEquals(listOf(-100, 0, 100, 200, 200), result)
    }

    @Test
    fun `levels below minLevelMb are clamped up`() {
        val tightMin = -100
        val result = resolveWavdropBandLevels("spoken-word", bandCount, minLevelMb = tightMin, maxLevelMb = 1500)
        assertNotNull(result)
        // spoken-word = [-400, -200, 200, 500, 100] — -400 and -200 must clamp to -100
        assertEquals(listOf(-100, -100, 200, 500, 100), result)
    }

    @Test
    fun `exact boundary values are preserved without clamping`() {
        // Catalog max is 500 mB; set device max to exactly 500 — no clamping
        val result = resolveWavdropBandLevels("clarity", bandCount, minLevelMb = -500, maxLevelMb = 500)
        assertNotNull(result)
        assertEquals(listOf(-200, -100, 200, 500, 200), result)
    }

    // ── All catalog ids resolve on 5-band device ──────────────────────────────

    @Test
    fun `all catalog presets resolve successfully on 5-band device`() {
        val ids = listOf("balanced", "deep-bass", "clarity", "warm", "detail", "late-night", "spoken-word")
        ids.forEach { id ->
            val result = resolveWavdropBandLevels(id, bandCount, minLevelMb, maxLevelMb)
            assertNotNull("$id should resolve on 5-band device", result)
            assertEquals("$id should have $bandCount levels", bandCount, result!!.size)
        }
    }
}
