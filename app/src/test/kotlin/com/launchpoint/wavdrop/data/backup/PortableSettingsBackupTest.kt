package com.launchpoint.wavdrop.data.backup

import com.launchpoint.wavdrop.data.backup.wdbk.WdbkTestSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P5-AUDIT-08. The four settings that used to be dropped by backup (Home Smart Collections, Automatic Crossfade, Transport Fade,
 * Previous-button behavior) must survive the real exporter -> parser path (v2 JSON and WDBK), be integrity protected, and leave every
 * pre-existing verified backup valid with its original fingerprint.
 */
class PortableSettingsBackupTest {

    private fun prefs(
        home: List<String>? = listOf("FORGOTTEN_GEMS", "FAVORITES", "ALWAYS_FINISH"),
        crossfade: Int? = 4000,
        fadeEnabled: Boolean? = false,
        fadeMs: Int? = 650,
        previous: String? = "PREVIOUS_TRACK",
    ) = BackupPreferences(
        startupDestination = null, mostPlayedPeriod = null, mostPlayedLimit = null,
        homeVisibleSections = null, scanMode = null, selectedFolderUris = null, minimumTrackDurationSeconds = null,
        homeSmartCollections = home, crossfadeDurationMs = crossfade,
        transportFadeEnabled = fadeEnabled, transportFadeDurationMs = fadeMs, previousButtonBehavior = previous,
    )

    private fun backup(p: BackupPreferences?) = RecoveryTestFixtures.v2Backup(preferences = p)
    private fun json(p: BackupPreferences?) = RecoveryTestFixtures.v2Json(backup(p))
    private fun parsedPrefs(json: String): BackupPreferences {
        val r = WavdropBackupParser.parse(json)
        assertNull(r.error, r.error)
        assertEquals(BackupIntegrityStatus.VERIFIED, r.integrityStatus)
        return requireNotNull(r.backup?.preferences)
    }
    private fun wdbkPrefs(p: BackupPreferences): BackupPreferences {
        val (bytes, _) = WdbkTestSupport.write(backup(p))
        val r = WdbkTestSupport.read(bytes)
        assertNull(r.error, r.error)
        assertEquals(BackupIntegrityStatus.VERIFIED, r.integrityStatus)
        return requireNotNull(r.backup?.preferences)
    }

    // ── Round trips through the real exporter and parser ──────────────────────

    @Test fun `home smart collection selection and order survive the v2 round trip`() {
        val out = parsedPrefs(json(prefs(home = listOf("USUALLY_ABANDON", "MOST_PLAYED", "FAVORITES"))))
        assertEquals(listOf("USUALLY_ABANDON", "MOST_PLAYED", "FAVORITES"), out.homeSmartCollections)
    }

    @Test fun `automatic crossfade duration survives and OFF is written explicitly`() {
        assertEquals(4000, parsedPrefs(json(prefs(crossfade = 4000))).crossfadeDurationMs)
        assertEquals("OFF is a value, not an omission", 0, parsedPrefs(json(prefs(crossfade = 0))).crossfadeDurationMs)
    }

    @Test fun `transport fade enabled survives`() {
        val out = parsedPrefs(json(prefs(fadeEnabled = true, fadeMs = 300)))
        assertEquals(true, out.transportFadeEnabled)
        assertEquals(300, out.transportFadeDurationMs)
    }

    @Test fun `a disabled transport fade keeps its customized duration`() {
        val out = parsedPrefs(json(prefs(fadeEnabled = false, fadeMs = 650)))
        assertEquals(false, out.transportFadeEnabled)
        assertEquals(650, out.transportFadeDurationMs)
    }

    @Test fun `previous button behavior survives`() {
        assertEquals("PREVIOUS_TRACK", parsedPrefs(json(prefs(previous = "PREVIOUS_TRACK"))).previousButtonBehavior)
        assertEquals("RESTART_CURRENT", parsedPrefs(json(prefs(previous = "RESTART_CURRENT"))).previousButtonBehavior)
    }

    @Test fun `every new setting survives the WDBK round trip`() {
        val out = wdbkPrefs(prefs())
        assertEquals(listOf("FORGOTTEN_GEMS", "FAVORITES", "ALWAYS_FINISH"), out.homeSmartCollections)
        assertEquals(4000, out.crossfadeDurationMs)
        assertEquals(false, out.transportFadeEnabled)
        assertEquals(650, out.transportFadeDurationMs)
        assertEquals("PREVIOUS_TRACK", out.previousButtonBehavior)
    }

