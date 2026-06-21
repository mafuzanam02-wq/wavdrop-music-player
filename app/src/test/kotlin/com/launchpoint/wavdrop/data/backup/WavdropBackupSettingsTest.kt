package com.launchpoint.wavdrop.data.backup

import com.launchpoint.wavdrop.data.settings.HomeLayoutSettingsRules
import com.launchpoint.wavdrop.data.settings.HomeSectionId
import com.launchpoint.wavdrop.data.settings.AppIconChoice
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 4: complete non-default settings backup + Obsidian Black default icon.
 */
class WavdropBackupSettingsTest {

    private fun minimalBackup(preferences: BackupPreferences?) = WavdropBackup(
        exportedAt      = "2026-06-11T10:00:00Z",
        songs           = emptyList(),
        trackStats      = emptyList(),
        importBaselines = emptyList(),
        preferences     = preferences,
    )

    private fun allNullPrefs() = BackupPreferences(
        startupDestination = null, mostPlayedPeriod = null, mostPlayedLimit = null,
        homeVisibleSections = null, scanMode = null, selectedFolderUris = null,
        minimumTrackDurationSeconds = null,
    )

    private fun androidPreferencesObject(json: String): JSONObject =
        JSONObject(json).getJSONObject("preferences").getJSONObject("android")

    private fun minimalBackupJson(preferencesJson: String? = null): String = buildString {
        append(
            """
            {
              "app": "Wavdrop",
              "format": "wavdrop_backup",
              "version": 1,
              "exportedAt": "2026-06-11T10:00:00Z",
              "songs": [],
              "trackStats": [],
              "importBaselines": []
            """.trimIndent(),
        )
        preferencesJson?.let {
            append(",\n  \"preferences\": ")
            append(it)
        }
        append("\n}")
    }

    // ── Default launcher icon ─────────────────────────────────────────────────

    @Test
    fun `default launcher icon is Obsidian Black`() {
        assertEquals(AppIconChoice.OBSIDIAN_BLACK, AppIconChoice.DEFAULT)
    }

    // ── New settings round-trip ───────────────────────────────────────────────

    @Test
    fun `phase 4 settings survive export and parse`() {
        val prefs = allNullPrefs().copy(
            artworkCornerStyle        = "SQUARE",
            showSongThumbnails        = false,
            showQueueCount            = false,
            nowPlayingTimeDisplayMode = "REMAINING",
            songSortMode              = "MOST_PLAYED_THIS_MONTH",
            searchTapBehavior         = "PRESERVE_QUEUE",
            includeWhatsAppVoiceNotes = true,
            pauseOnAudioDisconnect    = false,
            bluetoothResumeMode       = "ALWAYS_RESUME",
        )
        val parsed = WavdropBackupParser
            .parse(WavdropBackupExporter.toJson(minimalBackup(prefs)))
            .backup
        assertNotNull(parsed)
        assertEquals(prefs, parsed!!.preferences)
    }

    @Test
    fun `non-default song sort is exported and parsed`() {
        val json = WavdropBackupExporter.toJson(
            minimalBackup(allNullPrefs().copy(songSortMode = "MOST_PLAYED_ALL_TIME")),
        )
        val prefsObj = androidPreferencesObject(json)
        val parsed = WavdropBackupParser.parse(json).backup

        assertEquals("MOST_PLAYED_ALL_TIME", prefsObj.getString("songSortMode"))
        assertNotNull(parsed)
        assertEquals("MOST_PLAYED_ALL_TIME", parsed!!.preferences?.songSortMode)
    }

    @Test
    fun `non-default search tap behavior is exported and parsed`() {
        val json = WavdropBackupExporter.toJson(
            minimalBackup(allNullPrefs().copy(searchTapBehavior = "PRESERVE_QUEUE")),
        )
        val prefsObj = androidPreferencesObject(json)
        val parsed = WavdropBackupParser.parse(json).backup

        assertEquals("PRESERVE_QUEUE", prefsObj.getString("searchTapBehavior"))
        assertNotNull(parsed)
        assertEquals("PRESERVE_QUEUE", parsed!!.preferences?.searchTapBehavior)
    }

    @Test
    fun `Android export contains platform-scoped android preferences only`() {
        val json = WavdropBackupExporter.toJson(
            minimalBackup(allNullPrefs().copy(startupDestination = "SONGS")),
        )
        val root = JSONObject(json)
        val platformPrefs = root.getJSONObject("preferences")
        val androidPrefs = platformPrefs.getJSONObject("android")

        assertEquals("SONGS", androidPrefs.getString("startupDestination"))
        assertFalse(platformPrefs.has("desktop"))
        assertFalse(platformPrefs.has("startupDestination"))
        assertFalse(root.has("startupDestination"))
    }

    // ── Default omission ──────────────────────────────────────────────────────

