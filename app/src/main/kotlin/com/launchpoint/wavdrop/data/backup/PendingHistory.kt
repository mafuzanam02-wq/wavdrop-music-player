package com.launchpoint.wavdrop.data.backup

/**
 * P5-STATS-17B - portable form of the pending (quarantine) history.
 *
 * Unmatched history from an earlier restore lives in the six `pending_*` tables. Before this section existed a later backup omitted it,
 * so a reinstall from only the newer backup lost it for good. This model carries those rows verbatim from one backup generation to the
 * next. It TRANSPORTS the quarantine; it never reinterprets it:
 *
 *  - every record keeps the origin key / origin fingerprint it was given when it was first quarantined, so restoring the same history
 *    again (or from an overlapping backup) lands on the existing unique indices and adds nothing;
 *  - records are linked by [PendingHistoryTrack.originKey], never by a local Room `pendingId` (which is meaningless on another device);
 *  - nothing is invented: a pending event has no `eventId` / `source` in the table, so none is written; nullable fields stay nullable;
 *  - restoring it never touches live statistics, events, lyrics, playlists or baselines and never matches anything to a song.
 *
 * It is an optional EXTENSION of a v2 backup. It is not covered by the primary semantic fingerprint (which is why the legacy JSON form
 * carries its own digest, and the `.wdbk` form relies on the container's per-entry SHA-256). See [PendingHistoryCodec].
 */
data class PendingHistoryBackup(
    val core: PendingHistoryCore,
    val events: List<PendingHistoryEvent>,
) {
    val isEmpty: Boolean get() = core.isEmpty && events.isEmpty()

    companion object {
        val EMPTY = PendingHistoryBackup(PendingHistoryCore.EMPTY, emptyList())
    }
}

/** Every pending record except the (potentially large) listening events, which travel separately so they can be streamed in chunks. */
data class PendingHistoryCore(
    val tracks: List<PendingHistoryTrack>,
    val stats: List<PendingHistoryStats>,
    val lyricsOverrides: List<PendingHistoryLyrics>,
    val importBaselines: List<PendingHistoryBaseline>,
    val playlistEntries: List<PendingHistoryPlaylistEntry>,
) {
    val isEmpty: Boolean
        get() = tracks.isEmpty() && stats.isEmpty() && lyricsOverrides.isEmpty() && importBaselines.isEmpty() && playlistEntries.isEmpty()

    companion object {
        val EMPTY = PendingHistoryCore(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
    }
}

/** One `pending_tracks` row. [originKey] is its portable identity and must equal `"$backupFingerprint|$backupSongId"`. */
data class PendingHistoryTrack(
    val originKey: String,
    val backupFingerprint: String,
    val backupSongId: String,
    val title: String,
    val artist: String,
    val album: String,
    val duration: Long,
    val trackNumber: Int,
    val year: Int,
    val sourceUri: String?,
    val sourceFolderPath: String?,
    /** When the row was first quarantined (by whichever device did it). Carried unchanged as provenance. */
    val restoredAt: Long,
    val status: String,
)

/** `pending_track_stats`. [lastListenedAt] null = "quarantined from a v1 backup", preserved as null, never guessed. */
data class PendingHistoryStats(
    val trackOriginKey: String,
    val playCount: Int,
    val skipCount: Int,
    val lastPlayedAt: Long,
    val totalListeningTimeMs: Long,
    val isFavorite: Boolean,
    val lastListenedAt: Long?,
)

data class PendingHistoryEvent(
    val trackOriginKey: String,
    val eventType: String,
    val occurredAt: Long,
    val listenedMs: Long,
    val durationMs: Long,
    val originFingerprint: String,
)

data class PendingHistoryLyrics(
    val trackOriginKey: String,
    val lyrics: String,
    val updatedAt: Long,
    val originFingerprint: String,
)

data class PendingHistoryBaseline(
    val trackOriginKey: String,
    val sourceType: String,
    val sourceKey: String,
    val playCount: Int,
    val originFingerprint: String,
)

data class PendingHistoryPlaylistEntry(
    val trackOriginKey: String,
    val playlistName: String,
    val position: Int,
    val originFingerprint: String,
    /** Null for rows quarantined before schema v10 (provenance unknown). Never invented. */
    val sourcePlaylistId: String?,
    val sourcePlaylistCreatedAt: Long?,
    val sourcePlaylistUpdatedAt: Long?,
)

/** Hard bounds applied to an UNTRUSTED pending-history section before anything is written. */
object PendingHistoryLimits {
    const val SCHEMA_VERSION = 1

    const val MAX_TRACKS = 250_000
    const val MAX_CHILDREN_PER_KIND = 1_000_000
    const val MAX_EVENTS = 5_000_000

    /** Keys, fingerprints, ids, types: a SHA-256 hex plus a few numbers is under 200 characters. */
    const val MAX_KEY_CHARS = 512
    /** Titles, URIs, folder paths, playlist names. */
    const val MAX_TEXT_CHARS = 8_192
    /** Lyrics text. */
    const val MAX_LYRICS_CHARS = 2_000_000
}

/** Thrown from inside a restore transaction when incoming pending history contradicts what is already stored under the same key. */
class PendingHistoryConflictException(message: String) : IllegalStateException(message)

/** Result of a restore of pending history. All counts are rows actually written by THIS restore. */
data class PendingHistoryRestoreResult(
    val tracksInserted: Int = 0,
    val statsInserted: Int = 0,
    val eventsInserted: Int = 0,
    val lyricsInserted: Int = 0,
    val baselinesInserted: Int = 0,
    val playlistEntriesInserted: Int = 0,
    /** Records that were already present and equivalent (idempotent no-ops). */
    val alreadyPresent: Int = 0,
) {
    val rowsInserted: Int
        get() = tracksInserted + statsInserted + eventsInserted + lyricsInserted + baselinesInserted + playlistEntriesInserted
}
