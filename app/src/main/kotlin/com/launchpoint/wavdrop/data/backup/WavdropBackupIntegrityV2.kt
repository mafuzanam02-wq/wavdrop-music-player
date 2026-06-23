package com.launchpoint.wavdrop.data.backup

import java.security.MessageDigest

/**
 * Payload integrity fingerprint for Backup format v2.
 *
 * Separate from [WavdropBackupIntegrity] (v1). Both implementations fingerprint the
 * parsed model — never the raw JSON text — so the check is immune to re-encoding.
 *
 * Coverage (v2 adds to v1):
 *  - "v2.header" record: [backupId], [sourceInstallationId], [exportedAtMs]
 *  - Per-stat: [BackupTrackStats.lastListenedAt] (required in v2)
 *  - All v1 payload sections (songs, baselines, lyrics, playlists, events, prefs)
 *
 * Excluded (same rule as v1):
 *  - The integrity field itself (fingerprint, v)
 *  - manifest counts
 *  - appVersionCode / appVersionName
 *
 * The v2 canonical string is clearly tagged so a v2 fingerprint can never collide
 * with a v1 fingerprint for the same payload data.
 */
object WavdropBackupIntegrityV2 {

    private const val FIELD = ''  // ASCII unit separator
    private const val RECORD = '' // ASCII record separator

    fun fingerprint(backup: WavdropBackup): String {
        val canonical = buildString {
            // Header record unique to v2 — covers all three v2 root identity fields.
            record(
                "v2.header",
                backup.backupId ?: "",
                backup.sourceInstallationId ?: "",
                backup.exportedAtMs?.toString() ?: "",
            )

            // All collections are sorted by stable keys before hashing so the fingerprint
            // is invariant to insertion order (defensive against non-deterministic DB queries
            // and different construction paths in tests).
            for (s in backup.songs.sortedBy { it.id }) {
                record(
                    "song", s.id, s.uri, s.title, s.artist, s.album, s.albumId,
                    s.duration, s.dateAdded, s.trackNumber, s.year,
                    s.folderPath ?: "", s.folderName ?: "",
                )
            }
            for (t in backup.trackStats.sortedBy { it.songId }) {
                record(
                    "stat", t.songId, t.contentUri, t.playCount, t.skipCount,
                    t.lastPlayedAt, t.lastListenedAt?.toString() ?: "",
                    t.totalListeningTimeMs, t.isFavorite,
                )
            }
            for (b in backup.importBaselines.sortedWith(compareBy({ it.songId }, { it.sourceType }, { it.sourceKey }))) {
                record(
                    "baseline", b.songId, b.sourceType, b.sourceKey,
                    b.lastImportedPlayCount, b.lastImportedSkipCount, b.lastImportedAt,
                )
            }
            for (o in backup.lyricsOverrides.sortedBy { it.songId }) {
                record("lyrics", o.songId, o.contentUri, o.lyrics, o.updatedAt)
            }
            for (p in backup.playlists.sortedBy { it.id }) {
                record("playlist", p.id, p.name, p.createdAt, p.updatedAt)
                for (s in p.songs.sortedBy { it.position }) {
                    record("playlistSong", s.songId, s.contentUri, s.position, s.title, s.artist, s.album)
                }
            }
            for (e in backup.listenEvents.sortedWith(compareBy<BackupListenEvent>(
                { it.occurredAt }, { it.songId }, { it.contentUri }, { it.eventType },
                { it.listenedMs }, { it.durationMs }, { it.source }, { it.title }, { it.artist }, { it.album },
                // Final tie-breaker added in P2-B1: when two events collide on every field above,
                // eventId gives a total order so the per-event eventId records are emitted
                // deterministically (preserving insertion-order invariance). For all-null-eventId
                // backups this key is a constant "" and leaves the pre-eventId ordering unchanged.
                { it.eventId ?: "" },
            ))) {
                record(
                    "event", e.songId, e.contentUri, e.title, e.artist, e.album,
                    e.eventType, e.occurredAt, e.listenedMs, e.durationMs, e.source,
                )
                // eventId is integrity-protected only when present. A null eventId emits nothing,
                // so an all-null-eventId backup fingerprints identically to the pre-eventId baseline.
                optionalRecord("eventId", e.eventId)
            }
            backup.preferences?.let { prefs ->
                record(
                    "prefs",
                    prefs.startupDestination ?: "",
                    prefs.mostPlayedPeriod ?: "",
                    prefs.mostPlayedLimit ?: "",
                    prefs.homeVisibleSections?.joinToString(",") ?: "",
                    prefs.scanMode ?: "",
                    prefs.selectedFolderUris?.joinToString(",") ?: "",
                    prefs.minimumTrackDurationSeconds?.toString() ?: "",
                    prefs.themeMode ?: "",
                    prefs.accentColor ?: "",
                    prefs.launcherIcon ?: "",
                    prefs.compactMode?.toString() ?: "",
                    prefs.backupFileMode ?: "",
                    prefs.autoBackupInterval ?: "",
                )
                optionalRecord("prefArtworkCornerStyle", prefs.artworkCornerStyle)
                optionalRecord("prefSongSortMode", prefs.songSortMode)
                optionalRecord("prefSearchTapBehavior", prefs.searchTapBehavior)
                optionalRecord("prefShowSongThumbnails", prefs.showSongThumbnails)
                optionalRecord("prefShowAlbumInSongRows", prefs.showAlbumInSongRows)
                optionalRecord("prefNowPlayingBackground", prefs.nowPlayingBackground)
                optionalRecord("prefShowQueueCount", prefs.showQueueCount)
                optionalRecord("prefNowPlayingTimeDisplayMode", prefs.nowPlayingTimeDisplayMode)
                optionalRecord("prefNotificationControls", prefs.notificationControls)
                optionalRecord("prefIncludeWhatsAppVoiceNotes", prefs.includeWhatsAppVoiceNotes)
                optionalRecord("prefPauseOnAudioDisconnect", prefs.pauseOnAudioDisconnect)
                optionalRecord("prefRememberLastTrack", prefs.rememberLastTrack)
                optionalRecord("prefRememberPosition", prefs.rememberPosition)
                optionalRecord("prefRestoreQueue", prefs.restoreQueue)
                optionalRecord("prefBluetoothResumeMode", prefs.bluetoothResumeMode)
                optionalRecord("prefWiredResumeMode", prefs.wiredResumeMode)
                optionalRecord("prefShowMilestoneCelebrations", prefs.showMilestoneCelebrations)
                optionalRecord("prefWrappedUseArtworkBackgrounds", prefs.wrappedUseArtworkBackgrounds)
                optionalRecord("prefWrappedBackgroundIntensity", prefs.wrappedBackgroundIntensity)
                optionalRecord("prefWrappedFallbackTheme", prefs.wrappedFallbackTheme)
            } ?: record("prefs-none")
        }
        return sha256Hex(canonical)
    }

    private fun StringBuilder.optionalRecord(tag: String, value: Any?) {
        if (value != null) record(tag, value.toString())
    }

    private fun StringBuilder.record(tag: String, vararg fields: Any?) {
        append(tag)
        for (field in fields) {
            append(FIELD)
            append(field.toString())
        }
        append(RECORD)
    }

    private fun sha256Hex(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
