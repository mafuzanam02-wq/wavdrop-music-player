package com.launchpoint.wavdrop.data.backup

import com.launchpoint.wavdrop.data.local.dao.PendingTrackDao
import com.launchpoint.wavdrop.data.local.entity.PendingImportBaselineEntity
import com.launchpoint.wavdrop.data.local.entity.PendingLyricsOverrideEntity
import com.launchpoint.wavdrop.data.local.entity.PendingPlaylistEntryEntity
import com.launchpoint.wavdrop.data.local.entity.PendingListenEventEntity
import com.launchpoint.wavdrop.data.local.entity.PendingTrackEntity
import com.launchpoint.wavdrop.data.local.entity.PendingTrackStatsEntity

/** Supplies pending events to the `.wdbk` writer one bounded chunk at a time; an empty list ends the stream. */
fun interface PendingHistoryEventSource {
    suspend fun nextChunk(maxEvents: Int): List<PendingHistoryEvent>
}

/**
 * Everything the `.wdbk` writer needs for the pending-history extension WITHOUT the events: those are streamed through [openEvents]
 * up to a boundary captured before [core] was read (so every streamed event's track is in [core], even if a restore runs meanwhile).
 */
class PendingHistoryExport(
    val core: PendingHistoryCore,
    val openEvents: () -> PendingHistoryEventSource,
) {
    val hasContent: Boolean get() = !core.isEmpty
}

/**
 * The only code that reads or writes the `pending_*` tables on behalf of the portable pending-history section.
 *
 * EXPORT describes the CURRENT pending set (one row of each kind per stored row, original keys intact) - it never appends a copy of
 * prior backup generations. RESTORE inserts a validated section additively, row by row, and treats every unique-key hit as either an
 * idempotent no-op (the stored row is equivalent) or a [PendingHistoryConflictException] (it is not): contradictory data under one key
 * is never silently dropped. It must run inside the caller's restore transaction so a conflict rolls the whole restore back.
 */
class PendingHistoryStore(private val dao: PendingTrackDao) {

    // ── Export ────────────────────────────────────────────────────────────────

    /** Reads the small records now and returns a streaming source for events. Null when there is nothing pending. */
    suspend fun openExport(): PendingHistoryExport? {
        val boundary = dao.getMaxEventId() ?: 0L        // captured BEFORE the tracks are read: see class doc of PendingHistoryExport
        val core = loadCore()
        if (core.isEmpty) return null
        return PendingHistoryExport(core) {
            var after = ""
            PendingHistoryEventSource { max ->
                val page = dao.getEventExportPage(boundary, after, max)
                if (page.isNotEmpty()) after = page.last().originFingerprint
                page.map {
                    PendingHistoryEvent(it.originKey, it.eventType, it.occurredAt, it.listenedMs, it.durationMs, it.originFingerprint)
                }
            }
        }
    }

    /** Whole section including events, for the legacy JSON export (which, like its live history, is a single in-memory document). */
    suspend fun loadAll(): PendingHistoryBackup? {
        val export = openExport() ?: return null
        val source = export.openEvents()
        val events = ArrayList<PendingHistoryEvent>()
        while (true) {
            val chunk = source.nextChunk(EVENT_PAGE)
            if (chunk.isEmpty()) break
            events += chunk
        }
        return PendingHistoryBackup(export.core, events)
    }

    private suspend fun loadCore(): PendingHistoryCore {
        val trackEntities = dao.getAllTracksForExport()
        if (trackEntities.isEmpty()) return PendingHistoryCore.EMPTY
        val keyById = trackEntities.associate { it.pendingId to it.originKey }
        fun key(pendingId: Long) = keyById[pendingId] ?: error("pending row $pendingId has no parent track")
        return PendingHistoryCore(
            tracks = trackEntities.map { it.toPortable() },
            stats = dao.getAllStatsForExport().map { it.toPortable(key(it.pendingId)) }.sortedBy { it.trackOriginKey },
            lyricsOverrides = dao.getAllLyricsForExport().map { it.toPortable(key(it.pendingId)) }.sortedBy { it.originFingerprint },
            importBaselines = dao.getAllBaselinesForExport().map { it.toPortable(key(it.pendingId)) }.sortedBy { it.originFingerprint },
            playlistEntries = dao.getAllPlaylistEntriesForExport().map { it.toPortable(key(it.pendingId)) }.sortedBy { it.originFingerprint },
        )
    }

    // ── Preview support ───────────────────────────────────────────────────────

