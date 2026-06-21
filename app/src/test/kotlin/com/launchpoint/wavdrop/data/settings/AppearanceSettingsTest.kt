package com.launchpoint.wavdrop.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun `AccentColor has exactly nine entries`() {
        assertEquals(9, AccentColor.entries.size)
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

    @Test
    fun `AccentColor display names match supported accent order`() {
        assertEquals(
            listOf(
                "Midnight Violet",
                "Clean Purple",
                "Deep Teal",
                "Ocean Blue",
                "Emerald Green",
                "Amber Gold",
                "Crimson Red",
                "Rose Pink",
                "Slate Gray",
            ),
            AccentColor.entries.map { it.displayName },
        )
    }

    // ── Cross-enum consistency ────────────────────────────────────────────────

    @Test
    fun `AccentColor display names match original AppIconChoice display names`() {
        // Accent and icon choices share names so users can match icon and in-app color easily
        val accentNames = AccentColor.entries.take(3).map { it.displayName }
        val iconNames   = AppIconChoice.entries.take(3).map { it.displayName }
        assertEquals(accentNames, iconNames)
    }

    @Test
    fun `AppIconChoice has six launcher icon choices`() {
        assertEquals(6, AppIconChoice.entries.size)
    }

    @Test
    fun `AppIconChoice parses known stored names and rejects unknown values`() {
        AppIconChoice.entries.forEach { choice ->
            assertEquals(choice, AppIconChoice.fromStoredName(choice.name))
        }
        assertNull(AppIconChoice.fromStoredName("RAINBOW"))
        assertNull(AppIconChoice.fromStoredName(null))
    }

    @Test
    fun `AppIconChoice aliases are distinct manifest class names`() {
        val aliases = AppIconChoice.entries.map { it.aliasClassName }
        assertEquals(AppIconChoice.entries.size, aliases.distinct().size)
        assertEquals(
            listOf(
                "com.launchpoint.wavdrop.MainActivityAliasMidnightViolet",
                "com.launchpoint.wavdrop.MainActivityAliasCleanPurple",
                "com.launchpoint.wavdrop.MainActivityAliasDeepTeal",
                "com.launchpoint.wavdrop.MainActivityAliasObsidianBlack",
                "com.launchpoint.wavdrop.MainActivityAliasOceanBlue",
                "com.launchpoint.wavdrop.MainActivityAliasSunsetOrange",
            ),
            aliases,
        )
    }

    @Test
    fun `AppIconAliasRules enables selected alias before disabling others`() {
        val selected = AppIconChoice.DEEP_TEAL
        val plan = AppIconAliasRules.switchPlan(selected)

        assertEquals(AppIconAliasStateChange(selected, enabled = true), plan.first())
        assertEquals(AppIconChoice.entries.size, plan.size)
        assertEquals(
            AppIconChoice.entries.filterNot { it == selected }.toSet(),
            plan.drop(1).map { it.choice }.toSet(),
        )
        assertTrue(plan.drop(1).all { !it.enabled })
    }

    @Test
    fun `SongSortMode invalid stored value falls back to A-Z`() {
        assertEquals(SongSortMode.TITLE_ASC, SongSortMode.DEFAULT)
        assertEquals(SongSortMode.TITLE_ASC, SongSortMode.fromStoredNameOrDefault("NOT_A_SORT"))
        assertEquals(SongSortMode.TITLE_ASC, SongSortMode.fromStoredNameOrDefault(null))
    }

    @Test
    fun `SearchTapBehavior invalid stored value falls back to preserve queue`() {
        assertEquals(SearchTapBehavior.PRESERVE_QUEUE, SearchTapBehavior.DEFAULT)
        assertEquals(
            SearchTapBehavior.PRESERVE_QUEUE,
            SearchTapBehavior.fromStoredNameOrDefault("NOT_A_BEHAVIOR"),
        )
        assertEquals(SearchTapBehavior.PRESERVE_QUEUE, SearchTapBehavior.fromStoredNameOrDefault(null))
    }

    // ── WrappedBackgroundIntensity ────────────────────────────────────────────

    @Test
    fun `WrappedBackgroundIntensity has exactly three entries`() {
        assertEquals(3, WrappedBackgroundIntensity.entries.size)
    }

    @Test
    fun `WrappedBackgroundIntensity entries have distinct non-blank display names`() {
        val names = WrappedBackgroundIntensity.entries.map { it.displayName }
        assertEquals(WrappedBackgroundIntensity.entries.size, names.distinct().size)
        names.forEach { assertNotEquals("", it.trim()) }
    }

    @Test
    fun `WrappedBackgroundIntensity entries have non-blank descriptions`() {
        WrappedBackgroundIntensity.entries.forEach { assertNotEquals("", it.description.trim()) }
    }

    @Test
    fun `WrappedBackgroundIntensity valueOf roundtrip for all entries`() {
        WrappedBackgroundIntensity.entries.forEach { intensity ->
            assertEquals(intensity, WrappedBackgroundIntensity.valueOf(intensity.name))
        }
    }

    // ── WrappedFallbackTheme ──────────────────────────────────────────────────

    @Test
    fun `WrappedFallbackTheme has exactly seven entries`() {
        assertEquals(7, WrappedFallbackTheme.entries.size)
    }

    @Test
    fun `WrappedFallbackTheme entries have distinct non-blank display names`() {
        val names = WrappedFallbackTheme.entries.map { it.displayName }
        assertEquals(WrappedFallbackTheme.entries.size, names.distinct().size)
        names.forEach { assertNotEquals("", it.trim()) }
    }

    @Test
    fun `WrappedFallbackTheme entries have non-blank descriptions`() {
        WrappedFallbackTheme.entries.forEach { assertNotEquals("", it.description.trim()) }
    }

    @Test
    fun `WrappedFallbackTheme valueOf roundtrip for all entries`() {
        WrappedFallbackTheme.entries.forEach { theme ->
            assertEquals(theme, WrappedFallbackTheme.valueOf(theme.name))
        }
    }
}
