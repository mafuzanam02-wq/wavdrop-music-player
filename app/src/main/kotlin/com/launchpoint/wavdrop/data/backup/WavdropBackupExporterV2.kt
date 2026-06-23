package com.launchpoint.wavdrop.data.backup

import org.json.JSONArray
import org.json.JSONObject

/**
 * Serialises a [WavdropBackup] to Backup format v2 JSON.
 *
 * v2 differences from v1:
 *  - `version: 2`, `formatMajor: 2`, `formatMinor: 0`
 *  - `backupId`, `sourceInstallationId`, `exportedAt` (epoch ms integer) at root
 *  - `producer` block with platform metadata
 *  - `requiredCapabilities` / `optionalCapabilities` arrays
 *  - `integrity` object (required); computed from [WavdropBackupIntegrityV2]
 *  - Opaque IDs (song id, albumId, playlist id, stat songId, …) serialised as JSON strings
 *  - `trackStats` entries include `lastListenedAt` (required integer)
 *  - `payloadSha256` is not written (v1 only)
 *
 * The integrity fingerprint is computed from the model before JSON serialisation and
 * embedded in `integrity.fingerprint`. The fingerprint covers all payload fields but
 * not the integrity object itself.
 */
object WavdropBackupExporterV2 {

    fun toJson(backup: WavdropBackup): String {
        requireNotNull(backup.backupId) { "backupId must be set for v2 export" }
        requireNotNull(backup.sourceInstallationId) { "sourceInstallationId must be set for v2 export" }
        requireNotNull(backup.exportedAtMs) { "exportedAtMs must be set for v2 export" }

        val fingerprint = WavdropBackupIntegrityV2.fingerprint(backup)
        val manifest = BackupManifest.of(backup)

        return JSONObject().apply {
            put("format", "wavdrop_backup")
            put("version", 2)
            put("formatMajor", 2)
            put("formatMinor", 0)
            put("backupId", backup.backupId)
            put("sourceInstallationId", backup.sourceInstallationId)
            put("exportedAt", backup.exportedAtMs)
            put("producer", JSONObject().apply {
                put("platform", "android")
                backup.appVersionCode?.let { put("appVersionCode", it) }
                backup.appVersionName?.let { put("appVersionName", it) }
            })
            put("requiredCapabilities", JSONArray())
            put("optionalCapabilities", JSONArray())
            put("manifest", manifestObject(manifest))
            put("integrity", JSONObject().apply {
                put("v", 2)
                put("fingerprint", fingerprint)
            })
            put("songs", songsArray(backup.songs))
            put("trackStats", trackStatsArray(backup.trackStats))
            put("importBaselines", baselinesArray(backup.importBaselines))
            put("lyricsOverrides", lyricsOverridesArray(backup.lyricsOverrides))
            backup.preferences?.let { put("preferences", platformPreferencesObject(it)) }
            put("playlists", playlistsArray(backup.playlists))
            put("listenEvents", listenEventsArray(backup.listenEvents))
        }.toString(2)
    }

    private fun songsArray(songs: List<BackupSong>): JSONArray = JSONArray().apply {
        songs.forEach { s ->
            put(JSONObject().apply {
                put("id", s.id.toString())         // opaque ID → string in v2
                put("uri", s.uri)
                put("title", s.title)
                put("artist", s.artist)
                put("album", s.album)
                put("albumId", s.albumId.toString()) // opaque ID → string in v2
                put("duration", s.duration)
                put("dateAdded", s.dateAdded)
                put("trackNumber", s.trackNumber)
                put("year", s.year)
                s.folderPath?.let { put("folderPath", it) }
                s.folderName?.let { put("folderName", it) }
            })
        }
    }

    private fun trackStatsArray(stats: List<BackupTrackStats>): JSONArray = JSONArray().apply {
        stats.forEach { s ->
            put(JSONObject().apply {
                put("songId", s.songId.toString())   // opaque ID → string in v2
                put("contentUri", s.contentUri)
                put("playCount", s.playCount)
                put("skipCount", s.skipCount)
                put("lastPlayedAt", s.lastPlayedAt)
                put("lastListenedAt", s.lastListenedAt ?: 0L)  // required field in v2
                put("totalListeningTimeMs", s.totalListeningTimeMs)
                put("isFavorite", s.isFavorite)
            })
        }
    }

    private fun baselinesArray(baselines: List<BackupImportBaseline>): JSONArray = JSONArray().apply {
        baselines.forEach { b ->
            put(JSONObject().apply {
                put("songId", b.songId.toString())   // opaque ID → string in v2
                put("sourceType", b.sourceType)
                put("sourceKey", b.sourceKey)
                put("lastImportedPlayCount", b.lastImportedPlayCount)
                put("lastImportedSkipCount", b.lastImportedSkipCount)
                put("lastImportedAt", b.lastImportedAt)
            })
        }
    }

    private fun lyricsOverridesArray(overrides: List<BackupLyricsOverride>): JSONArray = JSONArray().apply {
        overrides.forEach { o ->
            put(JSONObject().apply {
                put("songId", o.songId.toString())  // opaque ID → string in v2
                put("contentUri", o.contentUri)
                put("lyrics", o.lyrics)
                put("updatedAt", o.updatedAt)
            })
        }
    }

