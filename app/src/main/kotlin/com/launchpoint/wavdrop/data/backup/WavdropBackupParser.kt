package com.launchpoint.wavdrop.data.backup

import com.launchpoint.wavdrop.data.local.entity.TrackListenEventEntity

object WavdropBackupParser {
    const val SUPPORTED_FORMAT = "wavdrop_backup"
    const val SUPPORTED_VERSION = 2

    const val INTEGRITY_ERROR = "Backup integrity check failed. The file may be damaged."

    const val NEWER_VERSION_ERROR =
        "This backup was created by a newer version of Wavdrop. Update Wavdrop and try again."

    const val IMPLAUSIBLE_STATS_ERROR =
        "This backup contains statistics values that are out of the supported range. " +
            "The file may be damaged."

    // Capabilities known to this version of the parser. Any required capability
    // not in this set causes the import to be rejected cleanly.
    private val KNOWN_REQUIRED_CAPABILITIES = emptySet<String>()
    private val KNOWN_OPTIONAL_CAPABILITIES = emptySet<String>()

    fun parse(
        content: String,
        nowMs: Long = System.currentTimeMillis(),
    ): WavdropBackupImportResult {
        if (content.isBlank()) {
            return failure("The selected file is empty.")
        }

        val root = try {
            JsonReader(content).parse() as? Map<*, *> ?: return failure("Malformed JSON")
        } catch (_: JsonParseException) {
            return failure("Malformed JSON")
        } catch (e: BackupParseException) {
            return failure(e.message ?: "Malformed JSON")
        }

        val format = root["format"] as? String
        if (format != SUPPORTED_FORMAT) {
            return failure("Invalid backup format")
        }

        if (!root.hasField("version")) {
            return failure("Missing field: version")
        }
        val version = try {
            root.requiredInt("version", "version")
        } catch (_: BackupParseException) {
            return failure("This file does not appear to be a valid Wavdrop backup.")
        }

        return when {
            version == 1 -> parseV1(root, nowMs)
            version == 2 -> parseV2(root, nowMs)
            version > SUPPORTED_VERSION -> failure(NEWER_VERSION_ERROR)
            else -> failure("Unsupported backup version: $version")
        }
    }

    // ── v1 ────────────────────────────────────────────────────────────────────

    private val requiredV1RootFields = listOf(
        "app",
        "format",
        "version",
        "songs",
        "trackStats",
        "importBaselines",
    )

