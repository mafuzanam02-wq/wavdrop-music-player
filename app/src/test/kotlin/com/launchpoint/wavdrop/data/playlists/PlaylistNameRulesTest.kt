package com.launchpoint.wavdrop.data.playlists

import com.launchpoint.wavdrop.data.playlists.PlaylistNameRules
import com.launchpoint.wavdrop.data.playlists.PlaylistValidationResult
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistNameRulesTest {

    // ── normalize ─────────────────────────────────────────────────────────────

    @Test fun `normalize trims leading and trailing whitespace`() {
        assertEquals("My Mix", PlaylistNameRules.normalize("  My Mix  "))
    }

    @Test fun `normalize preserves internal whitespace`() {
        assertEquals("Road Trip 2024", PlaylistNameRules.normalize("Road Trip 2024"))
    }

    @Test fun `normalize on blank returns blank`() {
        assertEquals("", PlaylistNameRules.normalize("   "))
    }

    // ── validate – blank ──────────────────────────────────────────────────────

    @Test fun `validate blank name returns BlankName`() {
        assertEquals(PlaylistValidationResult.BlankName, PlaylistNameRules.validate("", emptyList()))
    }

    @Test fun `validate whitespace-only name returns BlankName`() {
        assertEquals(PlaylistValidationResult.BlankName, PlaylistNameRules.validate("   ", emptyList()))
    }

    // ── validate – duplicate ─────────────────────────────────────────────────

    @Test fun `validate exact duplicate returns DuplicateName`() {
        val result = PlaylistNameRules.validate("Chill Vibes", listOf("Chill Vibes"))
        assertEquals(PlaylistValidationResult.DuplicateName, result)
    }

    @Test fun `validate case-insensitive duplicate returns DuplicateName`() {
        val result = PlaylistNameRules.validate("chill vibes", listOf("Chill Vibes"))
        assertEquals(PlaylistValidationResult.DuplicateName, result)
    }

    @Test fun `validate name with leading spaces matches trimmed duplicate`() {
        val result = PlaylistNameRules.validate("  Chill Vibes  ", listOf("Chill Vibes"))
        assertEquals(PlaylistValidationResult.DuplicateName, result)
    }

    // ── validate – valid ──────────────────────────────────────────────────────

    @Test fun `validate unique name among non-empty list returns Valid`() {
        val result = PlaylistNameRules.validate("New Mix", listOf("Chill Vibes", "Road Trip"))
        assertEquals(PlaylistValidationResult.Valid, result)
    }

    @Test fun `validate unique name against empty list returns Valid`() {
        val result = PlaylistNameRules.validate("First Playlist", emptyList())
        assertEquals(PlaylistValidationResult.Valid, result)
    }

    @Test fun `validate similar but distinct name returns Valid`() {
        val result = PlaylistNameRules.validate("Chill Vibes 2", listOf("Chill Vibes"))
        assertEquals(PlaylistValidationResult.Valid, result)
    }
}