    @Test
    fun `null settings are omitted from the exported JSON`() {
        val json = WavdropBackupExporter.toJson(minimalBackup(allNullPrefs()))
        val platformPrefs = JSONObject(json).getJSONObject("preferences")
        val prefsObj = platformPrefs.getJSONObject("android")
        assertFalse(platformPrefs.has("desktop"))
        assertEquals("All-default preferences should serialise empty", 0, prefsObj.length())
    }

    @Test
    fun `manifest preferenceCount counts only non-default settings`() {
        val prefs = allNullPrefs().copy(
            searchTapBehavior = "PRESERVE_QUEUE",
            showQueueCount = false,
            themeMode = "DARK",
        )
        val parsed = WavdropBackupParser
            .parse(WavdropBackupExporter.toJson(minimalBackup(prefs)))
            .backup!!
        assertEquals(3, parsed.manifest!!.preferenceCount)
    }

    // ── Legacy compatibility ──────────────────────────────────────────────────

    @Test
    fun `legacy backup without phase 4 settings parses with nulls`() {
        // Old 13-setting backup shape: new keys simply absent.
        val prefs = allNullPrefs().copy(themeMode = "DARK", compactMode = true)
        val parsed = WavdropBackupParser
            .parse(WavdropBackupExporter.toJson(minimalBackup(prefs)))
            .backup!!
        val p = parsed.preferences!!
        assertNull(p.artworkCornerStyle)
        assertNull(p.showSongThumbnails)
        assertNull(p.bluetoothResumeMode)
        assertEquals("DARK", p.themeMode)
        assertEquals(true, p.compactMode)
    }

    @Test
    fun `legacy flat Android preferences parse as import-only compatibility`() {
        val parsed = WavdropBackupParser.parse(
            minimalBackupJson(
                """
                {
                  "startupDestination": "SONGS",
                  "searchTapBehavior": "PRESERVE_QUEUE"
                }
                """.trimIndent(),
            ),
        ).backup

        assertNotNull(parsed)
        assertEquals("SONGS", parsed!!.preferences?.startupDestination)
        assertEquals("PRESERVE_QUEUE", parsed.preferences?.searchTapBehavior)
    }

    @Test
    fun `preferences android restores Android settings`() {
        val parsed = WavdropBackupParser.parse(
            minimalBackupJson(
                """
                {
                  "android": {
                    "startupDestination": "SETTINGS",
                    "searchTapBehavior": "PRESERVE_QUEUE",
                    "showQueueCount": false
                  }
                }
                """.trimIndent(),
            ),
        ).backup

        assertNotNull(parsed)
        val prefs = parsed!!.preferences
        assertEquals("SETTINGS", prefs?.startupDestination)
        assertEquals("PRESERVE_QUEUE", prefs?.searchTapBehavior)
        assertEquals(false, prefs?.showQueueCount)
    }

    @Test
    fun `preferences desktop is ignored on Android`() {
        val parsed = WavdropBackupParser.parse(
            minimalBackupJson(
                """
                {
                  "desktop": {
                    "theme": "dark",
                    "sidebarCollapsed": true
                  }
                }
                """.trimIndent(),
            ),
        ).backup

        assertNotNull(parsed)
        assertNull(parsed!!.preferences)
    }

    @Test
    fun `missing preferences is valid`() {
        val parsed = WavdropBackupParser.parse(minimalBackupJson()).backup

        assertNotNull(parsed)
        assertNull(parsed!!.preferences)
    }

    @Test
    fun `missing preferences android is valid`() {
        val parsed = WavdropBackupParser.parse(
            minimalBackupJson(
                """
                {
                  "desktop": {
                    "theme": "dark"
                  }
                }
                """.trimIndent(),
            ),
        ).backup

        assertNotNull(parsed)
        assertNull(parsed!!.preferences)
    }

    @Test
    fun `unknown Android preference keys are ignored`() {
        val parsed = WavdropBackupParser.parse(
            minimalBackupJson(
                """
                {
                  "android": {
                    "startupDestination": "HOME",
                    "desktopSidebarWidth": 320,
                    "madeUpAndroidSetting": "ignored"
                  }
                }
                """.trimIndent(),
            ),
        ).backup

        assertNotNull(parsed)
        assertEquals("HOME", parsed!!.preferences?.startupDestination)
    }

    @Test
    fun `invalid Android preference values are sanitized while parsing`() {
        val parsed = WavdropBackupParser.parse(
            minimalBackupJson(
                """
                {
                  "android": {
                    "startupDestination": false,
                    "compactMode": "yes",
                    "minimumTrackDurationSeconds": 2147483648,
                    "homeVisibleSections": ["CONTINUE_LISTENING", 42]
                  }
                }
                """.trimIndent(),
            ),
        ).backup

        assertNotNull(parsed)
        val prefs = parsed!!.preferences
        assertNull(prefs?.startupDestination)
        assertNull(prefs?.compactMode)
        assertNull(prefs?.minimumTrackDurationSeconds)
        assertEquals(listOf("CONTINUE_LISTENING"), prefs?.homeVisibleSections)
    }