    /**
     * True when applying [history] would insert at least one row, or would hit a stored stats row that differs from the incoming one.
     * The latter is reported as actionable (never as an up-to-date no-op) so the user reaches the authoritative apply, which rejects
     * the conflict transactionally. Read-only; the apply path re-checks everything inside its transaction.
     */
    suspend fun hasNewData(history: PendingHistoryBackup): Boolean {
        if (history.isEmpty) return false
        val tracks = dao.getAllOriginKeys().toHashSet()
        if (history.core.tracks.any { it.originKey !in tracks }) return true
        // Stats are one row per track, keyed by the track (no fingerprint), so an already-present track can still lack its stats row.
        for (s in history.core.stats) {
            val pendingId = dao.getPendingId(s.trackOriginKey) ?: return true
            if (dao.getStatsFor(pendingId) != s.toEntity(pendingId)) return true   // missing, or conflicting: apply decides
        }
        val events = dao.getAllEventFingerprints().toHashSet()
        if (history.events.any { it.originFingerprint !in events }) return true
        val lyrics = dao.getAllLyricsFingerprints().toHashSet()
        if (history.core.lyricsOverrides.any { it.originFingerprint !in lyrics }) return true
        val baselines = dao.getAllBaselineFingerprints().toHashSet()
        if (history.core.importBaselines.any { it.originFingerprint !in baselines }) return true
        val entries = dao.getAllPlaylistEntryFingerprints().toHashSet()
        return history.core.playlistEntries.any { it.originFingerprint !in entries }
    }

    // ── Restore ───────────────────────────────────────────────────────────────

    /**
     * Inserts [history] (already validated by [PendingHistoryCodec.validate]) without touching anything else. Must be called inside the
     * caller's database transaction. Throws [PendingHistoryConflictException] if a stored row shares a key with an incoming row but
     * differs from it; the transaction then rolls back in full.
     */
    suspend fun restore(history: PendingHistoryBackup): PendingHistoryRestoreResult {
        var tracks = 0; var stats = 0; var events = 0; var lyrics = 0; var baselines = 0; var entries = 0; var present = 0
        val core = history.core

        val pendingIds = HashMap<String, Long>(core.tracks.size * 2)
        for (t in core.tracks) {
            val incoming = t.toEntity()
            val rowId = dao.insertTrack(incoming)
            if (rowId != -1L) {
                pendingIds[t.originKey] = rowId; tracks++
            } else {
                val existing = dao.getTrackByOriginKey(t.originKey) ?: conflict("track", t.originKey)
                if (!existing.equivalentTo(incoming)) conflict("track", t.originKey)
                pendingIds[t.originKey] = existing.pendingId; present++
            }
        }
        fun parent(key: String) = pendingIds[key] ?: error("validated section referenced an unknown track")

        for (s in core.stats) {
            val incoming = s.toEntity(parent(s.trackOriginKey))
            if (dao.insertStatsOrIgnore(incoming) != -1L) stats++
            else if (dao.getStatsFor(incoming.pendingId) == incoming) present++
            else conflict("stats", s.trackOriginKey)
        }
        for (l in core.lyricsOverrides) {
            val incoming = l.toEntity(parent(l.trackOriginKey))
            if (dao.insertLyricsOrIgnore(incoming) != -1L) lyrics++
            else if ((dao.getLyricsFor(incoming.pendingId) ?: dao.getLyricsByFingerprint(l.originFingerprint)) == incoming) present++
            else conflict("lyrics", l.originFingerprint)
        }
        for (b in core.importBaselines) {
            val incoming = b.toEntity(parent(b.trackOriginKey))
            if (dao.insertBaselineOrIgnore(incoming) != -1L) baselines++
            else if (dao.getBaselineByFingerprint(b.originFingerprint)?.equivalentTo(incoming) == true) present++
            else conflict("import baseline", b.originFingerprint)
        }
        for (p in core.playlistEntries) {
            val incoming = p.toEntity(parent(p.trackOriginKey))
            if (dao.insertPlaylistEntryOrIgnore(incoming) != -1L) entries++
            else if (dao.getPlaylistEntryByFingerprint(p.originFingerprint)?.equivalentTo(incoming) == true) present++
            else conflict("playlist entry", p.originFingerprint)
        }
        // P5-STATS-18C1: a carried event ALREADY has an established identity (its originFingerprint, which need not be canonical after a
        // legacy-survivor repair). It is transported verbatim: never regrouped, relabelled or re-slotted. An absent fingerprint is
        // inserted with its own payload, an identical stored row is a no-op, and a different payload under the same fingerprint (or a
        // fingerprint owned by another track) is a conflict that rolls the whole restore back.
        for (e in history.events) {
            val incoming = PendingListenEventEntity(
                pendingId = parent(e.trackOriginKey), eventType = e.eventType, occurredAt = e.occurredAt, listenedMs = e.listenedMs,
                durationMs = e.durationMs, originFingerprint = e.originFingerprint,
            )
            if (dao.insertEventOrIgnore(incoming) != -1L) { events++; continue }
            val stored = dao.getEventByFingerprint(e.originFingerprint) ?: conflict("listen event", e.originFingerprint)
            if (stored.pendingId == incoming.pendingId && stored.eventType == incoming.eventType && stored.occurredAt == incoming.occurredAt &&
                stored.listenedMs == incoming.listenedMs && stored.durationMs == incoming.durationMs
            ) present++ else conflict("listen event", e.originFingerprint)
        }
        return PendingHistoryRestoreResult(tracks, stats, events, lyrics, baselines, entries, present)
    }

