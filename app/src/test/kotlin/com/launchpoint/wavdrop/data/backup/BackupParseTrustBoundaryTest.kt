package com.launchpoint.wavdrop.data.backup

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-A: parse trust boundary.
 *
 * Covers:
 *  1. VERIFIED status for a fully-checksummed v1 backup.
 *  2. UNVERIFIED_LEGACY status for a v1 backup with no checksum.
 *  3. Checksum mismatch → INVALID, no usable backup.
 *  4. Manifest mismatch → INVALID, no usable backup.
 *  5. Duplicate root-level key → INVALID, no usable backup.
 *  6. Duplicate nested key (playCount in trackStats) → INVALID, no usable backup.
 *  7. Higher-than-supported version → INVALID with newer-version message.
 *  8. Missing version field → INVALID with plain-language message.
 *  9. A valid v1 export round-trips and carries VERIFIED.
 * 10. UNVERIFIED_LEGACY is the correct signal for a legacy-warning in the preview.
 * 11. VERIFIED carries no legacy warning signal.
 */
class BackupParseTrustBoundaryTest {

    // ── Shared fixtures ───────────────────────────────────────────────────────

    private fun minimalBackup() = WavdropBackup(
        exportedAt      = "2026-06-23T12:00:00Z",
        songs           = listOf(
            BackupSong(
                id = 1L, uri = "content://media/external/audio/media/1",
                title = "Song", artist = "Artist", album = "Album",
                albumId = 10L, duration = 180_000L, dateAdded = 1_700_000_000L,
                trackNumber = 1, year = 2020,
            ),
        ),
        trackStats      = listOf(
            BackupTrackStats(
                songId = 1L, contentUri = "content://media/external/audio/media/1",
                playCount = 5, skipCount = 0, lastPlayedAt = 1_750_000_000L,
                totalListeningTimeMs = 100_000L, isFavorite = false,
            ),
        ),
        importBaselines = emptyList(),
    )

    /** Full export with checksum + manifest — current exporter output. */
    private fun verifiedJson(): String = WavdropBackupExporter.toJson(minimalBackup())

