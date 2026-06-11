package com.launchpoint.wavdrop.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Exporter → parser round-trip. Fails if the two sides ever drift — a field
 * added to one side but not the other surfaces here, not on a user's restore.
 */
class WavdropBackupRoundTripTest {

    private fun representativeBackup() = WavdropBackup(
        exportedAt     = "2026-06-11T10:00:00Z",
        appVersionCode = 42,
        appVersionName = "0.1.0-test",
        songs = listOf(
            BackupSong(
                id = 1L, uri = "content://media/external/audio/media/1",
                title = "Tïtle with \"quotes\" & émojis 🎵", artist = "Ärtist\nNewline",
                album = "Albüm", albumId = 7L, duration = 183_456L, dateAdded = 1_700_000_000L,
                trackNumber = 3, year = 2021,
                folderPath = "Music/Albums/Tëst", folderName = "Tëst",
            ),
            BackupSong(
                id = 2L, uri = "content://media/external/audio/media/2",
                title = "Plain", artist = "Artist", album = "Album", albumId = 8L,
                duration = 200_000L, dateAdded = 1_700_000_100L, trackNumber = 1, year = 0,
                // Legacy song shape: no folder fields.
            ),
        ),
        trackStats = listOf(
            BackupTrackStats(
                songId = 1L, contentUri = "content://media/external/audio/media/1",
                playCount = 99, skipCount = 4, lastPlayedAt = 1_750_000_000L,
                totalListeningTimeMs = 9_876_543L, isFavorite = true,
            ),
        ),
        importBaselines = listOf(
            BackupImportBaseline(
                songId = 1L, sourceType = "blackplayer_bpstat", sourceKey = "stats-2026.bpstat",
                lastImportedPlayCount = 80, lastImportedSkipCount = 2, lastImportedAt = 1_720_000_000L,
            ),
        ),
        lyricsOverrides = listOf(
            BackupLyricsOverride(
                songId = 1L, contentUri = "content://media/external/audio/media/1",
                lyrics = "Line one\nLine \"two\"\n日本語の歌詞\n", updatedAt = 1_730_000_000L,
            ),
        ),
        preferences = BackupPreferences(
            startupDestination = "SONGS", mostPlayedPeriod = "ALL_TIME", mostPlayedLimit = "TEN",
            homeVisibleSections = listOf("CONTINUE_LISTENING", "LIBRARY_SHORTCUT"),
            scanMode = "ALL_FOLDERS", selectedFolderUris = null, minimumTrackDurationSeconds = 30,
            themeMode = "DARK", accentColor = "MIDNIGHT_VIOLET", launcherIcon = "CLEAN_PURPLE",
            compactMode = false, backupFileMode = "DATED", autoBackupInterval = "WEEKLY",
            artworkCornerStyle = "SQUARE", showSongThumbnails = false, showAlbumInSongRows = true,
            nowPlayingBackground = "SOLID", showQueueCount = false,
            nowPlayingTimeDisplayMode = "REMAINING", notificationControls = "MINIMAL",
            includeWhatsAppVoiceNotes = true, pauseOnAudioDisconnect = false,
            rememberLastTrack = false, rememberPosition = false, restoreQueue = false,
            bluetoothResumeMode = "ALWAYS_RESUME", wiredResumeMode = "NEVER_RESUME",
        ),
        playlists = listOf(
            BackupPlaylist(
                id = 5L, name = "Röad Trip 🚗", createdAt = 1_000L, updatedAt = 2_000L,
                songs = listOf(
                    BackupPlaylistSong(
                        songId = 1L, contentUri = "content://media/external/audio/media/1",
                        position = 0, title = "Tïtle with \"quotes\" & émojis 🎵",
                        artist = "Ärtist\nNewline", album = "Albüm",
                    ),
                ),
            ),
        ),
        listenEvents = listOf(
            BackupListenEvent(
                songId = 1L, contentUri = "content://media/external/audio/media/1",
                title = "Tïtle with \"quotes\" & émojis 🎵", artist = "Ärtist\nNewline",
                album = "Albüm", eventType = "PLAY", occurredAt = 1_750_000_000L,
                listenedMs = 30_000L, durationMs = 183_456L, source = "wavdrop_playback",
            ),
            BackupListenEvent(
                songId = 1L, contentUri = "content://media/external/audio/media/1",
                title = "Tïtle with \"quotes\" & émojis 🎵", artist = "Ärtist\nNewline",
                album = "Albüm", eventType = "SKIP", occurredAt = 1_750_000_500L,
                listenedMs = 0L, durationMs = 183_456L, source = "manual_restore",
            ),
        ),
    )

    @Test
    fun `export then parse preserves every section exactly`() {
        val original = representativeBackup()
        val result = WavdropBackupParser.parse(WavdropBackupExporter.toJson(original))

        val parsed = result.backup
        assertNotNull("Parse failed: ${result.error}", parsed)
        parsed!!

        assertEquals(original.exportedAt, parsed.exportedAt)
        assertEquals(original.songs, parsed.songs)
        assertEquals(original.trackStats, parsed.trackStats)
        assertEquals(original.importBaselines, parsed.importBaselines)
        assertEquals(original.lyricsOverrides, parsed.lyricsOverrides)
        assertEquals(original.preferences, parsed.preferences)
        assertEquals(original.playlists, parsed.playlists)
        assertEquals(original.listenEvents, parsed.listenEvents)
        assertEquals(original.appVersionCode, parsed.appVersionCode)
        assertEquals(original.appVersionName, parsed.appVersionName)
    }

    @Test
    fun `round-trip fingerprints are identical`() {
        val original = representativeBackup()
        val parsed = WavdropBackupParser.parse(WavdropBackupExporter.toJson(original)).backup!!
        assertEquals(
            WavdropBackupIntegrity.payloadFingerprint(original),
            WavdropBackupIntegrity.payloadFingerprint(parsed),
        )
    }

    @Test
    fun `empty optional sections round-trip as empty`() {
        val minimal = WavdropBackup(
            exportedAt      = "2026-06-11T10:00:00Z",
            songs           = emptyList(),
            trackStats      = emptyList(),
            importBaselines = emptyList(),
        )
        val parsed = WavdropBackupParser.parse(WavdropBackupExporter.toJson(minimal)).backup
        assertNotNull(parsed)
        parsed!!
        assertEquals(emptyList<BackupLyricsOverride>(), parsed.lyricsOverrides)
        assertEquals(emptyList<BackupPlaylist>(), parsed.playlists)
        assertEquals(emptyList<BackupListenEvent>(), parsed.listenEvents)
        assertNull(parsed.preferences)
    }
}
