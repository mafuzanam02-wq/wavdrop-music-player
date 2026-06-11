package com.launchpoint.wavdrop.data.backup

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

    // ── Default omission ──────────────────────────────────────────────────────

    @Test
    fun `null settings are omitted from the exported JSON`() {
        val json = WavdropBackupExporter.toJson(minimalBackup(allNullPrefs()))
        val prefsObj = JSONObject(json).getJSONObject("preferences")
        assertEquals("All-default preferences should serialise empty", 0, prefsObj.length())
    }

    @Test
    fun `manifest preferenceCount counts only non-default settings`() {
        val prefs = allNullPrefs().copy(showQueueCount = false, themeMode = "DARK")
        val parsed = WavdropBackupParser
            .parse(WavdropBackupExporter.toJson(minimalBackup(prefs)))
            .backup!!
        assertEquals(2, parsed.manifest!!.preferenceCount)
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
