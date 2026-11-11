package com.launchpoint.wavdrop.data.backup

/**
 * Pure-function planner that decides which backup tracks and their associated
 * records should enter the pending (quarantine) tables.
 *
 * Called with the tracks that were NOT resolved to a live local song, plus the
 * set of originKeys already present in the DB. Returns a [Plan] describing new
 * candidates to insert and how many were already present from a prior import.
 *
 * No DB access. No side effects. All fingerprint functions are stable across
 * re-imports of the same backup so that UNIQUE constraints handle deduplication.
 *
 * ── SNAPSHOT-SCOPED QUARANTINE ──────────────────────────────────────────────
 *
 * Pending rows produced by this planner are **snapshot-scoped**.
 *
 * The [originKey] is derived from [WavdropBackupIntegrity.payloadFingerprint],
 * which includes `exportedAt`. Two exports from the same installation at
 * different times therefore produce distinct [originKey] values and create
 * separate pending rows for the same logical unresolved track.
 *
 * This is intentional for the current P2-A phase: unmatched history is
 * preserved without making claims about cross-snapshot identity. Separate
 * pending rows are NOT the same as separate historical tracks; they are
 * separate snapshot artefacts that may refer to the same piece of music.
 *
 * P2-B must NOT:
 *  - Automatically merge pending rows from different snapshots.
 *  - Deduplicate pending events across snapshots using metadata alone.
 *  - Rematch pending rows to live songs until durable TrackIdentity and
 *    stable event lineage (sourceInstallationId) are present in the backup.
 *
 * Until those foundations exist, pending rows must remain isolated and
 * readable as archive data only.
 */
object QuarantinePlanner {

    // ── Fingerprint helpers ───────────────────────────────────────────────────

    /**
     * Stable snapshot fingerprint used as the origin-key prefix for quarantine rows.
     *
     * v2 backups use [WavdropBackupIntegrityV2.fingerprint] so the quarantine origin
     * key covers all v2-specific fields (backupId, sourceInstallationId, exportedAtMs,
     * lastListenedAt). v1 backups use the v1 fingerprint unchanged.
     */
    fun backupFingerprint(backup: WavdropBackup): String = when (backup.sourceVersion) {
        BackupFormatVersion.V2 -> WavdropBackupIntegrityV2.fingerprint(backup)
        BackupFormatVersion.V1 -> WavdropBackupIntegrity.payloadFingerprint(backup)
    }

    fun originKey(fp: String, songId: Long): String = "$fp|$songId"

    fun eventFingerprint(fp: String, songId: Long, e: BackupListenEvent): String =
        "$fp|$songId|${e.occurredAt}|${e.eventType}|${e.listenedMs}"

    fun lyricsFingerprint(fp: String, songId: Long): String = "$fp|$songId|lyrics"

    fun baselineFingerprint(fp: String, songId: Long, b: BackupImportBaseline): String =
        "$fp|$songId|${b.sourceType}|${b.sourceKey}"

    /**
     * Fingerprint for a pending playlist entry.
     *
     * Uses [playlistId] (the backup-internal playlist ID) rather than the playlist
     * display name, so two same-name playlists from one backup with different IDs
     * produce distinct fingerprints and are not collapsed into the same pending row.
     */
    fun playlistEntryFingerprint(fp: String, songId: Long, playlistId: String, pos: Int): String =
        "$fp|$songId|pl:$playlistId|$pos"

    // ── Model ─────────────────────────────────────────────────────────────────

    /**
     * A single pending playlist entry with full source provenance.
     *
     * [sourcePlaylistId] is the backup-internal playlist ID (not a local Room ID);
     * it is the identity key used in [originFingerprint] so same-name playlists
     * with different IDs remain distinguishable in quarantine.
     */
    data class PlaylistEntryCandidate(
        val playlistName: String,
        val position: Int,
        val originFingerprint: String,
        val sourcePlaylistId: String,
        val sourcePlaylistCreatedAt: Long,
        val sourcePlaylistUpdatedAt: Long,
    )

    /**
     * All data needed to write one pending track and its sub-records to the DB.
     * The repository does a two-pass insert: insert the track → get the real
     * pendingId → insert sub-records with that id.
     */
    data class TrackCandidate(
        val originKey: String,
        val backupSongId: Long,
        val title: String,
        val artist: String,
        val album: String,
        val duration: Long,
        val trackNumber: Int,
        val year: Int,
        val sourceUri: String?,
        val sourceFolderPath: String?,
        val stats: BackupTrackStats?,
        /** Each pair: (event, originFingerprint). */
        val events: List<Pair<BackupListenEvent, String>>,
        /** Pair of (override, originFingerprint), or null if no lyrics for this track. */
        val lyrics: Pair<BackupLyricsOverride, String>?,
        /** Each pair: (baseline, originFingerprint). */
        val baselines: List<Pair<BackupImportBaseline, String>>,
        val playlistEntries: List<PlaylistEntryCandidate>,
    )