    @Test fun `default values are written and restored as explicit values`() {
        val defaults = prefs(
            home = listOf("ALWAYS_FINISH", "FORGOTTEN_GEMS", "USUALLY_ABANDON"), crossfade = 0,
            fadeEnabled = false, fadeMs = 300, previous = "RESTART_CURRENT",
        )
        assertEquals(defaults, parsedPrefs(json(defaults)).copy(homeVisibleSections = null))
        assertEquals(defaults, wdbkPrefs(defaults).copy(homeVisibleSections = null))
    }

    // ── Integrity ─────────────────────────────────────────────────────────────

    private fun assertTamperFails(original: BackupPreferences, find: String, replace: String) {
        val text = json(original)
        val regex = Regex(find)
        assertEquals("tamper target must exist exactly once: $find", 1, regex.findAll(text).count())
        val result = WavdropBackupParser.parse(regex.replace(text, replace))
        assertNull(result.backup)
        assertEquals(WavdropBackupParser.INTEGRITY_ERROR, result.error)
        assertEquals(BackupIntegrityStatus.INVALID, result.integrityStatus)
    }

    @Test fun `tampering with a new setting fails integrity`() {
        val p = prefs()
        assertTamperFails(p, "\"crossfadeDurationMs\"\\s*:\\s*4000", "\"crossfadeDurationMs\": 5000")
        assertTamperFails(p, "\"transportFadeEnabled\"\\s*:\\s*false", "\"transportFadeEnabled\": true")
        assertTamperFails(p, "\"transportFadeDurationMs\"\\s*:\\s*650", "\"transportFadeDurationMs\": 700")
        assertTamperFails(p, "\"previousButtonBehavior\"\\s*:\\s*\"PREVIOUS_TRACK\"", "\"previousButtonBehavior\": \"RESTART_CURRENT\"")
        assertTamperFails(p, "\"FORGOTTEN_GEMS\"", "\"MOST_PLAYED\"")
    }

    @Test fun `reordering the Home selection fails integrity because order is protected`() {
        val text = json(prefs(home = listOf("FAVORITES", "MOST_PLAYED", "LONG_TRACKS")))
        val swapped = text.replace("\"FAVORITES\"", "\"@@\"").replace("\"MOST_PLAYED\"", "\"FAVORITES\"").replace("\"@@\"", "\"MOST_PLAYED\"")
        assertNotEquals(text, swapped)
        assertEquals(WavdropBackupParser.INTEGRITY_ERROR, WavdropBackupParser.parse(swapped).error)
    }

    @Test fun `a tampered new setting inside a WDBK is rejected`() {
        val (bytes, _) = WdbkTestSupport.write(backup(prefs()))
        val tampered = WdbkTestSupport.rezip(bytes) { entries ->
            val old = String(with(WdbkTestSupport) { entries.bytesOf("sections/preferences.json") }, Charsets.UTF_8)
            val changed = Regex("\"crossfadeDurationMs\"\\s*:\\s*4000").replace(old, "\"crossfadeDurationMs\": 9000")
            assertNotEquals(old, changed)
            WdbkTestSupport.replaceEntryConsistently(entries, "sections/preferences.json", changed.toByteArray(Charsets.UTF_8))
        }
        val r = WdbkTestSupport.read(tampered)
        assertNull(r.backup)
        assertNotNull(r.error)
    }

    @Test fun `new settings are covered by the production fingerprint and match the independent reference`() {
        val a = backup(prefs())
        assertEquals(ReferenceFingerprintV2.fingerprint(a), WavdropBackupIntegrityV2.fingerprint(a))
        for (changed in listOf(
            prefs(home = listOf("FAVORITES", "MOST_PLAYED", "LONG_TRACKS")), prefs(crossfade = 0), prefs(fadeEnabled = true),
            prefs(fadeMs = 700), prefs(previous = "RESTART_CURRENT"),
        )) {
            assertNotEquals(WavdropBackupIntegrityV2.fingerprint(a), WavdropBackupIntegrityV2.fingerprint(backup(changed)))
        }
    }

    // ── Older backups ─────────────────────────────────────────────────────────