    private fun parseV1(root: Map<*, *>, nowMs: Long): WavdropBackupImportResult {
        requiredV1RootFields.firstOrNull { !root.hasField(it) }?.let { field ->
            return failure("Missing field: $field")
        }

        return try {
            val songs = root.requiredArray("songs").mapObjects("songs") { index, item ->
                BackupSong(
                    id          = item.requiredLong("songs[$index].id", "id"),
                    uri         = item.requiredString("songs[$index].uri", "uri"),
                    title       = item.requiredString("songs[$index].title", "title"),
                    artist      = item.requiredString("songs[$index].artist", "artist"),
                    album       = item.requiredString("songs[$index].album", "album"),
                    albumId     = item.requiredLong("songs[$index].albumId", "albumId"),
                    duration    = item.requiredLong("songs[$index].duration", "duration"),
                    dateAdded   = item.requiredLong("songs[$index].dateAdded", "dateAdded"),
                    trackNumber = item.requiredInt("songs[$index].trackNumber", "trackNumber"),
                    year        = item.requiredInt("songs[$index].year", "year"),
                    folderPath  = item["folderPath"] as? String,
                    folderName  = item["folderName"] as? String,
                )
            }

            val trackStats = root.requiredArray("trackStats").mapObjects("trackStats") { index, item ->
                BackupTrackStats(
                    songId               = item.requiredLong("trackStats[$index].songId", "songId"),
                    contentUri           = item.requiredString("trackStats[$index].contentUri", "contentUri"),
                    playCount            = item.requiredInt("trackStats[$index].playCount", "playCount"),
                    skipCount            = item.requiredInt("trackStats[$index].skipCount", "skipCount"),
                    lastPlayedAt         = item.requiredLong("trackStats[$index].lastPlayedAt", "lastPlayedAt"),
                    totalListeningTimeMs = item.requiredLong("trackStats[$index].totalListeningTimeMs", "totalListeningTimeMs"),
                    isFavorite           = item.requiredBoolean("trackStats[$index].isFavorite", "isFavorite"),
                    // v1 does not carry lastListenedAt — null is the typed absence marker.
                    lastListenedAt       = null,
                )
            }

            val importBaselines = root.requiredArray("importBaselines")
                .mapObjects("importBaselines") { index, item ->
                    BackupImportBaseline(
                        songId                = item.requiredLong("importBaselines[$index].songId", "songId"),
                        sourceType            = item.requiredString("importBaselines[$index].sourceType", "sourceType"),
                        sourceKey             = item.requiredString("importBaselines[$index].sourceKey", "sourceKey"),
                        lastImportedPlayCount = item.requiredInt("importBaselines[$index].lastImportedPlayCount", "lastImportedPlayCount"),
                        lastImportedSkipCount = item.requiredInt("importBaselines[$index].lastImportedSkipCount", "lastImportedSkipCount"),
                        lastImportedAt        = item.requiredLong("importBaselines[$index].lastImportedAt", "lastImportedAt"),
                    )
                }

            val lyricsOverrides = (root["lyricsOverrides"] as? List<*>)
                ?.mapObjects("lyricsOverrides") { index, item ->
                    BackupLyricsOverride(
                        songId     = item.requiredLong("lyricsOverrides[$index].songId", "songId"),
                        contentUri = item.requiredString("lyricsOverrides[$index].contentUri", "contentUri"),
                        lyrics     = item.requiredString("lyricsOverrides[$index].lyrics", "lyrics"),
                        updatedAt  = item.requiredLong("lyricsOverrides[$index].updatedAt", "updatedAt"),
                    )
                } ?: emptyList()

            val preferences = root.parseAndroidPreferences()

            val playlists = (root["playlists"] as? List<*>)
                ?.mapObjects("playlists") { pi, playlist ->
                    BackupPlaylist(
                        id        = playlist.requiredLong("playlists[$pi].id", "id"),
                        name      = playlist.requiredString("playlists[$pi].name", "name"),
                        createdAt = playlist.requiredLong("playlists[$pi].createdAt", "createdAt"),
                        updatedAt = playlist.requiredLong("playlists[$pi].updatedAt", "updatedAt"),
                        songs     = (playlist["songs"] as? List<*>)
                            ?.mapObjects("playlists[$pi].songs") { si, song ->
                                BackupPlaylistSong(
                                    songId     = song.requiredLong("playlists[$pi].songs[$si].songId", "songId"),
                                    contentUri = song.requiredString("playlists[$pi].songs[$si].contentUri", "contentUri"),
                                    position   = song.requiredInt("playlists[$pi].songs[$si].position", "position"),
                                    title      = song.requiredString("playlists[$pi].songs[$si].title", "title"),
                                    artist     = song.requiredString("playlists[$pi].songs[$si].artist", "artist"),
                                    album      = song.requiredString("playlists[$pi].songs[$si].album", "album"),
                                )
                            } ?: emptyList(),
                    )
                } ?: emptyList()

            val listenEvents = (root["listenEvents"] as? List<*>)
                ?.mapObjects("listenEvents") { index, item ->
                    BackupListenEvent(
                        songId     = item.requiredLong("listenEvents[$index].songId", "songId"),
                        contentUri = item.requiredString("listenEvents[$index].contentUri", "contentUri"),
                        title      = item.requiredString("listenEvents[$index].title", "title"),
                        artist     = item.requiredString("listenEvents[$index].artist", "artist"),
                        album      = item.requiredString("listenEvents[$index].album", "album"),
                        eventType  = item.requiredString("listenEvents[$index].eventType", "eventType"),
                        occurredAt = item.requiredLong("listenEvents[$index].occurredAt", "occurredAt"),
                        listenedMs = item.requiredLong("listenEvents[$index].listenedMs", "listenedMs"),
                        durationMs = item.requiredLong("listenEvents[$index].durationMs", "durationMs"),
                        source     = item.requiredString("listenEvents[$index].source", "source"),
                    )
                } ?: emptyList()

            val manifest = (root["manifest"] as? Map<*, *>)?.let { m ->
                BackupManifest(
                    songCount           = m.optCount("songCount"),
                    trackStatsCount     = m.optCount("trackStatsCount"),
                    listenEventCount    = m.optCount("listenEventCount"),
                    importBaselineCount = m.optCount("importBaselineCount"),
                    lyricsOverrideCount = m.optCount("lyricsOverrideCount"),
                    playlistCount       = m.optCount("playlistCount"),
                    preferenceCount     = m.optCount("preferenceCount"),
                )
            }
            val payloadSha256 = root["payloadSha256"] as? String

            val backup = WavdropBackup(
                exportedAt      = root["exportedAt"] as? String ?: "",
                songs           = songs,
                trackStats      = trackStats,
                importBaselines = importBaselines,
                lyricsOverrides = lyricsOverrides,
                preferences     = preferences,
                playlists       = playlists,
                listenEvents    = listenEvents,
                appVersionCode  = (root["appVersionCode"] as? Long)?.toInt(),
                appVersionName  = root["appVersionName"] as? String,
                manifest        = manifest,
                payloadSha256   = payloadSha256,
                sourceVersion   = BackupFormatVersion.V1,
            )

            var integrityStatus = BackupIntegrityStatus.UNVERIFIED_LEGACY
            if (payloadSha256 != null) {
                if (payloadSha256 != WavdropBackupIntegrity.payloadFingerprint(backup)) {
                    return failure(INTEGRITY_ERROR)
                }
                integrityStatus = BackupIntegrityStatus.VERIFIED
            }
            if (manifest != null && !manifest.matchesContentOf(backup)) {
                return failure(INTEGRITY_ERROR)
            }

            // Plausibility runs only after integrity/manifest checks — the sealed
            // values are never mutated, and the fingerprint is unaffected (WD-05).
            if (!backup.trackStats.all { it.isPlausible(nowMs) }) {
                return failure(IMPLAUSIBLE_STATS_ERROR)
            }

            success(backup, integrityStatus)
        } catch (e: BackupParseException) {
            failure(e.message ?: "Invalid backup file")
        }
    }