    /** A v1 backup JSON without payloadSha256 or manifest — simulates a legacy backup. */
    private fun legacyJson(): String = """
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

    /** Minimal JSON without specific fields. */
    private fun minimalJsonWithout(vararg omit: String): String {
        val fields = linkedMapOf(
            "app"             to "\"Wavdrop\"",
            "format"          to "\"wavdrop_backup\"",
            "version"         to "1",
            "songs"           to "[]",
            "trackStats"      to "[]",
            "importBaselines" to "[]",
        ).filterKeys { it !in omit }
        return fields.entries.joinToString(prefix = "{", postfix = "}", separator = ",") { (k, v) ->
            "\"$k\":$v"
        }
    }

    // ── 1. VERIFIED status ────────────────────────────────────────────────────

    @Test
    fun `valid v1 with checksum produces VERIFIED status`() {
        val result = WavdropBackupParser.parse(verifiedJson())

        assertNotNull("Expected a parsed backup", result.backup)
        assertNull(result.error)
        assertEquals(BackupIntegrityStatus.VERIFIED, result.integrityStatus)
    }

    // ── 2. UNVERIFIED_LEGACY status ───────────────────────────────────────────

    @Test
    fun `legacy v1 without checksum produces UNVERIFIED_LEGACY status`() {
        val result = WavdropBackupParser.parse(legacyJson())

        assertNotNull("Legacy backup must parse successfully: ${result.error}", result.backup)
        assertNull(result.error)
        assertEquals(BackupIntegrityStatus.UNVERIFIED_LEGACY, result.integrityStatus)
    }

    @Test
    fun `v1 export with checksum stripped parses as UNVERIFIED_LEGACY`() {
        val json = JSONObject(verifiedJson()).apply {
            remove("payloadSha256")
            remove("manifest")
        }.toString(2)

        val result = WavdropBackupParser.parse(json)

        assertNotNull("Stripped backup must parse: ${result.error}", result.backup)
        assertNull(result.error)
        assertEquals(BackupIntegrityStatus.UNVERIFIED_LEGACY, result.integrityStatus)
    }

    // ── 3. Checksum mismatch → INVALID ───────────────────────────────────────

    @Test
    fun `zeroed checksum returns INVALID and no usable backup`() {
        val zeroed = "0".repeat(64)
        val json = JSONObject(verifiedJson()).apply {
            put("payloadSha256", zeroed)
        }.toString(2)

        val result = WavdropBackupParser.parse(json)

        assertNull("Backup must be null on checksum mismatch", result.backup)
        assertEquals(WavdropBackupParser.INTEGRITY_ERROR, result.error)
        assertEquals(BackupIntegrityStatus.INVALID, result.integrityStatus)
    }

    @Test
    fun `field altered without updating checksum returns INVALID`() {
        val json = verifiedJson().replace("\"playCount\": 5", "\"playCount\": 9999")
        assertTrue("Tamper target not found in JSON", json.contains("9999"))

        val result = WavdropBackupParser.parse(json)

        assertNull(result.backup)
        assertEquals(WavdropBackupParser.INTEGRITY_ERROR, result.error)
        assertEquals(BackupIntegrityStatus.INVALID, result.integrityStatus)
    }

    // ── 4. Manifest mismatch → INVALID ───────────────────────────────────────

    @Test
    fun `manifest count mismatch returns INVALID and no usable backup`() {
        val json = JSONObject(verifiedJson()).apply {
            getJSONObject("manifest").put("songCount", 99)
        }.toString(2)

        val result = WavdropBackupParser.parse(json)

        assertNull(result.backup)
        assertEquals(WavdropBackupParser.INTEGRITY_ERROR, result.error)
        assertEquals(BackupIntegrityStatus.INVALID, result.integrityStatus)
    }

    // ── 5. Duplicate root key → INVALID ──────────────────────────────────────

    @Test
    fun `duplicate root key is rejected`() {
        val json = """
            {
              "app": "Wavdrop",
              "app": "Wavdrop",
              "format": "wavdrop_backup",
              "version": 1,
              "exportedAt": "2025-01-01T00:00:00Z",
              "songs": [],
              "trackStats": [],
              "importBaselines": []
            }
        """.trimIndent()

        val result = WavdropBackupParser.parse(json)

        assertNull(result.backup)
        assertNotNull(result.error)
        assertTrue(
            "Error should mention duplicate field 'app', got: ${result.error}",
            result.error!!.contains("duplicate field 'app'"),
        )
        assertEquals(BackupIntegrityStatus.INVALID, result.integrityStatus)
    }

    // ── 6. Duplicate nested key → INVALID ────────────────────────────────────

    @Test
    fun `duplicate playCount in trackStats is rejected`() {
        val json = """
            {
              "app": "Wavdrop",
              "format": "wavdrop_backup",
              "version": 1,
              "exportedAt": "2025-01-01T00:00:00Z",
              "songs": [],
              "trackStats": [
                {
                  "songId": 1,
                  "contentUri": "content://media/1",
                  "playCount": 5,
                  "playCount": 999,
                  "skipCount": 0,
                  "lastPlayedAt": 1000,
                  "totalListeningTimeMs": 1000,
                  "isFavorite": false
                }
              ],
              "importBaselines": []
            }
        """.trimIndent()

        val result = WavdropBackupParser.parse(json)

        assertNull(result.backup)
        assertNotNull(result.error)
        assertTrue(
            "Error should mention duplicate field 'playCount', got: ${result.error}",
            result.error!!.contains("duplicate field 'playCount'"),
        )
        assertEquals(BackupIntegrityStatus.INVALID, result.integrityStatus)
    }

    @Test
    fun `duplicate title key in song object is rejected`() {
        val json = """
            {
              "app": "Wavdrop",
              "format": "wavdrop_backup",
              "version": 1,
              "exportedAt": "2025-01-01T00:00:00Z",
              "songs": [
                {
                  "id": 1,
                  "uri": "content://media/1",
                  "title": "Song",
                  "title": "Song Again",
                  "artist": "Artist",
                  "album": "Album",
                  "albumId": 10,
                  "duration": 180000,
                  "dateAdded": 0,
                  "trackNumber": 1,
                  "year": 2020
                }
              ],
              "trackStats": [],
              "importBaselines": []
            }
        """.trimIndent()

        val result = WavdropBackupParser.parse(json)

        assertNull(result.backup)
        assertNotNull(result.error)
        assertTrue(
            "Error should mention duplicate field 'title', got: ${result.error}",
            result.error!!.contains("duplicate field 'title'"),
        )
        assertEquals(BackupIntegrityStatus.INVALID, result.integrityStatus)
    }

    // ── 7. Higher version → NEWER_VERSION_ERROR ───────────────────────────────

    @Test
    fun `version 3 returns newer-version message`() {
        // v2 is now supported; use v3 to trigger the newer-version message.
        val result = WavdropBackupParser.parse(minimalJsonWithout().replace("\"version\":1", "\"version\":3"))

        assertNull(result.backup)
        assertEquals(WavdropBackupParser.NEWER_VERSION_ERROR, result.error)
        assertEquals(BackupIntegrityStatus.INVALID, result.integrityStatus)
    }

    @Test
    fun `far-future version returns newer-version message`() {
        val json = JSONObject(verifiedJson()).put("version", 99).toString(2)

        val result = WavdropBackupParser.parse(json)

        assertNull(result.backup)
        assertEquals(WavdropBackupParser.NEWER_VERSION_ERROR, result.error)
        assertEquals(BackupIntegrityStatus.INVALID, result.integrityStatus)
    }

    // ── 8. Missing or non-numeric version → plain invalid-backup message ───────

    @Test
    fun `missing version field returns clear missing-field message`() {
        // "version" is in requiredRootFields so its absence is caught before the
        // version-parse block; the result is a clear, distinct required-field error.
        val result = WavdropBackupParser.parse(minimalJsonWithout("version"))

        assertNull(result.backup)
        assertEquals("Missing field: version", result.error)
        assertEquals(BackupIntegrityStatus.INVALID, result.integrityStatus)
    }

    @Test
    fun `non-numeric version returns plain invalid-backup message`() {
        val json = """
            {
              "app": "Wavdrop",
              "format": "wavdrop_backup",
              "version": "latest",
              "songs": [],
              "trackStats": [],
              "importBaselines": []
            }
        """.trimIndent()

        val result = WavdropBackupParser.parse(json)

        assertNull(result.backup)
        assertEquals("This file does not appear to be a valid Wavdrop backup.", result.error)
        assertEquals(BackupIntegrityStatus.INVALID, result.integrityStatus)
    }

    // ── 9. Round-trip integrity ───────────────────────────────────────────────

    @Test
    fun `valid v1 export round-trips with VERIFIED status and correct data`() {
        val original = minimalBackup()
        val result   = WavdropBackupParser.parse(WavdropBackupExporter.toJson(original))

        assertNotNull("Round-trip parse failed: ${result.error}", result.backup)
        assertNull(result.error)
        assertEquals(BackupIntegrityStatus.VERIFIED, result.integrityStatus)
        assertEquals(original.songs, result.backup!!.songs)
        assertEquals(original.trackStats, result.backup.trackStats)
    }

    // ── 10. UNVERIFIED_LEGACY is the signal for showing a legacy warning ───────

    @Test
    fun `UNVERIFIED_LEGACY status signals that a legacy warning should be shown`() {
        val result = WavdropBackupParser.parse(legacyJson())

        assertEquals(BackupIntegrityStatus.UNVERIFIED_LEGACY, result.integrityStatus)
        // The ViewModel maps this status to a non-null legacyWarning string.
        val shouldShowWarning = result.integrityStatus == BackupIntegrityStatus.UNVERIFIED_LEGACY
        assertTrue("Preview should show legacy warning for UNVERIFIED_LEGACY", shouldShowWarning)
    }

    // ── 11. VERIFIED carries no legacy warning signal ──────────────────────────

    @Test
    fun `VERIFIED status signals that no legacy warning should be shown`() {
        val result = WavdropBackupParser.parse(verifiedJson())

        assertEquals(BackupIntegrityStatus.VERIFIED, result.integrityStatus)
        val shouldShowWarning = result.integrityStatus == BackupIntegrityStatus.UNVERIFIED_LEGACY
        assertTrue("Preview must not show legacy warning for VERIFIED", !shouldShowWarning)
    }
}
