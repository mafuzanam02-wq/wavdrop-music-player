package com.launchpoint.wavdrop.data.repository

import androidx.room.withTransaction
import com.launchpoint.wavdrop.data.backup.StatsImportMerger
import com.launchpoint.wavdrop.data.legacy.BpstatApplyResult
import com.launchpoint.wavdrop.data.legacy.BlackPlayerStatImportRow
import com.launchpoint.wavdrop.data.legacy.ImportSourceTypes
import com.launchpoint.wavdrop.data.legacy.planBpstatMerge
import com.launchpoint.wavdrop.data.local.WavdropDatabase
import com.launchpoint.wavdrop.data.local.dao.ImportBaselineDao
import com.launchpoint.wavdrop.data.local.dao.TrackListenEventDao
import com.launchpoint.wavdrop.data.local.dao.TrackStatsDao
import com.launchpoint.wavdrop.data.local.entity.ImportBaselineEntity
import com.launchpoint.wavdrop.data.local.entity.TrackListenEventEntity
import com.launchpoint.wavdrop.data.local.entity.TrackStatsEntity
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.model.SongCompletionSummary
import com.launchpoint.wavdrop.data.model.TrackStats
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StatsRepository @Inject constructor(
    private val db: WavdropDatabase,
    private val dao: TrackStatsDao,
    private val importBaselineDao: ImportBaselineDao,
    private val listenEventDao: TrackListenEventDao,
) : PlayEventWriter {
    // ── Regular write ops ─────────────────────────────────────────────────────

    /**
     * Records a meaningful play: updates the aggregate row and appends a PLAY event.
     *
     * [durationMs] is the track's total duration — 0 if unknown. Used only for the event
     * record; the aggregate is unaffected.
     *
     * NOTE: BlackPlayer imports do NOT call this method — they go through [applyBpstatImport]
     * which calls [TrackStatsDao.mergeImportedStats] directly. No events are written for imports.
     */
    override suspend fun recordPlay(songId: Long, contentUri: String, listenedMs: Long, durationMs: Long) {
        val nowMs = System.currentTimeMillis()
        db.withTransaction {
            dao.insertIfAbsent(TrackStatsEntity(songId = songId, contentUri = contentUri))
            dao.incrementPlayCount(songId, nowMs = nowMs, listenedMs = listenedMs)
            listenEventDao.insert(
                TrackListenEventEntity(
                    songId = songId,
                    eventType = TrackListenEventEntity.TYPE_PLAY,
                    occurredAt = nowMs,
                    listenedMs = listenedMs,
                    durationMs = durationMs,
                    source = TrackListenEventEntity.SOURCE_WAVDROP_PLAYBACK,
                    // Stable per-event id generated at creation time (contract §9.2).
                    eventId = UUID.randomUUID().toString(),
                )
            )
        }
    }

    /**
     * Records a skip: updates the aggregate row and appends a SKIP event.
     *
     * [durationMs] is the track's total duration — 0 if unknown. Used only for the event record.
     */
    override suspend fun recordListenStart(songId: Long, contentUri: String) {
        val nowMs = System.currentTimeMillis()
        db.withTransaction {
            dao.insertIfAbsent(TrackStatsEntity(songId = songId, contentUri = contentUri))
            dao.updateLastListenedAt(songId, nowMs)
        }
    }

    override suspend fun recordSkip(songId: Long, contentUri: String, durationMs: Long) {
        db.withTransaction {
            val nowMs = System.currentTimeMillis()
            dao.insertIfAbsent(TrackStatsEntity(songId = songId, contentUri = contentUri))
            dao.incrementSkipCount(songId)
            listenEventDao.insert(
                TrackListenEventEntity(
                    songId = songId,
                    eventType = TrackListenEventEntity.TYPE_SKIP,
                    occurredAt = nowMs,
                    listenedMs = 0L,
                    durationMs = durationMs,
                    source = TrackListenEventEntity.SOURCE_WAVDROP_PLAYBACK,
                    // Stable per-event id generated at creation time (contract §9.2).
                    eventId = UUID.randomUUID().toString(),
                )
            )
        }
    }

    suspend fun toggleFavorite(songId: Long, contentUri: String) {
        db.withTransaction {
            dao.insertIfAbsent(TrackStatsEntity(songId = songId, contentUri = contentUri))
            dao.toggleFavorite(songId)
        }
    }

    // ── Read ops ──────────────────────────────────────────────────────────────

    /**
     * Every listen event ENTITY, most recent first. Only for genuine full-history entity consumers: ordinary analytics screens
     * must not subscribe to it (WC-02). Use [listenEventCount], [analyticsEventTimestamps], [playEventTimestamps] or
     * [listenEventsInRange] for counts, timestamps or one selected period.
     */
    fun allListenEvents(): Flow<List<TrackListenEventEntity>> = listenEventDao.observeAll()

    /** Number of listen-event rows (all types) without loading them. */
    fun listenEventCount(): Flow<Int> = listenEventDao.observeCount()

    /** occurredAt of every PLAY and SKIP event (timestamps only): feeds available months/years. */
    fun analyticsEventTimestamps(): Flow<List<Long>> = listenEventDao.observeAnalyticsEventTimestamps()

    /** occurredAt of every PLAY event, most recent first (timestamps only): feeds streaks and most-active weekday/hour. */
    fun playEventTimestamps(): Flow<List<Long>> = listenEventDao.observePlayEventTimestamps()

    /** Latest PLAY/SKIP event timestamp used to select the bounded Home Wrapped preview year. */
    fun latestAnalyticsEventAt(): Flow<Long?> = listenEventDao.observeLatestAnalyticsEventAt()

    /**
     * Listen events within an inclusive [fromMs]..[toMs] window. Lets callers that only need a
     * bounded slice (e.g. current-month Most Played sorting) avoid observing the full event table
     * and re-emitting on every out-of-range insert (WC-02).
     */
    fun listenEventsInRange(fromMs: Long, toMs: Long): Flow<List<TrackListenEventEntity>> =
        listenEventDao.observeInRange(fromMs, toMs)

    /** Per-song completion summaries from native Wavdrop playback. Used by Smart Collections. */
    fun observeCompletionSummaries(): Flow<List<SongCompletionSummary>> =
        listenEventDao.observeCompletionSummaries()

    fun favoriteSongIds(): Flow<Set<Long>> =
        dao.favoriteSongIds().map { it.toSet() }

    fun observeStats(songId: Long): Flow<TrackStats?> =
        dao.observeStatsBySongId(songId).map { it?.toDomain() }

    fun allPlayCounts(): Flow<Map<Long, Int>> =
        dao.getAllStats().map { list -> list.associate { it.songId to it.playCount } }

    fun allTrackStatsEntities(): Flow<List<TrackStatsEntity>> =
        dao.getAllStats()

    /** At most [limit] live-song stats rows, most played first (WC-04). A non-positive [limit] yields an empty list. */
    fun mostPlayedPreview(limit: Int): Flow<List<TrackStatsEntity>> =
        if (limit <= 0) flowOf(emptyList()) else dao.observeMostPlayedPreview(limit)

    /** At most [limit] live-song stats rows, most recently listened (lastListenedAt) first (WC-04). Non-positive [limit] -> empty. */
    fun recentlyListenedPreview(limit: Int): Flow<List<TrackStatsEntity>> =
        if (limit <= 0) flowOf(emptyList()) else dao.observeRecentlyListenedPreview(limit)

    fun statsForSongs(songIds: List<Long>): Flow<List<TrackStats>> =
        dao.getStatsForSongs(songIds).map { list -> list.map(TrackStatsEntity::toDomain) }

    fun mostPlayed(): Flow<List<TrackStats>> =
        dao.getMostPlayed().map { list -> list.map(TrackStatsEntity::toDomain) }

    fun recentlyPlayed(): Flow<List<TrackStats>> =
        dao.getRecentlyPlayed().map { list -> list.map(TrackStatsEntity::toDomain) }

    fun mostSkipped(): Flow<List<TrackStats>> =
        dao.getMostSkipped().map { list -> list.map(TrackStatsEntity::toDomain) }

    // ── BlackPlayer import ────────────────────────────────────────────────────

    /**
     * Merges [matchedRows] into the Wavdrop stats database inside a single Room transaction.
     *
     * Merge strategy: MAX-reconciliation of what a .bpstat file actually supplies.
     * - newPlayCount  = MAX(current, imported main play count)   (BlackPlayer field 1)
     * - lastPlayedAt  = MAX(current, imported)
     * - skipCount is NEVER changed: BlackPlayer field 2 is a PERIOD play count, not skips, and is neither imported, added to
     *   the play count, nor turned into listening time or events (see [planBpstatMerge]). Existing skip counts, including any
     *   that an older version mis-imported from field 2, are left exactly as they are (their provenance cannot be proven, so
     *   they are not decremented).
     *
     * This is idempotent: importing the same file twice produces no change on the second import. Local stats that are
     * already higher are never reduced. totalListeningTimeMs is not available from BlackPlayer and is left unchanged. No
     * listen events are written.
     *
     * Import baselines are still written for historical tracking (play count; skip baseline 0 because the file carries no skip
     * evidence) but are not required for idempotency (MAX semantics guarantee that).
     *
     * @param matchedRows  Pairs of (Wavdrop Song, BlackPlayer import row) to apply.
     * @param unmatchedCount Rows that had no match — recorded in the result for display.
     */
    suspend fun applyBpstatImport(
        matchedRows: List<Pair<Song, BlackPlayerStatImportRow>>,
        unmatchedCount: Int,
    ): BpstatApplyResult = db.withTransaction {
        var tracksUpdated = 0
        var playsImported = 0L
        val importedAt = System.currentTimeMillis()

        // Pre-load all current stats to compute reporting deltas without N individual queries.
        val currentStatsById = dao.getAllStatsSnapshot().associateBy { it.songId }

        for ((song, row) in matchedRows) {
            val current = currentStatsById[song.id]

            // Ensure a stats row exists before updating.
            dao.insertIfAbsent(TrackStatsEntity(songId = song.id, contentUri = song.uri))

            val plan = planBpstatMerge(
                currentPlayCount       = current?.playCount ?: 0,
                currentSkipCount       = current?.skipCount ?: 0,
                currentListeningTimeMs = current?.totalListeningTimeMs ?: 0L,
                row                    = row,
            )

            // MAX-reconciliation. The plan passes 0 for skips, listening time and lastListenedAt, so MAX(local, 0) = local.
            dao.mergeMaxStats(
                songId                  = song.id,
                importedPlayCount       = plan.importedPlayCount,
                importedSkipCount       = plan.importedSkipCount,
                importedListeningTimeMs = plan.importedListeningTimeMs,
                importedLastPlayedAt    = plan.importedLastPlayedAt,
                importedLastListenedAt  = plan.importedLastListenedAt,
            )

            if (plan.effect.anyUpdated) {
                tracksUpdated++
                playsImported += plan.effect.playDelta
            }

            // Write baseline for historical tracking.
            importBaselineDao.upsertBaseline(
                ImportBaselineEntity(
                    songId                = song.id,
                    sourceType            = ImportSourceTypes.BLACKPLAYER_BPSTAT,
                    sourceKey             = row.blackPlayerBpstatSourceKey(),
                    lastImportedPlayCount = plan.baselinePlayCount,
                    lastImportedSkipCount = plan.baselineSkipCount,
                    lastImportedAt        = importedAt,
                )
            )
        }

        BpstatApplyResult(
            tracksMatched           = matchedRows.size,
            tracksUpdated           = tracksUpdated,
            tracksSkippedNoNewStats = matchedRows.size - tracksUpdated,
            playsImported           = playsImported,
            unmatchedSkipped        = unmatchedCount,
        )
    }
}

// ── Mapping ───────────────────────────────────────────────────────────────────

private fun TrackStatsEntity.toDomain() = TrackStats(
    songId               = songId,
    contentUri           = contentUri,
    playCount            = playCount,
    skipCount            = skipCount,
    lastPlayedAt         = lastPlayedAt,
    totalListeningTimeMs = totalListeningTimeMs,
    isFavorite           = isFavorite,
)

private fun BlackPlayerStatImportRow.blackPlayerBpstatSourceKey(): String {
    val normalizedTitle = title.normalizedImportKeyPart()
    val normalizedArtist = artist.normalizedImportKeyPart()
    val normalizedAlbum = album.normalizedImportKeyPart()
    return buildString {
        append("title:")
        append(normalizedTitle.length)
        append(':')
        append(normalizedTitle)
        append("|artist:")
        append(normalizedArtist.length)
        append(':')
        append(normalizedArtist)
        append("|album:")
        append(normalizedAlbum.length)
        append(':')
        append(normalizedAlbum)
    }
}

private fun String.normalizedImportKeyPart(): String = trim().lowercase()