    // ── v2 ────────────────────────────────────────────────────────────────────

    private fun parseV2(root: Map<*, *>, nowMs: Long): WavdropBackupImportResult {
        return try {
            // ── 1. Root version consistency ───────────────────────────────────
            val formatMajor = try {
                root.requiredInt("formatMajor", "formatMajor")
            } catch (_: BackupParseException) {
                return failure("Missing field: formatMajor")
            }
            if (formatMajor != 2) {
                return failure("Inconsistent backup format: version=2 but formatMajor=$formatMajor")
            }
            val formatMinor = try {
                root.requiredInt("formatMinor", "formatMinor")
            } catch (_: BackupParseException) {
                return failure("Missing field: formatMinor")
            }
            if (formatMinor < 0) {
                return failure("Invalid formatMinor: $formatMinor")
            }

            // ── 2. Capabilities ───────────────────────────────────────────────
            val reqCaps = (root["requiredCapabilities"] as? List<*>)
                ?: return failure("Missing field: requiredCapabilities")
            for (cap in reqCaps) {
                val capStr = cap as? String
                    ?: return failure("requiredCapabilities must contain strings")
                if (capStr !in KNOWN_REQUIRED_CAPABILITIES) {
                    return failure(
                        "This backup requires a feature this version of Wavdrop does not " +
                            "support: $capStr. Update Wavdrop and try again."
                    )
                }
            }
            val optCaps = (root["optionalCapabilities"] as? List<*>)
                ?: return failure("Missing field: optionalCapabilities")
            val unknownOptional = optCaps
                .filterIsInstance<String>()
                .filter { it !in KNOWN_OPTIONAL_CAPABILITIES }

            // ── 3. v2-only root fields ────────────────────────────────────────
            val backupId = root.requiredString("backupId", "backupId")
            val sourceInstallationId = root.requiredString("sourceInstallationId", "sourceInstallationId")
            val exportedAtMs = root.requiredLongStrict("exportedAt", "exportedAt")

            // ── 4. Integrity object (required for v2) ─────────────────────────
            val integrityObj = root["integrity"] as? Map<*, *>
                ?: return failure("Missing required integrity data")
            val integrityV = (integrityObj["v"] as? Long)?.toInt()
            if (integrityV != 2) {
                return failure("Integrity data is invalid or not recognised")
            }
            val integrityFingerprint = integrityObj["fingerprint"] as? String
                ?: return failure("Missing integrity fingerprint")

            // ── 5. Payload sections ───────────────────────────────────────────
            val songs = root.requiredArray("songs").mapObjects("songs") { index, item ->
                BackupSong(
                    id          = item.requiredStringOpaqueId("songs[$index].id", "id"),
                    uri         = item.requiredString("songs[$index].uri", "uri"),
                    title       = item.requiredString("songs[$index].title", "title"),
                    artist      = item.requiredString("songs[$index].artist", "artist"),
                    album       = item.requiredString("songs[$index].album", "album"),
                    albumId     = item.requiredStringOpaqueId("songs[$index].albumId", "albumId"),
                    duration    = item.requiredLongStrict("songs[$index].duration", "duration"),
                    dateAdded   = item.requiredLongStrict("songs[$index].dateAdded", "dateAdded"),
                    trackNumber = item.requiredIntStrict("songs[$index].trackNumber", "trackNumber"),
                    year        = item.requiredIntStrict("songs[$index].year", "year"),
                    folderPath  = item["folderPath"] as? String,
                    folderName  = item["folderName"] as? String,
                )
            }

            val trackStats = root.requiredArray("trackStats").mapObjects("trackStats") { index, item ->
                val lastListenedAt = item.requiredLongStrict("trackStats[$index].lastListenedAt", "lastListenedAt")
                if (lastListenedAt < 0) {
                    throw BackupParseException("Field trackStats[$index].lastListenedAt must be non-negative")
                }
                BackupTrackStats(
                    songId               = item.requiredStringOpaqueId("trackStats[$index].songId", "songId"),
                    contentUri           = item.requiredString("trackStats[$index].contentUri", "contentUri"),
                    playCount            = item.requiredIntStrict("trackStats[$index].playCount", "playCount"),
                    skipCount            = item.requiredIntStrict("trackStats[$index].skipCount", "skipCount"),
                    lastPlayedAt         = item.requiredLongStrict("trackStats[$index].lastPlayedAt", "lastPlayedAt"),
                    totalListeningTimeMs = item.requiredLongStrict("trackStats[$index].totalListeningTimeMs", "totalListeningTimeMs"),
                    isFavorite           = item.requiredBoolean("trackStats[$index].isFavorite", "isFavorite"),
                    lastListenedAt       = lastListenedAt,
                )
            }

            val importBaselines = root.requiredArray("importBaselines")
                .mapObjects("importBaselines") { index, item ->
                    BackupImportBaseline(
                        songId                = item.requiredStringOpaqueId("importBaselines[$index].songId", "songId"),
                        sourceType            = item.requiredString("importBaselines[$index].sourceType", "sourceType"),
                        sourceKey             = item.requiredString("importBaselines[$index].sourceKey", "sourceKey"),
                        lastImportedPlayCount = item.requiredIntStrict("importBaselines[$index].lastImportedPlayCount", "lastImportedPlayCount"),
                        lastImportedSkipCount = item.requiredIntStrict("importBaselines[$index].lastImportedSkipCount", "lastImportedSkipCount"),
                        lastImportedAt        = item.requiredLongStrict("importBaselines[$index].lastImportedAt", "lastImportedAt"),
                    )
                }

            val lyricsOverrides = (root["lyricsOverrides"] as? List<*>)
                ?.mapObjects("lyricsOverrides") { index, item ->
                    BackupLyricsOverride(
                        songId     = item.requiredStringOpaqueId("lyricsOverrides[$index].songId", "songId"),
                        contentUri = item.requiredString("lyricsOverrides[$index].contentUri", "contentUri"),
                        lyrics     = item.requiredString("lyricsOverrides[$index].lyrics", "lyrics"),
                        updatedAt  = item.requiredLongStrict("lyricsOverrides[$index].updatedAt", "updatedAt"),
                    )
                } ?: emptyList()

            val preferences = root.parseAndroidPreferences()

            val playlists = (root["playlists"] as? List<*>)
                ?.mapObjects("playlists") { pi, playlist ->
                    BackupPlaylist(
                        id        = playlist.requiredStringOpaqueId("playlists[$pi].id", "id"),
                        name      = playlist.requiredString("playlists[$pi].name", "name"),
                        createdAt = playlist.requiredLongStrict("playlists[$pi].createdAt", "createdAt"),
                        updatedAt = playlist.requiredLongStrict("playlists[$pi].updatedAt", "updatedAt"),
                        songs     = (playlist["songs"] as? List<*>)
                            ?.mapObjects("playlists[$pi].songs") { si, song ->
                                BackupPlaylistSong(
                                    songId     = song.requiredStringOpaqueId("playlists[$pi].songs[$si].songId", "songId"),
                                    contentUri = song.requiredString("playlists[$pi].songs[$si].contentUri", "contentUri"),
                                    position   = song.requiredIntStrict("playlists[$pi].songs[$si].position", "position"),
                                    title      = song.requiredString("playlists[$pi].songs[$si].title", "title"),
                                    artist     = song.requiredString("playlists[$pi].songs[$si].artist", "artist"),
                                    album      = song.requiredString("playlists[$pi].songs[$si].album", "album"),
                                )
                            } ?: emptyList(),
                    )
                } ?: emptyList()

            val listenEvents = (root["listenEvents"] as? List<*>)
                ?.mapObjects("listenEvents") { index, item ->
                    BackupListenEvent(
                        songId     = item.requiredStringOpaqueId("listenEvents[$index].songId", "songId"),
                        contentUri = item.requiredString("listenEvents[$index].contentUri", "contentUri"),
                        title      = item.requiredString("listenEvents[$index].title", "title"),
                        artist     = item.requiredString("listenEvents[$index].artist", "artist"),
                        album      = item.requiredString("listenEvents[$index].album", "album"),
                        eventType  = item.requiredString("listenEvents[$index].eventType", "eventType"),
                        occurredAt = item.requiredLongStrict("listenEvents[$index].occurredAt", "occurredAt"),
                        listenedMs = item.requiredLongStrict("listenEvents[$index].listenedMs", "listenedMs"),
                        durationMs = item.requiredLongStrict("listenEvents[$index].durationMs", "durationMs"),
                        source     = item.requiredString("listenEvents[$index].source", "source"),
                        // Optional in v2 — null when absent (legacy events). Never fabricated.
                        eventId    = item["eventId"] as? String,
                    )
                } ?: emptyList()

            val manifest = (root["manifest"] as? Map<*, *>)?.let { m ->
                BackupManifest(
                    songCount           = m.optCount("songCount"),
                    trackStatsCount     = m.optCount("trackStatsCount"),
                    listenEventCount    = m.optCount("listenEventCount"),
                    importBaselineCount = m.optCount("importBaselineCount"),
                    lyricsOverrideCount = m.optCount("lyricsOverrideCount"),
                    playlistCount       = m.optCount("playlistCount"),
                    preferenceCount     = m.optCount("preferenceCount"),
                )
            }
            val desktopOverlayRoot = root["desktopOverlay"] as? Map<*, *>

            val sealedBackup = WavdropBackup(
                exportedAt            = "",
                exportedAtMs          = exportedAtMs,
                backupId              = backupId,
                sourceInstallationId  = sourceInstallationId,
                sourceVersion         = BackupFormatVersion.V2,
                songs                 = songs,
                trackStats            = trackStats,
                importBaselines       = importBaselines,
                lyricsOverrides       = lyricsOverrides,
                preferences           = preferences,
                playlists             = playlists,
                listenEvents          = listenEvents,
                manifest              = manifest,
                appVersionCode        = (root["producer"] as? Map<*, *>)
                    ?.let { (it["appVersionCode"] as? Long)?.toInt() },
                appVersionName        = (root["producer"] as? Map<*, *>)
                    ?.let { it["appVersionName"] as? String },
            )

            // ── 6. Integrity verification (required for v2) ───────────────────
            val computedFingerprint = WavdropBackupIntegrityV2.fingerprint(sealedBackup)
            if (integrityFingerprint != computedFingerprint) {
                return failure(INTEGRITY_ERROR)
            }

            // ── 7. Manifest validation (if present) ───────────────────────────
            if (manifest != null && !manifest.matchesContentOf(sealedBackup)) {
                return failure(INTEGRITY_ERROR)
            }

            // ── 8. Portable-stat plausibility (WD-05) ─────────────────────────
            // Runs after the v2 fingerprint is verified, so sealed values are
            // never mutated and the fingerprint formula is untouched. The Android
            // root is sealed: a single implausible row rejects the whole backup,
            // consistent with the existing invalid-backup policy (no partial apply).
            if (!sealedBackup.trackStats.all { it.isPlausible(nowMs) }) {
                return failure(IMPLAUSIBLE_STATS_ERROR)
            }

            val backup = sealedBackup.copy(
                desktopOverlay = desktopOverlayRoot?.parseDesktopOverlay(),
            )

            val warnings = if (unknownOptional.isNotEmpty()) {
                listOf(
                    "This backup includes optional features not supported by this version of " +
                        "Wavdrop. Some data may not be fully restored: ${unknownOptional.joinToString()}."
                )
            } else {
                emptyList()
            }

            success(backup, BackupIntegrityStatus.VERIFIED, warnings)
        } catch (e: BackupParseException) {
            failure(e.message ?: "Invalid backup file")
        }
    }

