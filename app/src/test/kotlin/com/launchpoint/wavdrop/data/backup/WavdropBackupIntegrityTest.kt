package com.launchpoint.wavdrop.data.backup

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WavdropBackupIntegrityTest {

    private fun backup() = WavdropBackup(
        exportedAt = "2026-06-11T10:00:00Z",
        songs = listOf(
            BackupSong(
                id = 1L, uri = "content://media/1", title = "Song One", artist = "Artist",
                album = "Album", albumId = 1L, duration = 180_000L, dateAdded = 0L,
                trackNumber = 1, year = 2020,
            ),
        ),
        trackStats = listOf(
            BackupTrackStats(
                songId = 1L, contentUri = "content://media/1", playCount = 10, skipCount = 1,
                lastPlayedAt = 100L, totalListeningTimeMs = 1_000L, isFavorite = false,
            ),
        ),
        importBaselines = emptyList(),
        listenEvents = listOf(
            BackupListenEvent(
                songId = 1L, contentUri = "content://media/1", title = "Song One",
                artist = "Artist", album = "Album", eventType = "PLAY", occurredAt = 100L,
                listenedMs = 30_000L, durationMs = 180_000L, source = "wavdrop_playback",
            ),
        ),
    )

    // ── Manifest ──────────────────────────────────────────────────────────────

    @Test
    fun `manifest counts are written from actual content`() {
        val parsed = WavdropBackupParser.parse(WavdropBackupExporter.toJson(backup())).backup
        assertNotNull(parsed)
        val manifest = parsed!!.manifest
        assertNotNull(manifest)
        assertEquals(1, manifest!!.songCount)
        assertEquals(1, manifest.trackStatsCount)
        assertEquals(1, manifest.listenEventCount)
        assertEquals(0, manifest.importBaselineCount)
        assertEquals(0, manifest.lyricsOverrideCount)
        assertEquals(0, manifest.playlistCount)
    }

    @Test
    fun `manifest mismatch is rejected as integrity failure`() {
        val json = WavdropBackupExporter.toJson(backup())
        val tampered = JSONObject(json).apply {
            getJSONObject("manifest").put("songCount", 99)
        }.toString(2)

        val result = WavdropBackupParser.parse(tampered)
        assertNull(result.backup)
        assertEquals(WavdropBackupParser.INTEGRITY_ERROR, result.error)
    }

    // ── Checksum ──────────────────────────────────────────────────────────────

    @Test
    fun `checksum is written and matches the model fingerprint`() {
        val original = backup()
        val parsed = WavdropBackupParser.parse(WavdropBackupExporter.toJson(original)).backup!!
        assertEquals(WavdropBackupIntegrity.payloadFingerprint(original), parsed.payloadSha256)
    }

    @Test
    fun `tampered payload is rejected as integrity failure`() {
        val json = WavdropBackupExporter.toJson(backup())
        // Corrupt a data field without touching manifest counts or the checksum.
        val tampered = json.replace("\"playCount\": 10", "\"playCount\": 9999")

        assertTrue("Tamper target not found in JSON", tampered != json)
        val result = WavdropBackupParser.parse(tampered)
        assertNull(result.backup)
        assertEquals(WavdropBackupParser.INTEGRITY_ERROR, result.error)
    }

    @Test
    fun `fingerprint changes when any field changes`() {
        val base = backup()
        val changedTitle = base.copy(songs = listOf(base.songs[0].copy(title = "Song One!")))
        val changedEvent = base.copy(
            listenEvents = listOf(base.listenEvents[0].copy(listenedMs = 30_001L)),
        )
        val baseFp = WavdropBackupIntegrity.payloadFingerprint(base)
        assertTrue(baseFp != WavdropBackupIntegrity.payloadFingerprint(changedTitle))
        assertTrue(baseFp != WavdropBackupIntegrity.payloadFingerprint(changedEvent))
    }

    // ── Legacy compatibility ──────────────────────────────────────────────────

    @Test
    fun `legacy backup without manifest or checksum still parses`() {
        // Strip the new metadata to simulate a pre-Phase-2 backup file.
        val legacyJson = JSONObject(WavdropBackupExporter.toJson(backup())).apply {
            remove("manifest")
            remove("payloadSha256")
            remove("appVersionCode")
            remove("appVersionName")
        }.toString(2)

        val result = WavdropBackupParser.parse(legacyJson)
        val parsed = result.backup
        assertNotNull("Legacy backup must remain valid: ${result.error}", parsed)
        assertNull(parsed!!.payloadSha256)
        assertNull(parsed.manifest)
        assertNull(parsed.appVersionCode)
        assertEquals(1, parsed.songs.size)
    }

    @Test
    fun `unsupported future version is rejected with a calm message`() {
        val futureJson = JSONObject(WavdropBackupExporter.toJson(backup()))
            .put("version", 99)
            .toString(2)

        val result = WavdropBackupParser.parse(futureJson)
        assertNull(result.backup)
        assertEquals("Unsupported backup version: 99", result.error)
    }
}
