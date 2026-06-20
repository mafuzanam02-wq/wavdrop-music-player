package com.launchpoint.wavdrop.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeLayoutSettingsRulesTest {

    @Test
    fun `default settings have all sections visible`() {
        val settings = HomeLayoutSettings()

        assertEquals(HomeSectionId.ALL, settings.visibleSections)
    }

    @Test
    fun `hiding a toggleable section removes it from visible set`() {
        val settings = HomeLayoutSettings()

        val updated = HomeLayoutSettingsRules.withSectionVisible(
            settings, HomeSectionId.RECENTLY_PLAYED, visible = false,
        )

        assertFalse(HomeSectionId.RECENTLY_PLAYED in updated.visibleSections)
        assertFalse(HomeSectionId.MOST_PLAYED in updated.visibleSections)
    }

    @Test
    fun `showing a hidden section adds it back to visible set`() {
        val settings = HomeLayoutSettings(
            visibleSections = setOf(HomeSectionId.LIBRARY_SHORTCUT),
        )

        val updated = HomeLayoutSettingsRules.withSectionVisible(
            settings, HomeSectionId.FAVORITES, visible = true,
        )

        assertTrue(HomeSectionId.FAVORITES in updated.visibleSections)
    }

    @Test
    fun `enabling listening activity enables recent and most played ids`() {
        val settings = HomeLayoutSettings(
            visibleSections = setOf(HomeSectionId.LIBRARY_SHORTCUT),
        )

        val updated = HomeLayoutSettingsRules.withSectionVisible(
            settings,
            HomeSectionId.RECENTLY_PLAYED,
            visible = true,
        )

        assertTrue(HomeSectionId.RECENTLY_PLAYED in updated.visibleSections)
        assertTrue(HomeSectionId.MOST_PLAYED in updated.visibleSections)
    }

    @Test
    fun `library shortcut cannot be hidden`() {
        val settings = HomeLayoutSettings()

        val updated = HomeLayoutSettingsRules.withSectionVisible(
            settings, HomeSectionId.LIBRARY_SHORTCUT, visible = false,
        )

        assertTrue(HomeSectionId.LIBRARY_SHORTCUT in updated.visibleSections)
    }

    @Test
    fun `library shortcut is not toggleable`() {
        assertFalse(HomeLayoutSettingsRules.isToggleable(HomeSectionId.LIBRARY_SHORTCUT))
    }

    @Test
    fun `all exposed sections are toggleable`() {
        HomeLayoutSettingsRules.EXPOSED_SECTION_IDS.forEach { id ->
            assertTrue("$id should be toggleable", HomeLayoutSettingsRules.isToggleable(id))
        }
    }

    @Test
    fun `hiding reserved sections preserves unrelated exposed settings`() {
        var settings = HomeLayoutSettings()

        settings = HomeLayoutSettingsRules.withSectionVisible(settings, HomeSectionId.FAVORITES, false)
        settings = HomeLayoutSettingsRules.withSectionVisible(settings, HomeSectionId.PLAYLISTS, false)

        assertFalse(HomeSectionId.FAVORITES in settings.visibleSections)
        assertFalse(HomeSectionId.PLAYLISTS in settings.visibleSections)
        assertTrue(HomeSectionId.RECENTLY_PLAYED in settings.visibleSections)
        assertTrue(HomeSectionId.MOST_PLAYED in settings.visibleSections)
        assertTrue(HomeSectionId.LIBRARY_SHORTCUT in settings.visibleSections)
    }

    @Test
    fun `always visible sections are always in the result after any toggle`() {
        val settings = HomeLayoutSettings()

        HomeSectionId.ALL.forEach { id ->
            val updated = HomeLayoutSettingsRules.withSectionVisible(settings, id, false)
            HomeLayoutSettingsRules.ALWAYS_VISIBLE_SECTIONS.forEach { alwaysOn ->
                assertTrue("$alwaysOn must remain visible after toggling $id off", alwaysOn in updated.visibleSections)
            }
        }
    }

    @Test
    fun `exposed controls exclude reserved and always visible sections`() {
        assertEquals(
            listOf(
                HomeSectionId.CONTINUE_LISTENING,
                HomeSectionId.RECENTLY_PLAYED,
                HomeSectionId.PLAYLISTS,
                HomeSectionId.SMART_COLLECTIONS,
                HomeSectionId.WRAPPED,
            ),
            HomeLayoutSettingsRules.EXPOSED_SECTION_IDS,
        )
        assertFalse(HomeSectionId.FAVORITES in HomeLayoutSettingsRules.EXPOSED_SECTION_IDS)
        assertFalse(HomeSectionId.LIBRARY_SHORTCUT in HomeLayoutSettingsRules.EXPOSED_SECTION_IDS)
    }

    @Test
    fun `old reserved ids remain parseable and survive normalization`() {
        val parsed = setOf("FAVORITES", "PLAYLISTS", "LIBRARY_SHORTCUT")
            .map { HomeSectionId.valueOf(it) }
            .toSet()

        val normalized = HomeLayoutSettingsRules.normalizeVisibleSections(parsed)

        assertTrue(HomeSectionId.FAVORITES in normalized)
        assertTrue(HomeSectionId.PLAYLISTS in normalized)
        assertTrue(HomeSectionId.LIBRARY_SHORTCUT in normalized)
    }
}