    // ── Result helpers ────────────────────────────────────────────────────────

    private fun success(
        backup: WavdropBackup,
        integrityStatus: BackupIntegrityStatus,
        warnings: List<String> = emptyList(),
    ) = WavdropBackupImportResult(
        backup          = backup,
        error           = null,
        integrityStatus = integrityStatus,
        warnings        = warnings,
    )

    private fun failure(message: String) = WavdropBackupImportResult(
        backup          = null,
        error           = message,
        integrityStatus = BackupIntegrityStatus.INVALID,
    )
}

// ── Parse exceptions ──────────────────────────────────────────────────────────

private class BackupParseException(message: String) : IllegalArgumentException(message)
private class JsonParseException(message: String) : IllegalArgumentException(message)

// ── Portable-stat plausibility (WD-05) ───────────────────────────────────────

private fun BackupTrackStats.isPlausible(nowMs: Long): Boolean =
    ImportedStatPlausibility.isPlausibleTrackStats(
        playCount = playCount,
        skipCount = skipCount,
        totalListeningTimeMs = totalListeningTimeMs,
        lastPlayedAt = lastPlayedAt,
        lastListenedAt = lastListenedAt,
        nowMs = nowMs,
    )

// ── Map helpers — shared by v1 and v2 ────────────────────────────────────────

