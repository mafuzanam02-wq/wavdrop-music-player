package com.launchpoint.wavdrop.data.backup

import java.security.MessageDigest

/**
 * Payload integrity fingerprint for Wavdrop backups.
 *
 * The fingerprint is a SHA-256 over a deterministic, canonical string built from
 * the backup MODEL — never from JSON text. This makes the check immune to
 * pretty-printing, key ordering, and provider re-encoding differences: the
 * exporter fingerprints the model it serialises, and the parser re-fingerprints
 * the model it reconstructed. If any data field was corrupted in transit, the
 * two fingerprints differ.
 *
 * Metadata fields ([WavdropBackup.appVersionCode], [WavdropBackup.appVersionName],
 * [WavdropBackup.manifest], [WavdropBackup.payloadSha256]) are deliberately
 * excluded — the checksum protects the data payload, not its own envelope.
 *
 * Canonical form: fields joined with the ASCII unit separator (), records
 * terminated with the record separator (), sections prefixed with a tag.
 * List order is preserved (JSON arrays keep order through export and parse).
 */
object WavdropBackupIntegrity {

    private const val FIELD = ''  // ASCII unit separator
    private const val RECORD = '' // ASCII record separator

    fun payloadFingerprint(backup: WavdropBackup): String {
        val canonical = buildString {
            record("exportedAt", backup.exportedAt)

            for (s in backup.songs) {
                record(
                    "song", s.id, s.uri, s.title, s.artist, s.album, s.albumId,
                    s.duration, s.dateAdded, s.trackNumber, s.year,
                    s.folderPath ?: "", s.folderName ?: "",
                )
            }
            for (t in backup.trackStats) {
                record(
                    "stat", t.songId, t.contentUri, t.playCount, t.skipCount,
                    t.lastPlayedAt, t.totalListeningTimeMs, t.isFavorite,
                )
            }
            for (b in backup.importBaselines) {
                record(
                    "baseline", b.songId, b.sourceType, b.sourceKey,
                    b.lastImportedPlayCount, b.lastImportedSkipCount, b.lastImportedAt,
                )
            }
            for (o in backup.lyricsOverrides) {
                record("lyrics", o.songId, o.contentUri, o.lyrics, o.updatedAt)
            }
            for (p in backup.playlists) {
                record("playlist", p.id, p.name, p.createdAt, p.updatedAt)
                for (s in p.songs) {
                    record("playlistSong", s.songId, s.contentUri, s.position, s.title, s.artist, s.album)
                }
            }
            for (e in backup.listenEvents) {
                record(
                    "event", e.songId, e.contentUri, e.title, e.artist, e.album,
                    e.eventType, e.occurredAt, e.listenedMs, e.durationMs, e.source,
                )
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
                // Phase 4 settings: appended as separate keyed records ONLY when present.
                // Older backups (where these are all null) produce the exact canonical
                // string they were fingerprinted with, so their stored checksums still
                // validate. Never add new fields to the fixed "prefs" record above.
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
