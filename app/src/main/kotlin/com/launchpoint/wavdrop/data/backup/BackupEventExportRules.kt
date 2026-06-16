package com.launchpoint.wavdrop.data.backup

import com.launchpoint.wavdrop.data.local.entity.TrackListenEventEntity

/**
 * Decides which listen-event rows belong in a Wavdrop backup.
 *
 * Backups must carry BOTH native playback events and previously restored events:
 * restore stamps inserted rows as [TrackListenEventEntity.SOURCE_MANUAL_RESTORE], so a
 * playback-only filter silently drops all restored history from the next backup —
 * backup → restore → backup → restore loses everything older than the first restore.
 *
 * Imported synthetic history (BlackPlayer) stays excluded: imports are aggregate-only
 * by product rule and must never fabricate event history.
 *
 * Desktop playback rows are real Wavdrop playback events restored from a verified
 * Desktop backup, so they must survive Android re-export.
 */
object BackupEventExportRules {

    fun shouldExport(source: String): Boolean =
        source == TrackListenEventEntity.SOURCE_WAVDROP_PLAYBACK ||
            source == TrackListenEventEntity.SOURCE_MANUAL_RESTORE ||
            source == TrackListenEventEntity.SOURCE_DESKTOP_PLAYBACK
}