private fun Map<*, *>.hasField(name: String): Boolean = containsKey(name) && this[name] != null

private fun Map<*, *>.optCount(name: String): Int = (this[name] as? Long)?.toInt() ?: 0

private fun Map<*, *>.parseAndroidPreferences(): BackupPreferences? {
    val preferences = this["preferences"] as? Map<*, *> ?: return null
    val androidPreferences = preferences["android"] as? Map<*, *>
    if (androidPreferences != null) return androidPreferences.toBackupPreferences()

    val isPlatformScoped = preferences.containsKey("android") || preferences.containsKey("desktop")
    if (isPlatformScoped) return null

    return preferences.toBackupPreferences()
}

private fun Map<*, *>.toBackupPreferences(): BackupPreferences = BackupPreferences(
    startupDestination          = this["startupDestination"] as? String,
    mostPlayedPeriod            = this["mostPlayedPeriod"] as? String,
    mostPlayedLimit             = this["mostPlayedLimit"] as? String,
    songSortMode                = this["songSortMode"] as? String,
    searchTapBehavior           = this["searchTapBehavior"] as? String,
    homeVisibleSections         = (this["homeVisibleSections"] as? List<*>)?.filterIsInstance<String>(),
    scanMode                    = this["scanMode"] as? String,
    selectedFolderUris          = (this["selectedFolderUris"] as? List<*>)?.filterIsInstance<String>(),
    minimumTrackDurationSeconds = (this["minimumTrackDurationSeconds"] as? Long)
        ?.takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }?.toInt(),
    themeMode                   = this["themeMode"] as? String,
    accentColor                 = this["accentColor"] as? String,
    launcherIcon                = this["launcherIcon"] as? String,
    compactMode                 = this["compactMode"] as? Boolean,
    backupFileMode              = this["backupFileMode"] as? String,
    autoBackupInterval          = this["autoBackupInterval"] as? String,
    artworkCornerStyle          = this["artworkCornerStyle"] as? String,
    showSongThumbnails          = this["showSongThumbnails"] as? Boolean,
    showAlbumInSongRows         = this["showAlbumInSongRows"] as? Boolean,
    nowPlayingBackground        = this["nowPlayingBackground"] as? String,
    showQueueCount              = this["showQueueCount"] as? Boolean,
    nowPlayingTimeDisplayMode   = this["nowPlayingTimeDisplayMode"] as? String,
    notificationControls        = this["notificationControls"] as? String,
    includeWhatsAppVoiceNotes   = this["includeWhatsAppVoiceNotes"] as? Boolean,
    pauseOnAudioDisconnect      = this["pauseOnAudioDisconnect"] as? Boolean,
    rememberLastTrack           = this["rememberLastTrack"] as? Boolean,
    rememberPosition            = this["rememberPosition"] as? Boolean,
    restoreQueue                = this["restoreQueue"] as? Boolean,
    bluetoothResumeMode         = this["bluetoothResumeMode"] as? String,
    wiredResumeMode             = this["wiredResumeMode"] as? String,
    showMilestoneCelebrations   = this["showMilestoneCelebrations"] as? Boolean,
    wrappedUseArtworkBackgrounds = this["wrappedUseArtworkBackgrounds"] as? Boolean,
    wrappedBackgroundIntensity  = this["wrappedBackgroundIntensity"] as? String,
    wrappedFallbackTheme        = this["wrappedFallbackTheme"] as? String,
)

