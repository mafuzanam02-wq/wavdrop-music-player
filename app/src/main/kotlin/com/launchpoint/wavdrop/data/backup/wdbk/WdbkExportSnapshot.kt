package com.launchpoint.wavdrop.data.backup.wdbk

import com.launchpoint.wavdrop.data.backup.BackupImportBaseline
import com.launchpoint.wavdrop.data.backup.BackupListenEvent
import com.launchpoint.wavdrop.data.backup.BackupLyricsOverride
import com.launchpoint.wavdrop.data.backup.BackupPlaylist
import com.launchpoint.wavdrop.data.backup.BackupPreferences
import com.launchpoint.wavdrop.data.backup.BackupSong
import com.launchpoint.wavdrop.data.backup.BackupTrackStats
import com.launchpoint.wavdrop.data.backup.WavdropBackup
import com.launchpoint.wavdrop.data.backup.WavdropBackupIntegrityV2

/**
 * Supplies listen events to [WdbkWriter] one bounded chunk at a time, ALREADY in the canonical fingerprint order
 * ([WavdropBackupIntegrityV2.EVENT_ORDER]). The writer never asks for more than one chunk, never keeps a returned chunk
 * after it is encoded and hashed, and never holds the whole history.
 */
fun interface WdbkEventSource {
    /**
     * The next up-to-[maxEvents] events in canonical order. Every call except the last returns exactly [maxEvents]
     * events; an EMPTY list means the history is exhausted (so a history is never written as an empty chunk).
     */
    suspend fun nextChunk(maxEvents: Int): List<BackupListenEvent>
}

/**
 * Everything [WdbkWriter] needs, WITHOUT a complete event-bearing [WavdropBackup]: the small logical sections and
 * identity metadata, plus a factory for the event source. The factory (not a single source) exists because a write can
 * be retried (`wt` then `w` open mode) and a retry must re-stream the SAME captured snapshot; each source it opens walks
 * the history from the start up to the export boundary that was captured once.
 */
class WdbkExportSnapshot(
    val backupId: String,
    val sourceInstallationId: String,
    val exportedAtMs: Long,
    val appVersionCode: Int?,
    val appVersionName: String?,
    val songs: List<BackupSong>,
    val trackStats: List<BackupTrackStats>,
    val importBaselines: List<BackupImportBaseline>,
    val lyricsOverrides: List<BackupLyricsOverride>,
    val preferences: BackupPreferences?,
    val playlists: List<BackupPlaylist>,
    val desktopOverlayRawJson: String?,
    val openEvents: () -> WdbkEventSource,
) {
    companion object {
        /**
         * Adapter for callers that already hold a complete model (tests, benchmarks). The production export path never
         * uses it: it builds the snapshot from the database with a streaming source instead.
         */
        fun fromBackup(backup: WavdropBackup): WdbkExportSnapshot {
            val sorted = backup.listenEvents.sortedWith(WavdropBackupIntegrityV2.EVENT_ORDER)
            return WdbkExportSnapshot(
                backupId = requireNotNull(backup.backupId) { "backupId must be set for WDBK export" },
                sourceInstallationId = requireNotNull(backup.sourceInstallationId) { "sourceInstallationId must be set for WDBK export" },
                exportedAtMs = requireNotNull(backup.exportedAtMs) { "exportedAtMs must be set for WDBK export" },
                appVersionCode = backup.appVersionCode,
                appVersionName = backup.appVersionName,
                songs = backup.songs,
                trackStats = backup.trackStats,
                importBaselines = backup.importBaselines,
                lyricsOverrides = backup.lyricsOverrides,
                preferences = backup.preferences,
                playlists = backup.playlists,
                desktopOverlayRawJson = backup.desktopOverlay?.rawJson,
                openEvents = {
                    var next = 0
                    WdbkEventSource { max ->
                        val to = minOf(next + max, sorted.size)
                        sorted.subList(next, to).toList().also { next = to }
                    }
                },
            )
        }
    }
}