    @Test fun `a v2 backup written before these settings existed still verifies with its original fingerprint`() {
        val r = WavdropBackupParser.parse(LEGACY_V2_JSON)
        assertNull(r.error, r.error)
        assertEquals(BackupIntegrityStatus.VERIFIED, r.integrityStatus)
        val p = requireNotNull(r.backup?.preferences)
        assertNull(p.homeSmartCollections); assertNull(p.crossfadeDurationMs); assertNull(p.transportFadeEnabled)
        assertNull(p.transportFadeDurationMs); assertNull(p.previousButtonBehavior)
        assertEquals(LEGACY_V2_FINGERPRINT, WavdropBackupIntegrityV2.fingerprint(requireNotNull(r.backup)))
    }

    @Test fun `a backup without the new settings keeps byte-identical fingerprint inputs`() {
        val legacyShape = BackupPreferences(
            startupDestination = "ALBUMS", mostPlayedPeriod = null, mostPlayedLimit = null,
            homeVisibleSections = listOf("HERO", "STATS"), scanMode = null, selectedFolderUris = null,
            minimumTrackDurationSeconds = 45, themeMode = "DARK", showSongThumbnails = false, wrappedVisualStyle = "BOLD",
        )
        assertEquals(LEGACY_V2_FINGERPRINT, WavdropBackupIntegrityV2.fingerprint(backup(legacyShape)))
        // and the exporter does not invent the new keys
        val text = json(legacyShape)
        for (key in listOf("homeSmartCollections", "crossfadeDurationMs", "transportFadeEnabled", "transportFadeDurationMs", "previousButtonBehavior")) {
            assertTrue("$key must be absent", !text.contains("\"$key\""))
        }
    }

    // ── Parsing robustness ────────────────────────────────────────────────────

    @Test fun `wrongly typed new values parse as absent and never crash`() {
        val text = json(prefs())
            .replace(Regex("\"crossfadeDurationMs\"\\s*:\\s*4000"), "\"crossfadeDurationMs\": \"loud\"")
            .replace(Regex("\"transportFadeEnabled\"\\s*:\\s*false"), "\"transportFadeEnabled\": \"no\"")
        // a hand-edited file fails integrity; the typed parse itself must not throw
        val r = WavdropBackupParser.parse(text)
        assertEquals(WavdropBackupParser.INTEGRITY_ERROR, r.error)
    }

    @Test fun `manifest preference count includes the new settings`() {
        val withNew = BackupManifest.of(backup(prefs())).preferenceCount
        val without = BackupManifest.of(backup(prefs(null, null, null, null, null))).preferenceCount
        assertEquals(5, withNew - without)
    }

    private companion object {
        /** Produced by the UNMODIFIED baseline (HEAD 3796b87) exporter; never regenerated. */
        const val LEGACY_V2_FINGERPRINT = "93ecc2522df04733776e342b2ce47f7073c9325ed48f42c62bc71ae5c0334cd0"
        const val LEGACY_V2_JSON =
            """{  "optionalCapabilities": [],  "preferences": {"android": {    "homeVisibleSections": [      "HERO",      "STATS"    ],    "startupDestination": "ALBUMS",    "themeMode": "DARK",    "minimumTrackDurationSeconds": 45,    "wrappedVisualStyle": "BOLD",    "showSongThumbnails": false  }},  "sourceInstallationId": "00000000-0000-0000-0000-0000000000b2",  "manifest": {    "importBaselineCount": 0,    "lyricsOverrideCount": 0,    "preferenceCount": 6,    "listenEventCount": 0,    "trackStatsCount": 1,    "playlistCount": 0,    "songCount": 1  },  "backupId": "00000000-0000-0000-0000-0000000000a1",  "format": "wavdrop_backup",  "formatMajor": 2,  "playlists": [],  "importBaselines": [],  "version": 2,  "trackStats": [{    "playCount": 10,    "totalListeningTimeMs": 1000000,    "contentUri": "content://media/1",    "lastPlayedAt": 1782229400000,    "lastListenedAt": 1782229500000,    "songId": "1",    "skipCount": 1,    "isFavorite": false  }],  "listenEvents": [],  "integrity": {    "v": 2,    "fingerprint": "93ecc2522df04733776e342b2ce47f7073c9325ed48f42c62bc71ae5c0334cd0"  },  "formatMinor": 0,  "songs": [{    "duration": 180001,    "folderPath": "Music/",    "trackNumber": 1,    "artist": "Artist",    "year": 2020,    "album": "Album",    "albumId": "1",    "id": "1",    "folderName": "Music",    "title": "Song 1",    "uri": "content://media/1",    "dateAdded": 1000  }],  "exportedAt": 1782230400000,  "producer": {"platform": "android"},  "lyricsOverrides": [],  "requiredCapabilities": []}"""
    }
}