private fun Map<*, *>.parseDesktopOverlay(): BackupDesktopOverlay {
    val schemaVersion = requiredIntStrict("desktopOverlay.schemaVersion", "schemaVersion")
    if (schemaVersion != 1) {
        throw BackupParseException("Unsupported desktopOverlay schemaVersion: $schemaVersion")
    }
    val producer = this["producer"] as? Map<*, *>
    val trackStatsArray = (this["trackStats"] as? List<*>) ?: (this["songs"] as? List<*>) ?: emptyList<Any?>()
    val trackStats = trackStatsArray.mapObjects("desktopOverlay.trackStats") { index, item ->
        BackupDesktopOverlayTrackStats(
            desktopTrackId = item.desktopTrackId("desktopOverlay.trackStats[$index]"),
            title = item.optionalString("title"),
            artist = item.optionalString("artist"),
            album = item.optionalString("album"),
            durationMs = item.optionalLongStrict("durationMs").coerceAtLeast(0L),
            playCount = item.optionalIntStrict("playCount").coerceAtLeast(0),
            skipCount = item.optionalIntStrict("skipCount").coerceAtLeast(0),
            totalListeningTimeMs = item.optionalLongStrict("totalListeningTimeMs").coerceAtLeast(0L),
            lastPlayedAt = item.optionalLongStrict("lastPlayedAt").coerceAtLeast(0L),
            lastListenedAt = item.optionalLongStrict("lastListenedAt").coerceAtLeast(0L),
            favorite = item["favorite"] as? Boolean ?: false,
        )
    }
    val listenEvents = ((this["listenEvents"] as? List<*>) ?: emptyList<Any?>())
        .mapObjects("desktopOverlay.listenEvents") { index, item ->
            BackupDesktopOverlayListenEvent(
                eventId = item["eventId"] as? String,
                desktopTrackId = item.desktopTrackIdOrNull(),
                title = item.optionalString("title"),
                artist = item.optionalString("artist"),
                album = item.optionalString("album"),
                durationMs = item.optionalLongStrict("durationMs").coerceAtLeast(0L),
                occurredAt = item.optionalLongStrict("occurredAt"),
                listenedMs = item.optionalLongStrict("listenedMs"),
                eventType = item.optionalString("eventType").ifBlank { TrackListenEventEntity.TYPE_PLAY },
                source = item.optionalString("source").ifBlank {
                    TrackListenEventEntity.SOURCE_DESKTOP_PLAYBACK
                },
            )
        }
    return BackupDesktopOverlay(
        schemaVersion = schemaVersion,
        producerPlatform = producer?.get("platform") as? String,
        trackStats = trackStats,
        listenEvents = listenEvents,
        rawJson = toJsonObject().toString(),
    )
}

private fun Map<*, *>.desktopTrackId(path: String): String =
    desktopTrackIdOrNull()
        ?: throw BackupParseException("Missing field: $path.desktopTrackId")

private fun Map<*, *>.desktopTrackIdOrNull(): String? =
    (this["desktopTrackId"] as? String)
        ?: (this["id"] as? String)
        ?: (this["songId"] as? String)

private fun Map<*, *>.optionalString(name: String): String =
    this[name] as? String ?: ""