    @Test
    fun `legacy home section ids remain accepted in backup preferences`() {
        val parsed = WavdropBackupParser.parse(
            minimalBackupJson(
                """
                {
                  "android": {
                    "homeVisibleSections": ["FAVORITES", "PLAYLISTS", "RECENTLY_PLAYED", "MOST_PLAYED"]
                  }
                }
                """.trimIndent(),
            ),
        ).backup

        assertNotNull(parsed)
        assertEquals(
            listOf("FAVORITES", "PLAYLISTS", "RECENTLY_PLAYED", "MOST_PLAYED"),
            parsed!!.preferences?.homeVisibleSections,
        )
        val restoredSections = parsed.preferences?.homeVisibleSections
            .orEmpty()
            .mapNotNull { runCatching { HomeSectionId.valueOf(it) }.getOrNull() }
            .toSet()
            .let(HomeLayoutSettingsRules::normalizeVisibleSections)
        assertTrue(HomeSectionId.FAVORITES in restoredSections)
        assertTrue(HomeSectionId.PLAYLISTS in restoredSections)
    }

    // ── Checksum stability across the fingerprint extension ──────────────────

    @Test
    fun `fingerprint is unchanged for backups without phase 4 settings`() {
        // The Phase 4 fields are appended to the canonical string ONLY when present,
        // so a legacy-shaped payload must fingerprint identically whether the new
        // fields are conceptually "absent" or explicitly null.
        val legacyShaped = minimalBackup(allNullPrefs().copy(themeMode = "DARK"))
        val sameWithExplicitNulls = minimalBackup(
            allNullPrefs().copy(themeMode = "DARK", artworkCornerStyle = null, restoreQueue = null),
        )
        assertEquals(
            WavdropBackupIntegrity.payloadFingerprint(legacyShaped),
            WavdropBackupIntegrity.payloadFingerprint(sameWithExplicitNulls),
        )
        // And a checksum written at export time still validates at parse time.
        val parsed = WavdropBackupParser.parse(WavdropBackupExporter.toJson(legacyShaped))
        assertNotNull("Checksum must validate: ${parsed.error}", parsed.backup)
    }

    @Test
    fun `fingerprint changes when a phase 4 setting changes`() {
        val base    = minimalBackup(allNullPrefs())
        val changed = minimalBackup(allNullPrefs().copy(showQueueCount = false))
        assertFalse(
            WavdropBackupIntegrity.payloadFingerprint(base) ==
                WavdropBackupIntegrity.payloadFingerprint(changed),
        )
    }

    // ── Wrapped / milestone settings round-trip ───────────────────────────────

    @Test
    fun `wrapped and milestone settings survive export and parse`() {
        val prefs = allNullPrefs().copy(
            showMilestoneCelebrations    = false,
            wrappedUseArtworkBackgrounds = false,
            wrappedBackgroundIntensity   = "BOLD",
            wrappedFallbackTheme         = "OCEAN",
        )
        val parsed = WavdropBackupParser
            .parse(WavdropBackupExporter.toJson(minimalBackup(prefs)))
            .backup
        assertNotNull(parsed)
        val p = parsed!!.preferences!!
        assertEquals(false, p.showMilestoneCelebrations)
        assertEquals(false, p.wrappedUseArtworkBackgrounds)
        assertEquals("BOLD", p.wrappedBackgroundIntensity)
        assertEquals("OCEAN", p.wrappedFallbackTheme)
    }

    @Test
    fun `old backups without wrapped and milestone settings parse with nulls`() {
        val prefs = allNullPrefs().copy(themeMode = "DARK")
        val parsed = WavdropBackupParser
            .parse(WavdropBackupExporter.toJson(minimalBackup(prefs)))
            .backup!!
        val p = parsed.preferences!!
        assertNull(p.showMilestoneCelebrations)
        assertNull(p.wrappedUseArtworkBackgrounds)
        assertNull(p.wrappedBackgroundIntensity)
        assertNull(p.wrappedFallbackTheme)
    }

    @Test
    fun `wrapped and milestone settings change the fingerprint when present`() {
        val base    = minimalBackup(allNullPrefs())
        val changed = minimalBackup(allNullPrefs().copy(wrappedFallbackTheme = "OBSIDIAN"))
        assertFalse(
            WavdropBackupIntegrity.payloadFingerprint(base) ==
                WavdropBackupIntegrity.payloadFingerprint(changed),
        )
    }

    @Test
    fun `tampering a phase 4 setting in the file fails integrity`() {
        val json = WavdropBackupExporter.toJson(
            minimalBackup(allNullPrefs().copy(nowPlayingTimeDisplayMode = "REMAINING")),
        )
        val tampered = json.replace("\"REMAINING\"", "\"DURATION\"")
        assertTrue(tampered != json)
        val result = WavdropBackupParser.parse(tampered)
        assertNull(result.backup)
        assertEquals(WavdropBackupParser.INTEGRITY_ERROR, result.error)
    }
}