    data class Plan(
        val newCandidates: List<TrackCandidate>,
        val alreadyPresentCount: Int,
    ) {
        val hasNewData: Boolean get() = newCandidates.isNotEmpty()
        val newTracksCount: Int get() = newCandidates.size
        val newEventsCount: Int get() = newCandidates.sumOf { it.events.size }
        val newPlaylistEntriesCount: Int get() = newCandidates.sumOf { it.playlistEntries.size }
    }

    // ── plan() ────────────────────────────────────────────────────────────────

    /**
     * @param backup               The full backup being imported.
     * @param backupFingerprint    Stable fingerprint for this backup (from [backupFingerprint]).
     * @param resolvedBackupSongIds Backup songIds that were successfully matched to live songs
     *                             (these do NOT enter the quarantine).
     * @param existingOriginKeys   The current contents of `SELECT originKey FROM pending_tracks`.
     */
    fun plan(
        backup: WavdropBackup,
        backupFingerprint: String,
        resolvedBackupSongIds: Set<Long>,
        existingOriginKeys: Set<String>,
    ): Plan {
        val fp = backupFingerprint

        // Index backup data by backup songId for O(1) lookup.
        val statsBySongId = backup.trackStats.associateBy { it.songId }
        val eventsBySongId = backup.listenEvents.groupBy { it.songId }
        val lyricsBySongId = backup.lyricsOverrides.associateBy { it.songId }
        val baselinesBySongId = backup.importBaselines.groupBy { it.songId }

        // Build playlist entries indexed by backup songId.
        val playlistEntriesBySongId = mutableMapOf<Long, MutableList<PlaylistEntryCandidate>>()
        for (playlist in backup.playlists) {
            val playlistIdStr = playlist.id.toString()
            for (entry in playlist.songs) {
                val fingerprint = playlistEntryFingerprint(fp, entry.songId, playlistIdStr, entry.position)
                playlistEntriesBySongId
                    .getOrPut(entry.songId) { mutableListOf() }
                    .add(
                        PlaylistEntryCandidate(
                            playlistName            = playlist.name,
                            position                = entry.position,
                            originFingerprint       = fingerprint,
                            sourcePlaylistId        = playlistIdStr,
                            sourcePlaylistCreatedAt = playlist.createdAt,
                            sourcePlaylistUpdatedAt = playlist.updatedAt,
                        )
                    )
            }
        }

        val backupSongById = backup.songs.associateBy { it.id }

        // Collect backup songs that have any data and were NOT resolved.
        val unmatchedSongIds = backup.songs
            .map { it.id }
            .filter { id ->
                id !in resolvedBackupSongIds &&
                    hasAnyData(id, statsBySongId, eventsBySongId, lyricsBySongId, baselinesBySongId, playlistEntriesBySongId)
            }

        // P5-STATS-17C: DESCRIPTOR-LESS ORPHANS. A record may reference a backup song id that has no entry in the backup's own `songs`
        // list (a v1/legacy export that dropped the song, or history whose song row was already gone when the backup was written).
        // Such an id can never be resolved to a live song (resolution starts from a descriptor, and an id alone is not identity), so it
        // can never be restored live; before this it was simply dropped. It is real history, so it is quarantined under the same
        // snapshot-scoped origin key, with metadata left unknown. Sorted so the plan is deterministic.
        val orphanSongIds = (statsBySongId.keys + eventsBySongId.keys + lyricsBySongId.keys + baselinesBySongId.keys +
            playlistEntriesBySongId.keys)
            .filter { id ->
                id !in backupSongById &&
                    hasAnyData(id, statsBySongId, eventsBySongId, lyricsBySongId, baselinesBySongId, playlistEntriesBySongId)
            }
            .distinct()
            .sorted()

        val orphanHints = orphanHints(backup, orphanSongIds.toHashSet())

        val newCandidates = mutableListOf<TrackCandidate>()
        var alreadyPresentCount = 0

        for (songId in unmatchedSongIds + orphanSongIds) {
            val key = originKey(fp, songId)
            if (key in existingOriginKeys) {
                alreadyPresentCount++
                continue
            }

            val song = backupSongById[songId]

            val events = (eventsBySongId[songId] ?: emptyList())
                .map { e -> e to eventFingerprint(fp, songId, e) }

            val lyrics = lyricsBySongId[songId]
                ?.let { o -> o to lyricsFingerprint(fp, songId) }

            val baselines = (baselinesBySongId[songId] ?: emptyList())
                .map { b -> b to baselineFingerprint(fp, songId, b) }

            val plEntries = playlistEntriesBySongId[songId] ?: emptyList()

            // Descriptor-less orphan: "" / 0 / null mean UNKNOWN, never an actual title, duration or path. The only values filled in
            // are ones the backup itself states on the orphan's own records, and only when every such record agrees.
            val hint = if (song == null) orphanHints[songId] else null

            newCandidates += TrackCandidate(
                originKey        = key,
                backupSongId     = songId,
                title            = song?.title ?: hint?.title ?: "",
                artist           = song?.artist ?: hint?.artist ?: "",
                album            = song?.album ?: hint?.album ?: "",
                duration         = song?.duration ?: 0L,
                trackNumber      = song?.trackNumber ?: 0,
                year             = song?.year ?: 0,
                sourceUri        = song?.uri ?: hint?.uri,
                sourceFolderPath = song?.folderPath,
                stats            = statsBySongId[songId],
                events           = events,
                lyrics           = lyrics,
                baselines        = baselines,
                playlistEntries  = plEntries,
            )
        }

        return Plan(newCandidates = newCandidates, alreadyPresentCount = alreadyPresentCount)
    }