private fun Map<*, *>.optionalLongStrict(name: String): Long {
    if (!hasField(name)) return 0L
    return requiredLongStrict(name, name)
}

private fun Map<*, *>.optionalIntStrict(name: String): Int {
    if (!hasField(name)) return 0
    return requiredIntStrict(name, name)
}

private fun Map<*, *>.toJsonObject(): org.json.JSONObject = org.json.JSONObject().apply {
    for ((key, value) in this@toJsonObject) {
        if (key is String) put(key, value.toJsonValue())
    }
}

private fun List<*>.toJsonArray(): org.json.JSONArray = org.json.JSONArray().apply {
    this@toJsonArray.forEach { put(it.toJsonValue()) }
}

private fun Any?.toJsonValue(): Any? = when (this) {
    is Map<*, *> -> this.toJsonObject()
    is List<*> -> this.toJsonArray()
    else -> this
}

private fun Map<*, *>.requiredArray(name: String): List<*> {
    if (!hasField(name)) throw BackupParseException("Missing field: $name")
    return this[name] as? List<*>
        ?: throw BackupParseException("Field $name must be an array")
}

private fun <T> List<*>.mapObjects(
    arrayName: String,
    transform: (index: Int, item: Map<*, *>) -> T,
): List<T> = mapIndexed { index, value ->
    val item = value as? Map<*, *>
        ?: throw BackupParseException("Field $arrayName[$index] must be an object")
    transform(index, item)
}

private fun Map<*, *>.requiredString(path: String, name: String): String {
    if (!hasField(name)) throw BackupParseException("Missing field: $path")
    return this[name] as? String
        ?: throw BackupParseException("Field $path must be a string")
}

// ── v1 lenient number helpers (allow whole-Double coercion) ──────────────────

private fun Map<*, *>.requiredLong(path: String, name: String): Long {
    if (!hasField(name)) throw BackupParseException("Missing field: $path")
    return when (val value = this[name]) {
        is Long   -> value
        is Double -> value.toWholeLongOrNull()
        else      -> null
    } ?: throw BackupParseException("Field $path must be a number")
}

private fun Map<*, *>.requiredInt(path: String, name: String): Int {
    val longValue = requiredLong(path, name)
    if (longValue < Int.MIN_VALUE || longValue > Int.MAX_VALUE) {
        throw BackupParseException("Field $path must be a number")
    }
    return longValue.toInt()
}

// ── v2 strict number helpers (reject Double — no decimal or exponent notation) ─

/** v2: rejects decimals and exponent notation (JSON number returned as Double). */
private fun Map<*, *>.requiredLongStrict(path: String, name: String): Long {
    if (!hasField(name)) throw BackupParseException("Missing field: $path")
    return when (val value = this[name]) {
        is Long   -> value
        is Double -> throw BackupParseException(
            "Field $path must be an integer (decimal or exponent notation is not allowed)"
        )
        else      -> null
    } ?: throw BackupParseException("Field $path must be an integer")
}

private fun Map<*, *>.requiredIntStrict(path: String, name: String): Int {
    val longValue = requiredLongStrict(path, name)
    if (longValue < Int.MIN_VALUE || longValue > Int.MAX_VALUE) {
        throw BackupParseException("Field $path is out of the supported range")
    }
    return longValue.toInt()
}

/**
 * v2: reads a string-typed opaque identifier and converts it to Long.
 *
 * Valid: digits only, no sign, no decimal, no exponent, no leading zero except "0",
 * value must fit in [Long.MIN_VALUE]..[Long.MAX_VALUE].
 */
private fun Map<*, *>.requiredStringOpaqueId(path: String, name: String): Long {
    if (!hasField(name)) throw BackupParseException("Missing field: $path")
    val str = this[name] as? String
        ?: throw BackupParseException("Field $path must be a string identifier")
    if (str.isEmpty() || !str.all { it.isDigit() }) {
        throw BackupParseException("Field $path is not a valid identifier (digits only, no sign)")
    }
    if (str.length > 1 && str[0] == '0') {
        throw BackupParseException("Field $path is not a valid identifier (no leading zeros)")
    }
    return str.toLongOrNull()
        ?: throw BackupParseException("Field $path is out of the supported range")
}

private fun Map<*, *>.requiredBoolean(path: String, name: String): Boolean {
    if (!hasField(name)) throw BackupParseException("Missing field: $path")
    return this[name] as? Boolean
        ?: throw BackupParseException("Field $path must be a boolean")
}

private fun Double.toWholeLongOrNull(): Long? {
    val asLong = toLong()
    return if (isFinite() && this == asLong.toDouble()) asLong else null
}

// ── Custom JSON reader ────────────────────────────────────────────────────────

private class JsonReader(private val input: String) {
    private var index = 0

    fun parse(): Any? {
        skipWhitespace()
        val value = parseValue(0)
        skipWhitespace()
        if (index != input.length) fail("Unexpected trailing content")
        return value
    }

