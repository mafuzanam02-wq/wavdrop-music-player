package com.launchpoint.wavdrop.data.backup

import com.launchpoint.wavdrop.data.local.entity.TrackListenEventEntity

/** Shared fixtures for the Recovery Restore tests. Backups are built the way the real exporter builds them (v2, sealed). */
internal object RecoveryTestFixtures {

    const val NOW = 1_782_230_400_000L

    fun backupSong(id: Long, title: String = "Song $id", artist: String = "Artist", album: String = "Album") = BackupSong(
        id = id, uri = "content://media/$id", title = title, artist = artist, album = album, albumId = 1L,
        duration = 180_000L + id, dateAdded = 1_000L, trackNumber = 1, year = 2020, folderPath = "Music/", folderName = "Music",
    )

    fun backupStats(
        songId: Long, plays: Int, skips: Int, favorite: Boolean,
        lastPlayedAt: Long = NOW - 1_000_000L, lastListenedAt: Long = NOW - 900_000L, listeningMs: Long = plays * 100_000L,
    ) = BackupTrackStats(
        songId = songId, contentUri = "content://media/$songId", playCount = plays, skipCount = skips,
        lastPlayedAt = lastPlayedAt, totalListeningTimeMs = listeningMs, isFavorite = favorite, lastListenedAt = lastListenedAt,
    )

    fun backupEvent(songId: Long, eventId: String?, at: Long, title: String = "Song $songId") = BackupListenEvent(
        songId = songId, contentUri = "content://media/$songId", title = title, artist = "Artist", album = "Album",
        eventType = TrackListenEventEntity.TYPE_PLAY, occurredAt = at, listenedMs = 60_000L, durationMs = 180_000L,
        source = TrackListenEventEntity.SOURCE_WAVDROP_PLAYBACK, eventId = eventId,
    )

    fun backupPlaylistSong(songId: Long, position: Int) = BackupPlaylistSong(
        songId = songId, contentUri = "content://media/$songId", position = position,
        title = "Song $songId", artist = "Artist", album = "Album",
    )

    fun v2Backup(
        songs: List<BackupSong> = listOf(backupSong(1L)),
        stats: List<BackupTrackStats> = listOf(backupStats(1L, 10, 1, false)),
        events: List<BackupListenEvent> = emptyList(),
        playlists: List<BackupPlaylist> = emptyList(),
        lyrics: List<BackupLyricsOverride> = emptyList(),
        baselines: List<BackupImportBaseline> = emptyList(),
        preferences: BackupPreferences? = null,
        overlayRawJson: String? = null,
        backupId: String = "00000000-0000-0000-0000-0000000000a1",
    ) = WavdropBackup(
        exportedAt = "", exportedAtMs = NOW, backupId = backupId,
        sourceInstallationId = "00000000-0000-0000-0000-0000000000b2", sourceVersion = BackupFormatVersion.V2,
        songs = songs, trackStats = stats, importBaselines = baselines, lyricsOverrides = lyrics, preferences = preferences,
        playlists = playlists, listenEvents = events,
        desktopOverlay = overlayRawJson?.let {
            BackupDesktopOverlay(schemaVersion = 1, producerPlatform = null, trackStats = emptyList(), listenEvents = emptyList(), rawJson = it)
        },
    )

    fun v2Json(backup: WavdropBackup = v2Backup()): String = WavdropBackupExporterV2.toJson(backup)

    fun v1Json(): String = WavdropBackupExporter.toJson(
        WavdropBackup(
            exportedAt = "2026-06-11T10:00:00Z",
            songs = listOf(backupSong(1L)),
            trackStats = listOf(backupStats(1L, 10, 1, false).copy(lastListenedAt = null)),
            importBaselines = emptyList(),
        ),
    )

    /** Same JSON with one stat value changed: the payload fingerprint no longer matches. */
    fun tampered(json: String): String = json.replaceFirst("\"playCount\": 10", "\"playCount\": 99")

    fun emptyPrefs() = BackupPreferences(
        startupDestination = null, mostPlayedPeriod = null, mostPlayedLimit = null, homeVisibleSections = null,
        scanMode = null, selectedFolderUris = null, minimumTrackDurationSeconds = null,
    )
}
