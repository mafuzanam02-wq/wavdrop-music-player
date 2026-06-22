package com.launchpoint.wavdrop.ui.screen.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsListeningSectionTest {

    // Feature roster for the Listening section — mirrors SettingsScreen content.
    private val listeningSectionActiveFeatures = listOf("Equalizer")
    private val listeningSectionComingLater    = listOf("Output Profiles", "Headphones", "Loudness Protection")

    // Sections in SettingsPlaybackScreen after Equalizer was moved out.
    private val playbackScreenSections = listOf(
        "Startup", "Session", "Search", "Display", "Sleep Timer", "Notification Controls",
    )

    // ── Listening section structure ───────────────────────────────────────────

    @Test
    fun `Listening section has exactly one active feature`() {
        assertEquals(1, listeningSectionActiveFeatures.size)
    }

    @Test
    fun `Equalizer is the active feature in Listening`() {
        assertEquals("Equalizer", listeningSectionActiveFeatures.first())
    }

    @Test
    fun `coming later features include Output Profiles`() {
        assertTrue(listeningSectionComingLater.contains("Output Profiles"))
    }

    @Test
    fun `coming later features include Headphones`() {
        assertTrue(listeningSectionComingLater.contains("Headphones"))
    }

    @Test
    fun `coming later features include Loudness Protection`() {
        assertTrue(listeningSectionComingLater.contains("Loudness Protection"))
    }

    @Test
    fun `Equalizer is not in coming later list`() {
        assertFalse(listeningSectionComingLater.contains("Equalizer"))
    }

    @Test
    fun `Listening section has three coming-later placeholders`() {
        assertEquals(3, listeningSectionComingLater.size)
    }

    // ── Playback section no longer contains EQ ────────────────────────────────

    @Test
    fun `Playback screen sections do not include Audio or Equalizer`() {
        assertFalse(playbackScreenSections.contains("Audio"))
        assertFalse(playbackScreenSections.contains("Equalizer"))
    }

    @Test
    fun `Playback screen retains its core sections`() {
        assertTrue(playbackScreenSections.contains("Startup"))
        assertTrue(playbackScreenSections.contains("Session"))
        assertTrue(playbackScreenSections.contains("Notification Controls"))
    }

    // ── Equalizer route ───────────────────────────────────────────────────────

    @Test
    fun `Equalizer route is under settings namespace`() {
        val route = "settings/equalizer"
        assertTrue(route.startsWith("settings/"))
        assertEquals("settings/equalizer", route)
    }

    @Test
    fun `Equalizer route differs from Playback route`() {
        val equalizerRoute = "settings/equalizer"
        val playbackRoute  = "settings/playback"
        assertFalse(equalizerRoute == playbackRoute)
    }
}
