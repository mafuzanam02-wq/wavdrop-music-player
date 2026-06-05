package com.launchpoint.wavdrop.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WavdropBackupAppearanceTest {

    // ── BackupPreferences model holds appearance fields ────────────────────────

    @Test
    fun `BackupPreferences holds themeMode`() {
        val prefs = minimalPrefs(themeMode = "DARK")
        assertEquals("DARK", prefs.themeMode)
    }

    @Test
    fun `BackupPreferences holds accentColor`() {
        val prefs = minimalPrefs(accentColor = "DEEP_TEAL")
        assertEquals("DEEP_TEAL", prefs.accentColor)
    }

    @Test
    fun `BackupPreferences holds launcherIcon`() {
        val prefs = minimalPrefs(launcherIcon = "CLEAN_PURPLE")
        assertEquals("CLEAN_PURPLE", prefs.launcherIcon)
    }

    @Test
    fun `BackupPreferences holds compactMode true`() {
        val prefs = minimalPrefs(compactMode = true)
        assertEquals(true, prefs.compactMode)
    }

    @Test
    fun `BackupPreferences holds compactMode false`() {
        val prefs = minimalPrefs(compactMode = false)
        assertEquals(false, prefs.compactMode)
    }

    @Test
    fun `BackupPreferences appearance fields default to null`() {
        val prefs = BackupPreferences(
            startupDestination          = null,
            mostPlayedPeriod            = null,
            mostPlayedLimit             = null,
            homeVisibleSections         = null,
            scanMode                    = null,
            selectedFolderUris          = null,
            minimumTrackDurationSeconds = null,
        )
        assertNull(prefs.themeMode)
        assertNull(prefs.accentColor)
        assertNull(prefs.launcherIcon)
        assertNull(prefs.compactMode)
    }

    // ── Parser reads appearance fields from JSON ──────────────────────────────

    @Test
    fun `parser reads themeMode from preferences`() {
        val prefs = parsePrefs(""""themeMode": "LIGHT"""")
        assertEquals("LIGHT", prefs?.themeMode)
    }

    @Test
    fun `parser reads accentColor from preferences`() {
        val prefs = parsePrefs(""""accentColor": "DEEP_TEAL"""")
        assertEquals("DEEP_TEAL", prefs?.accentColor)
    }

    @Test
    fun `parser reads launcherIcon from preferences`() {
        val prefs = parsePrefs(""""launcherIcon": "CLEAN_PURPLE"""")
        assertEquals("CLEAN_PURPLE", prefs?.launcherIcon)
    }

    @Test
    fun `parser reads compactMode true from preferences`() {
        val prefs = parsePrefs(""""compactMode": true""")
        assertEquals(true, prefs?.compactMode)
    }

    @Test
    fun `parser reads compactMode false from preferences`() {
        val prefs = parsePrefs(""""compactMode": false""")
        assertEquals(false, prefs?.compactMode)
    }

    @Test
    fun `parser reads all four appearance fields together`() {
        val prefs = parsePrefs("""
            "themeMode": "DARK",
            "accentColor": "DEEP_TEAL",
            "launcherIcon": "CLEAN_PURPLE",
            "compactMode": true
        """)
        assertNotNull(prefs)
        assertEquals("DARK",         prefs!!.themeMode)
        assertEquals("DEEP_TEAL",    prefs.accentColor)
        assertEquals("CLEAN_PURPLE", prefs.launcherIcon)
        assertEquals(true,           prefs.compactMode)
    }

    // ── Backward compatibility ────────────────────────────────────────────────

    @Test
    fun `parser accepts backup without appearance fields`() {
        val result = WavdropBackupParser.parse(legacyBackupWithoutAppearance())
        assertNull(result.error)
        val prefs = result.backup?.preferences
        assertNotNull(prefs)
        assertNull(prefs!!.themeMode)
        assertNull(prefs.accentColor)
        assertNull(prefs.launcherIcon)
        assertNull(prefs.compactMode)
    }

    @Test
    fun `parser accepts backup without preferences block`() {
        val result = WavdropBackupParser.parse(backupWithNoPreferences())
        assertNull(result.error)
        assertNull(result.backup?.preferences)
    }

    @Test
    fun `parser accepts backup where only themeMode is present`() {
        val prefs = parsePrefs(""""themeMode": "DARK"""")
        assertEquals("DARK", prefs?.themeMode)
        assertNull(prefs?.accentColor)
        assertNull(prefs?.launcherIcon)
        assertNull(prefs?.compactMode)
    }

    @Test
    fun `parser accepts backup where only compactMode is present`() {
        val prefs = parsePrefs(""""compactMode": true""")
        assertNull(prefs?.themeMode)
        assertNull(prefs?.accentColor)
        assertNull(prefs?.launcherIcon)
        assertEquals(true, prefs?.compactMode)
    }

    // ── Unknown values are preserved for caller to discard ────────────────────

    @Test
    fun `parser preserves unknown themeMode string for caller to discard`() {
        val prefs = parsePrefs(""""themeMode": "UNKNOWN_MODE"""")
        // Import repository discards unknown enum values via runCatching; parser just stores the string.
        assertEquals("UNKNOWN_MODE", prefs?.themeMode)
    }

    @Test
    fun `parser preserves unknown accentColor string for caller to discard`() {
        val prefs = parsePrefs(""""accentColor": "NEON_PINK"""")
        assertEquals("NEON_PINK", prefs?.accentColor)
    }

    @Test
    fun `parser preserves unknown launcherIcon string for caller to discard`() {
        val prefs = parsePrefs(""""launcherIcon": "RAINBOW"""")
        assertEquals("RAINBOW", prefs?.launcherIcon)
    }

    // ── Appearance alongside existing preferences fields ──────────────────────

    @Test
    fun `parser reads appearance fields alongside existing preferences`() {
        val result = WavdropBackupParser.parse(
            backupWithFullPreferences(
                themeMode    = "LIGHT",
                accentColor  = "MIDNIGHT_VIOLET",
                launcherIcon = "MIDNIGHT_VIOLET",
                compactMode  = false,
            )
        )
        assertNull(result.error)
        val prefs = result.backup?.preferences
        assertNotNull(prefs)
        assertEquals("SONGS",             prefs!!.startupDestination)
        assertEquals("ALL_AUDIO",         prefs.scanMode)
        assertEquals("LIGHT",             prefs.themeMode)
        assertEquals("MIDNIGHT_VIOLET",   prefs.accentColor)
        assertEquals("MIDNIGHT_VIOLET",   prefs.launcherIcon)
        assertEquals(false,               prefs.compactMode)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun minimalPrefs(
        themeMode: String? = null,
        accentColor: String? = null,
        launcherIcon: String? = null,
        compactMode: Boolean? = null,
    ) = BackupPreferences(
        startupDestination          = null,
        mostPlayedPeriod            = null,
        mostPlayedLimit             = null,
        homeVisibleSections         = null,
        scanMode                    = null,
        selectedFolderUris          = null,
        minimumTrackDurationSeconds = null,
        themeMode                   = themeMode,
        accentColor                 = accentColor,
        launcherIcon                = launcherIcon,
        compactMode                 = compactMode,
    )

    private fun parsePrefs(extraPrefsFields: String): BackupPreferences? {
        val json = """
            {
              "app": "Wavdrop",
              "format": "wavdrop_backup",
              "version": 1,
              "exportedAt": "2026-06-01T10:00:00Z",
              "songs": [],
              "trackStats": [],
              "importBaselines": [],
              "preferences": {
                $extraPrefsFields
              }
            }
        """.trimIndent()
        val result = WavdropBackupParser.parse(json)
        assertNull(result.error)
        return result.backup?.preferences
    }

    private fun legacyBackupWithoutAppearance(): String = """
        {
          "app": "Wavdrop",
          "format": "wavdrop_backup",
          "version": 1,
          "exportedAt": "2025-01-01T00:00:00Z",
          "songs": [],
          "trackStats": [],
          "importBaselines": [],
          "preferences": {
            "startupDestination": "SONGS",
            "scanMode": "ALL_AUDIO"
          }
        }
    """.trimIndent()

    private fun backupWithNoPreferences(): String = """
        {
          "app": "Wavdrop",
          "format": "wavdrop_backup",
          "version": 1,
          "exportedAt": "2025-01-01T00:00:00Z",
          "songs": [],
          "trackStats": [],
          "importBaselines": []
        }
    """.trimIndent()

    private fun backupWithFullPreferences(
        themeMode: String,
        accentColor: String,
        launcherIcon: String,
        compactMode: Boolean,
    ): String = """
        {
          "app": "Wavdrop",
          "format": "wavdrop_backup",
          "version": 1,
          "exportedAt": "2026-06-01T10:00:00Z",
          "songs": [],
          "trackStats": [],
          "importBaselines": [],
          "preferences": {
            "startupDestination": "SONGS",
            "scanMode": "ALL_AUDIO",
            "themeMode": "$themeMode",
            "accentColor": "$accentColor",
            "launcherIcon": "$launcherIcon",
            "compactMode": $compactMode
          }
        }
    """.trimIndent()
}
