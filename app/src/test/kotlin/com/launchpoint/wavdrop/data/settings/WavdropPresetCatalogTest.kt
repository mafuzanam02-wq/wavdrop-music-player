package com.launchpoint.wavdrop.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WavdropPresetCatalogTest {

    // ── Catalog roster ────────────────────────────────────────────────────────

    @Test
    fun `catalog has 7 presets`() {
        assertEquals(7, WavdropPresetCatalog.all.size)
    }

    @Test
    fun `all preset ids are unique`() {
        val ids = WavdropPresetCatalog.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `all preset ids are non-empty`() {
        WavdropPresetCatalog.all.forEach { preset ->
            assertTrue("id empty for ${preset.displayName}", preset.id.isNotEmpty())
        }
    }

    @Test
    fun `catalog order matches approved order`() {
        val ids = WavdropPresetCatalog.all.map { it.id }
        assertEquals(
            listOf("balanced", "deep-bass", "clarity", "warm", "detail", "late-night", "spoken-word"),
            ids,
        )
    }

    @Test
    fun `Clarity exists and Vocals does not`() {
        val ids = WavdropPresetCatalog.all.map { it.id }
        assertTrue(ids.contains("clarity"))
        assertFalse(ids.contains("vocals"))
    }

    // ── Band level integrity ──────────────────────────────────────────────────

    @Test
    fun `all presets have exactly 5 band levels`() {
        WavdropPresetCatalog.all.forEach { preset ->
            assertEquals("${preset.id} has wrong band count", 5, preset.bandLevels.size)
        }
    }

    @Test
    fun `all band levels are within minus 1500 to 1500 mB`() {
        WavdropPresetCatalog.all.forEach { preset ->
            preset.bandLevels.forEachIndexed { i, level ->
                assertTrue(
                    "${preset.id} band[$i]=$level exceeds device range",
                    level in -1500..1500,
                )
            }
        }
    }

    @Test
    fun `no band level exceeds plus or minus 500 mB`() {
        WavdropPresetCatalog.all.forEach { preset ->
            preset.bandLevels.forEachIndexed { i, level ->
                assertTrue(
                    "${preset.id} band[$i]=$level exceeds conservative ±500 mB safety limit",
                    level in -500..500,
                )
            }
        }
    }

    // ── Curve shape invariants ────────────────────────────────────────────────

    @Test
    fun `Deep Bass has positive bass bands`() {
        val preset = WavdropPresetCatalog.findById("deep-bass")
        assertNotNull(preset)
        assertTrue("60 Hz should be boosted", preset!!.bandLevels[0] > 0)
        assertTrue("230 Hz should be boosted", preset.bandLevels[1] > 0)
    }

    @Test
    fun `Spoken Word has negative bass bands`() {
        val preset = WavdropPresetCatalog.findById("spoken-word")
        assertNotNull(preset)
        assertTrue("60 Hz should be cut", preset!!.bandLevels[0] < 0)
        assertTrue("230 Hz should be cut", preset.bandLevels[1] < 0)
    }

    // ── findById ─────────────────────────────────────────────────────────────

    @Test
    fun `findById balanced returns Balanced preset`() {
        val preset = WavdropPresetCatalog.findById("balanced")
        assertNotNull(preset)
        assertEquals("balanced", preset!!.id)
        assertEquals("Balanced", preset.displayName)
    }

    @Test
    fun `findById null returns null`() {
        assertNull(WavdropPresetCatalog.findById(null))
    }

    @Test
    fun `findById unknown id returns null`() {
        assertNull(WavdropPresetCatalog.findById("unknown"))
    }

    @Test
    fun `findById is case-sensitive`() {
        // ids are lowercase-kebab; uppercase variants must not match
        assertNull(WavdropPresetCatalog.findById("Balanced"))
        assertNull(WavdropPresetCatalog.findById("BALANCED"))
        assertNotNull(WavdropPresetCatalog.findById("balanced"))
    }

    @Test
    fun `findById all known ids resolve successfully`() {
        val expectedIds = listOf("balanced", "deep-bass", "clarity", "warm", "detail", "late-night", "spoken-word")
        expectedIds.forEach { id ->
            assertNotNull("findById($id) returned null", WavdropPresetCatalog.findById(id))
        }
    }
}
