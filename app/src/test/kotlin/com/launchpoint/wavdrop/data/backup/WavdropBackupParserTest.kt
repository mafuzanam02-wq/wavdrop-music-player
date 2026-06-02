package com.launchpoint.wavdrop.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WavdropBackupParserTest {

    @Test
    fun `valid backup parses successfully`() {
        val result = WavdropBackupParser.parse(validBackupJson())

        assertNull(result.error)
        val backup = assertBackup(result)
        assertEquals("2026-06-01T10:15:30Z", backup.exportedAt)
        assertEquals(1, backup.songs.size)
        assertEquals(1, backup.trackStats.size)
        assertEquals(1, backup.importBaselines.size)
    }

    @Test
    fun `valid backup maps song fields`() {
        val song = assertBackup(WavdropBackupParser.parse(validBackupJson())).songs.single()

        assertEquals(42L, song.id)
        assertEquals("content://media/external/audio/media/42", song.uri)
        assertEquals("Song Title", song.title)
        assertEquals("Artist Name", song.artist)
        assertEquals("Album Name", song.album)
        assertEquals(7L, song.albumId)
        assertEquals(180000L, song.duration)
        assertEquals(1710000000L, song.dateAdded)
        assertEquals(3, song.trackNumber)
        assertEquals(2024, song.year)
    }

    @Test
    fun `valid backup maps stats fields`() {
        val stats = assertBackup(WavdropBackupParser.parse(validBackupJson())).trackStats.single()

        assertEquals(42L, stats.songId)
        assertEquals("content://media/external/audio/media/42", stats.contentUri)
        assertEquals(11, stats.playCount)
        assertEquals(2, stats.skipCount)
        assertEquals(1720000000000L, stats.lastPlayedAt)
        assertEquals(90000L, stats.totalListeningTimeMs)
        assertEquals(true, stats.isFavorite)
    }

    @Test
    fun `valid backup maps import baseline fields`() {
        val baseline = assertBackup(WavdropBackupParser.parse(validBackupJson())).importBaselines.single()

        assertEquals(42L, baseline.songId)
        assertEquals("blackplayer_bpstat", baseline.sourceType)
        assertEquals("song title|artist name|album name", baseline.sourceKey)
        assertEquals(510, baseline.lastImportedPlayCount)
        assertEquals(8, baseline.lastImportedSkipCount)
        assertEquals(1730000000000L, baseline.lastImportedAt)
    }

    @Test
    fun `empty arrays parse successfully`() {
        val result = WavdropBackupParser.parse(
            """
            {
              "app": "Wavdrop",
              "format": "wavdrop_backup",
              "version": 1,
              "exportedAt": "2026-06-01T10:15:30Z",
              "songs": [],
              "trackStats": [],
              "importBaselines": []
            }
            """.trimIndent()
        )

        val backup = assertBackup(result)
        assertEquals(0, backup.songs.size)
        assertEquals(0, backup.trackStats.size)
        assertEquals(0, backup.importBaselines.size)
    }

    @Test
    fun `invalid json fails`() {
        assertError("{ nope", "Malformed JSON")
    }

    @Test
    fun `blank content fails`() {
        assertError("   \n\t  ", "The selected file is empty.")
    }

    @Test
    fun `wrong format fails`() {
        assertError(
            validBackupJson().replace("\"format\": \"wavdrop_backup\"", "\"format\": \"other\""),
            "Invalid backup format",
        )
    }

    @Test
    fun `version 2 fails`() {
        assertError(
            validBackupJson().replace("\"version\": 1", "\"version\": 2"),
            "Unsupported backup version: 2",
        )
    }

    @Test
    fun `missing app field fails`() {
        assertError(minimalJsonWithout("app"), "Missing field: app")
    }

    @Test
    fun `missing songs field fails`() {
        assertError(minimalJsonWithout("songs"), "Missing field: songs")
    }

    @Test
    fun `missing trackStats field fails`() {
        assertError(minimalJsonWithout("trackStats"), "Missing field: trackStats")
    }

    @Test
    fun `missing importBaselines field fails`() {
        assertError(minimalJsonWithout("importBaselines"), "Missing field: importBaselines")
    }

    @Test
    fun `songs must be an array`() {
        assertError(
            """
            {
              "app": "Wavdrop",
              "format": "wavdrop_backup",
              "version": 1,
              "songs": {},
              "trackStats": [],
              "importBaselines": []
            }
            """.trimIndent(),
            "Field songs must be an array",
        )
    }

    @Test
    fun `song items must be objects`() {
        assertError(
            """
            {
              "app": "Wavdrop",
              "format": "wavdrop_backup",
              "version": 1,
              "songs": [7],
              "trackStats": [],
              "importBaselines": []
            }
            """.trimIndent(),
            "Field songs[0] must be an object",
        )
    }

    @Test
    fun `missing song field fails`() {
        assertError(
            validBackupJson().replace("\"uri\": \"content://media/external/audio/media/42\",", ""),
            "Missing field: songs[0].uri",
        )
    }

    @Test
    fun `invalid stats boolean fails`() {
        assertError(
            validBackupJson().replace("\"isFavorite\": true", "\"isFavorite\": \"yes\""),
            "Field trackStats[0].isFavorite must be a boolean",
        )
    }

    private fun assertBackup(result: WavdropBackupImportResult): WavdropBackup {
        assertNull(result.error)
        assertNotNull(result.backup)
        return result.backup ?: error("Expected parsed backup")
    }

    private fun assertError(json: String, message: String) {
        val result = WavdropBackupParser.parse(json)

        assertNull(result.backup)
        assertEquals(message, result.error)
    }

    private fun minimalJsonWithout(field: String): String {
        val fields = linkedMapOf(
            "app" to "\"Wavdrop\"",
            "format" to "\"wavdrop_backup\"",
            "version" to "1",
            "songs" to "[]",
            "trackStats" to "[]",
            "importBaselines" to "[]",
        ).filterKeys { it != field }

        return fields.entries.joinToString(
            prefix = "{",
            postfix = "}",
            separator = ",",
        ) { (name, value) -> "\"$name\": $value" }
    }

    private fun validBackupJson(): String = """
        {
          "app": "Wavdrop",
          "format": "wavdrop_backup",
          "version": 1,
          "exportedAt": "2026-06-01T10:15:30Z",
          "packageName": "com.launchpoint.wavdrop",
          "database": "wavdrop.db",
          "songs": [
            ${songObjectJson()}
          ],
          "trackStats": [
            {
              "songId": 42,
              "contentUri": "content://media/external/audio/media/42",
              "playCount": 11,
              "skipCount": 2,
              "lastPlayedAt": 1720000000000,
              "totalListeningTimeMs": 90000,
              "isFavorite": true
            }
          ],
          "importBaselines": [
            {
              "songId": 42,
              "sourceType": "blackplayer_bpstat",
              "sourceKey": "song title|artist name|album name",
              "lastImportedPlayCount": 510,
              "lastImportedSkipCount": 8,
              "lastImportedAt": 1730000000000
            }
          ]
        }
        """.trimIndent()

    private fun songObjectJson(): String = """
        {
          "id": 42,
          "uri": "content://media/external/audio/media/42",
          "title": "Song Title",
          "artist": "Artist Name",
          "album": "Album Name",
          "albumId": 7,
          "duration": 180000,
          "dateAdded": 1710000000,
          "trackNumber": 3,
          "year": 2024
        }
        """.trimIndent()
}