    private fun parseValue(depth: Int): Any? {
        skipWhitespace()
        if (index >= input.length) fail("Unexpected end of input")
        return when (val char = input[index]) {
            '{' -> parseObject(depth)
            '[' -> parseArray(depth)
            '"' -> parseString()
            't' -> parseLiteral("true", true)
            'f' -> parseLiteral("false", false)
            'n' -> parseLiteral("null", null)
            '-' -> parseNumber()
            in '0'..'9' -> parseNumber()
            else -> fail("Unexpected character: $char")
        }
    }

    /**
     * Guards against stack exhaustion from deeply nested structures. Called on
     * entering an object or array; [depth] is the current nesting level before
     * descending. Rejecting here — before recursing — keeps a hand-crafted
     * adversarial backup from overflowing the stack and terminating the process.
     */
    private fun enterNesting(depth: Int): Int {
        if (depth >= MAX_JSON_NESTING_DEPTH) {
            throw BackupParseException("Backup JSON is nested too deeply")
        }
        return depth + 1
    }

    private fun parseObject(depth: Int): Map<String, Any?> {
        val childDepth = enterNesting(depth)
        consume('{')
        skipWhitespace()
        if (tryConsume('}')) return emptyMap()

        val output = linkedMapOf<String, Any?>()
        while (true) {
            skipWhitespace()
            if (peek() != '"') fail("Expected object key")
            val key = parseString()
            if (output.containsKey(key)) {
                throw BackupParseException("Backup file is invalid: duplicate field '$key'.")
            }
            skipWhitespace()
            consume(':')
            output[key] = parseValue(childDepth)
            skipWhitespace()
            when {
                tryConsume(',') -> Unit
                tryConsume('}') -> return output
                else -> fail("Expected ',' or '}'")
            }
        }
    }

    private fun parseArray(depth: Int): List<Any?> {
        val childDepth = enterNesting(depth)
        consume('[')
        skipWhitespace()
        if (tryConsume(']')) return emptyList()

        val output = mutableListOf<Any?>()
        while (true) {
            output += parseValue(childDepth)
            skipWhitespace()
            when {
                tryConsume(',') -> Unit
                tryConsume(']') -> return output
                else -> fail("Expected ',' or ']'")
            }
        }
    }

    private fun parseString(): String {
        consume('"')
        val output = StringBuilder()
        while (index < input.length) {
            when (val char = input[index++]) {
                '"'  -> return output.toString()
                '\\' -> output.append(parseEscape())
                else -> output.append(char)
            }
        }
        fail("Unterminated string")
    }

    private fun parseEscape(): Char {
        if (index >= input.length) fail("Unterminated escape")
        return when (val escaped = input[index++]) {
            '"'  -> '"'
            '\\' -> '\\'
            '/'  -> '/'
            'b'  -> '\b'
            'f'  -> ''
            'n'  -> '\n'
            'r'  -> '\r'
            't'  -> '\t'
            'u'  -> parseUnicodeEscape()
            else -> fail("Invalid escape: $escaped")
        }
    }

    private fun parseUnicodeEscape(): Char {
        if (index + 4 > input.length) fail("Invalid unicode escape")
        val hex = input.substring(index, index + 4)
        index += 4
        return hex.toIntOrNull(16)?.toChar()
            ?: fail("Invalid unicode escape")
    }

    private fun parseNumber(): Number {
        val start = index
        if (peek() == '-') index++
        consumeDigits()
        val hasFraction = if (peek() == '.') {
            index++; consumeDigits(); true
        } else false
        val hasExponent = if (peek() == 'e' || peek() == 'E') {
            index++
            if (peek() == '+' || peek() == '-') index++
            consumeDigits()
            true
        } else false

        val raw = input.substring(start, index)
        return if (hasFraction || hasExponent) {
            raw.toDoubleOrNull() ?: fail("Invalid number")
        } else {
            raw.toLongOrNull() ?: fail("Invalid number")
        }
    }

    private fun consumeDigits() {
        val start = index
        while (peek() in '0'..'9') index++
        if (start == index) fail("Expected digit")
    }

    private fun parseLiteral(literal: String, value: Any?): Any? {
        if (!input.startsWith(literal, index)) fail("Expected $literal")
        index += literal.length
        return value
    }

    private fun skipWhitespace() { while (peek().isWhitespace()) index++ }

    private fun consume(expected: Char) { if (!tryConsume(expected)) fail("Expected '$expected'") }

    private fun tryConsume(expected: Char): Boolean {
        if (peek() != expected) return false
        index++
        return true
    }

    private fun peek(): Char = input.getOrNull(index) ?: ' '

    private fun fail(message: String): Nothing { throw JsonParseException(message) }

    companion object {
        /**
         * Maximum permitted JSON nesting depth. The top-level value sits at
         * depth 1; each nested object or array adds one level. A crafted backup
         * with deeper nesting is rejected deterministically before recursion can
         * exhaust the stack. 128 comfortably exceeds any legitimate Wavdrop
         * backup (the deepest real structure — playlists[].songs[] — is 4).
         */
        const val MAX_JSON_NESTING_DEPTH = 128
    }
}