    private fun playlistsArray(playlists: List<BackupPlaylist>): JSONArray = JSONArray().apply {
        playlists.forEach { p ->
            put(JSONObject().apply {
                put("id", p.id.toString())  // opaque ID → string in v2
                put("name", p.name)
                put("createdAt", p.createdAt)
                put("updatedAt", p.updatedAt)
                put("songs", playlistSongsArray(p.songs))
            })
        }
    }

    private fun playlistSongsArray(songs: List<BackupPlaylistSong>): JSONArray = JSONArray().apply {
        songs.forEach { s ->
            put(JSONObject().apply {
                put("songId", s.songId.toString()) // opaque ID → string in v2
                put("contentUri", s.contentUri)
                put("position", s.position)
                put("title", s.title)
                put("artist", s.artist)
                put("album", s.album)
            })
        }
    }

    private fun listenEventsArray(events: List<BackupListenEvent>): JSONArray = JSONArray().apply {
        events.forEach { e ->
            put(JSONObject().apply {
                put("songId", e.songId.toString()) // opaque ID → string in v2
                put("contentUri", e.contentUri)
                put("title", e.title)
                put("artist", e.artist)
                put("album", e.album)
                put("eventType", e.eventType)
                put("occurredAt", e.occurredAt)
                put("listenedMs", e.listenedMs)
                put("durationMs", e.durationMs)
                put("source", e.source)
                e.eventId?.let { put("eventId", it) }  // optional — omitted when null
            })
        }
    }

    private fun manifestObject(manifest: BackupManifest): JSONObject = JSONObject().apply {
        put("songCount", manifest.songCount)
        put("trackStatsCount", manifest.trackStatsCount)
        put("listenEventCount", manifest.listenEventCount)
        put("importBaselineCount", manifest.importBaselineCount)
        put("lyricsOverrideCount", manifest.lyricsOverrideCount)
        put("playlistCount", manifest.playlistCount)
        put("preferenceCount", manifest.preferenceCount)
    }

    private fun platformPreferencesObject(prefs: BackupPreferences): JSONObject = JSONObject().apply {
        put("android", preferencesObject(prefs))
    }

    private fun preferencesObject(prefs: BackupPreferences): JSONObject = JSONObject().apply {
        prefs.startupDestination?.let { put("startupDestination", it) }
        prefs.mostPlayedPeriod?.let { put("mostPlayedPeriod", it) }
        prefs.mostPlayedLimit?.let { put("mostPlayedLimit", it) }
        prefs.songSortMode?.let { put("songSortMode", it) }
        prefs.searchTapBehavior?.let { put("searchTapBehavior", it) }
        prefs.homeVisibleSections?.let { sections ->
            put("homeVisibleSections", JSONArray().apply { sections.forEach { put(it) } })
        }
        prefs.scanMode?.let { put("scanMode", it) }
        prefs.selectedFolderUris?.let { uris ->
            put("selectedFolderUris", JSONArray().apply { uris.forEach { put(it) } })
        }
        prefs.minimumTrackDurationSeconds?.let { put("minimumTrackDurationSeconds", it) }
        prefs.themeMode?.let { put("themeMode", it) }
        prefs.accentColor?.let { put("accentColor", it) }
        prefs.launcherIcon?.let { put("launcherIcon", it) }
        prefs.compactMode?.let { put("compactMode", it) }
        prefs.backupFileMode?.let { put("backupFileMode", it) }
        prefs.autoBackupInterval?.let { put("autoBackupInterval", it) }
        prefs.artworkCornerStyle?.let { put("artworkCornerStyle", it) }
        prefs.showSongThumbnails?.let { put("showSongThumbnails", it) }
        prefs.showAlbumInSongRows?.let { put("showAlbumInSongRows", it) }
        prefs.nowPlayingBackground?.let { put("nowPlayingBackground", it) }
        prefs.showQueueCount?.let { put("showQueueCount", it) }
        prefs.nowPlayingTimeDisplayMode?.let { put("nowPlayingTimeDisplayMode", it) }
        prefs.notificationControls?.let { put("notificationControls", it) }
        prefs.includeWhatsAppVoiceNotes?.let { put("includeWhatsAppVoiceNotes", it) }
        prefs.pauseOnAudioDisconnect?.let { put("pauseOnAudioDisconnect", it) }
        prefs.rememberLastTrack?.let { put("rememberLastTrack", it) }
        prefs.rememberPosition?.let { put("rememberPosition", it) }
        prefs.restoreQueue?.let { put("restoreQueue", it) }
        prefs.bluetoothResumeMode?.let { put("bluetoothResumeMode", it) }
        prefs.wiredResumeMode?.let { put("wiredResumeMode", it) }
        prefs.showMilestoneCelebrations?.let { put("showMilestoneCelebrations", it) }
        prefs.wrappedUseArtworkBackgrounds?.let { put("wrappedUseArtworkBackgrounds", it) }
        prefs.wrappedBackgroundIntensity?.let { put("wrappedBackgroundIntensity", it) }
        prefs.wrappedFallbackTheme?.let { put("wrappedFallbackTheme", it) }
    }
}
