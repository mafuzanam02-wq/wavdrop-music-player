package com.launchpoint.wavdrop.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AppearanceSettingsTest {

    // ── ThemeMode ─────────────────────────────────────────────────────────────

    @Test
    fun `ThemeMode has exactly three entries`() {
        assertEquals(3, ThemeMode.entries.size)
    }

    @Test
    fun `ThemeMode entries have distinct non-blank display names`() {
        val names = ThemeMode.entries.map { it.displayName }
        assertEquals(ThemeMode.entries.size, names.distinct().size)
        names.forEach { assertNotEquals("", it.trim()) }
    }

    @Test
    fun `ThemeMode valueOf roundtrip for all entries`() {
        ThemeMode.entries.forEach { mode ->
            assertEquals(mode, ThemeMode.valueOf(mode.name))
        }
    }

    @Test
    fun `ThemeMode default entry is SYSTEM`() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.entries.first())
    }

    @Test
    fun `ThemeMode SYSTEM displayName is System`() {
        assertEquals("System", ThemeMode.SYSTEM.displayName)
    }

    @Test
    fun `ThemeMode LIGHT displayName is Light`() {
        assertEquals("Light", ThemeMode.LIGHT.displayName)
    }

    @Test
    fun `ThemeMode DARK displayName is Dark`() {
        assertEquals("Dark", ThemeMode.DARK.displayName)
    }

    // ── AccentColor ───────────────────────────────────────────────────────────

    @Test
    fun `AccentColor has exactly three entries`() {
        assertEquals(3, AccentColor.entries.size)
    }

    @Test
    fun `AccentColor entries have distinct non-blank display names`() {
        val names = AccentColor.entries.map { it.displayName }
        assertEquals(AccentColor.entries.size, names.distinct().size)
        names.forEach { assertNotEquals("", it.trim()) }
    }

    @Test
    fun `AccentColor valueOf roundtrip for all entries`() {
        AccentColor.entries.forEach { color ->
            assertEquals(color, AccentColor.valueOf(color.name))
        }
    }

    @Test
    fun `AccentColor default entry is MIDNIGHT_VIOLET`() {
        assertEquals(AccentColor.MIDNIGHT_VIOLET, AccentColor.entries.first())
    }

    @Test
    fun `AccentColor MIDNIGHT_VIOLET displayName is Midnight Violet`() {
        assertEquals("Midnight Violet", AccentColor.MIDNIGHT_VIOLET.displayName)
    }

    @Test
    fun `AccentColor CLEAN_PURPLE displayName is Clean Purple`() {
        assertEquals("Clean Purple", AccentColor.CLEAN_PURPLE.displayName)
    }

    @Test
    fun `AccentColor DEEP_TEAL displayName is Deep Teal`() {
        assertEquals("Deep Teal", AccentColor.DEEP_TEAL.displayName)
    }

    // ── Cross-enum consistency ────────────────────────────────────────────────

    @Test
    fun `AccentColor display names match AppIconChoice display names`() {
        // Accent and icon choices share names so users can match icon and in-app color easily
        val accentNames = AccentColor.entries.map { it.displayName }
        val iconNames   = AppIconChoice.entries.map { it.displayName }
        assertEquals(accentNames, iconNames)
    }
}