    private data class OrphanHint(val title: String?, val artist: String?, val album: String?, val uri: String?)

    /** Distinct values seen for one orphan; only whether there is exactly one matters, so it stops growing at two. */
    private class Seen<T> {
        var first: T? = null
        var conflicted = false
        fun add(v: T) {
            val f = first
            if (f == null) first = v else if (f != v) conflicted = true
        }
        fun unanimous(): T? = if (conflicted) null else first
    }

    /**
     * Descriptive fields stated by each orphan's own records (events and playlist entries carry title/artist/album; stats, lyrics,
     * events and playlist entries carry a content URI). Each field is used only if at least one record states it and ALL records that
     * state it agree; otherwise it stays unknown. Nothing is guessed, and the result depends only on the backup, so repeats are
     * idempotent.
     *
     * One pass over each record list, touching only records of an orphan id: work is linear in the number of records, not
     * orphans x records. Title, artist and album are only meaningful together, so the whole triple must be unanimous
     * (all-blank triples state nothing and are ignored; blank URIs likewise).
     */
    private fun orphanHints(backup: WavdropBackup, orphanIds: Set<Long>): Map<Long, OrphanHint> {
        if (orphanIds.isEmpty()) return emptyMap()
        val tags = HashMap<Long, Seen<Triple<String, String, String>>>()
        val uris = HashMap<Long, Seen<String>>()
        fun tag(id: Long, t: String, a: String, al: String) {
            if (id !in orphanIds) return
            if (t.isNotBlank() || a.isNotBlank() || al.isNotBlank()) tags.getOrPut(id) { Seen() }.add(Triple(t, a, al))
        }
        fun uri(id: Long, u: String) {
            if (id !in orphanIds) return
            if (u.isNotBlank()) uris.getOrPut(id) { Seen() }.add(u)
        }
        for (s in backup.trackStats) uri(s.songId, s.contentUri)
        for (l in backup.lyricsOverrides) uri(l.songId, l.contentUri)
        for (e in backup.listenEvents) { tag(e.songId, e.title, e.artist, e.album); uri(e.songId, e.contentUri) }
        for (p in backup.playlists) for (s in p.songs) { tag(s.songId, s.title, s.artist, s.album); uri(s.songId, s.contentUri) }
        return orphanIds.associateWith { id ->
            val triple = tags[id]?.unanimous()
            OrphanHint(triple?.first, triple?.second, triple?.third, uris[id]?.unanimous())
        }
    }

    private fun hasAnyData(
        songId: Long,
        stats: Map<Long, BackupTrackStats>,
        events: Map<Long, List<BackupListenEvent>>,
        lyrics: Map<Long, BackupLyricsOverride>,
        baselines: Map<Long, List<BackupImportBaseline>>,
        plEntries: Map<Long, List<*>>,
    ): Boolean =
        songId in stats ||
            events[songId]?.isNotEmpty() == true ||
            songId in lyrics ||
            baselines[songId]?.isNotEmpty() == true ||
            plEntries[songId]?.isNotEmpty() == true
}