    private fun conflict(kind: String, key: String): Nothing =
        throw PendingHistoryConflictException(
            "The quarantined history in this backup conflicts with history already stored on this device ($kind $key). Nothing was restored.",
        )

    // ── Row equivalence (ignores only local surrogate ids) ────────────────────

    /** Same logical track. `restoredAt` is provenance of WHEN a device quarantined it, so a row restored earlier here may differ legitimately. */
    private fun PendingTrackEntity.equivalentTo(o: PendingTrackEntity) =
        originKey == o.originKey && backupFingerprint == o.backupFingerprint && backupSongId == o.backupSongId &&
            title == o.title && artist == o.artist && album == o.album && duration == o.duration && trackNumber == o.trackNumber &&
            year == o.year && sourceUri == o.sourceUri && sourceFolderPath == o.sourceFolderPath && status == o.status

    private fun PendingImportBaselineEntity.equivalentTo(o: PendingImportBaselineEntity) =
        pendingId == o.pendingId && sourceType == o.sourceType && sourceKey == o.sourceKey && playCount == o.playCount &&
            originFingerprint == o.originFingerprint

    private fun PendingPlaylistEntryEntity.equivalentTo(o: PendingPlaylistEntryEntity) =
        pendingId == o.pendingId && playlistName == o.playlistName && position == o.position && originFingerprint == o.originFingerprint &&
            sourcePlaylistId == o.sourcePlaylistId && sourcePlaylistCreatedAt == o.sourcePlaylistCreatedAt &&
            sourcePlaylistUpdatedAt == o.sourcePlaylistUpdatedAt

    // ── Mapping ───────────────────────────────────────────────────────────────

    private fun PendingTrackEntity.toPortable() = PendingHistoryTrack(
        originKey, backupFingerprint, backupSongId, title, artist, album, duration, trackNumber, year, sourceUri, sourceFolderPath, restoredAt, status,
    )

    private fun PendingTrackStatsEntity.toPortable(key: String) =
        PendingHistoryStats(key, playCount, skipCount, lastPlayedAt, totalListeningTimeMs, isFavorite, lastListenedAt)

    private fun PendingLyricsOverrideEntity.toPortable(key: String) = PendingHistoryLyrics(key, lyrics, updatedAt, originFingerprint)

    private fun PendingImportBaselineEntity.toPortable(key: String) =
        PendingHistoryBaseline(key, sourceType, sourceKey, playCount, originFingerprint)

    private fun PendingPlaylistEntryEntity.toPortable(key: String) = PendingHistoryPlaylistEntry(
        key, playlistName, position, originFingerprint, sourcePlaylistId, sourcePlaylistCreatedAt, sourcePlaylistUpdatedAt,
    )

    private fun PendingHistoryTrack.toEntity() = PendingTrackEntity(
        originKey = originKey, backupFingerprint = backupFingerprint, backupSongId = backupSongId, title = title, artist = artist,
        album = album, duration = duration, trackNumber = trackNumber, year = year, sourceUri = sourceUri,
        sourceFolderPath = sourceFolderPath, restoredAt = restoredAt, status = status,
    )

    private fun PendingHistoryStats.toEntity(pendingId: Long) = PendingTrackStatsEntity(
        pendingId, playCount, skipCount, lastPlayedAt, totalListeningTimeMs, isFavorite, lastListenedAt,
    )

    private fun PendingHistoryLyrics.toEntity(pendingId: Long) = PendingLyricsOverrideEntity(pendingId, lyrics, updatedAt, originFingerprint)

    private fun PendingHistoryBaseline.toEntity(pendingId: Long) = PendingImportBaselineEntity(
        pendingId = pendingId, sourceType = sourceType, sourceKey = sourceKey, playCount = playCount, originFingerprint = originFingerprint,
    )

    private fun PendingHistoryPlaylistEntry.toEntity(pendingId: Long) = PendingPlaylistEntryEntity(
        pendingId = pendingId, playlistName = playlistName, position = position, originFingerprint = originFingerprint,
        sourcePlaylistId = sourcePlaylistId, sourcePlaylistCreatedAt = sourcePlaylistCreatedAt, sourcePlaylistUpdatedAt = sourcePlaylistUpdatedAt,
    )

    private companion object {
        const val EVENT_PAGE = 2_000
    }
}
