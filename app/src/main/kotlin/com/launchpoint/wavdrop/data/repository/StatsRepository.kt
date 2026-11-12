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
import com.launchpoint.wavdrop.data.model.HomeWrappedSongActivity
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.model.SongCompletionSummary
import com.launchpoint.wavdrop.data.model.TrackStats
import com.launchpoint.wavdrop.data.stats.InsightsPlayActivity
import com.launchpoint.wavdrop.data.stats.InsightsPlayActivityReader
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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

    // P5-STATS-19C: the former non-idempotent recordPlay / recordSkip / recordListenStart entry points were removed. Each call minted a
    // fresh id, so repeating one counted twice, and nothing in production used them. BlackPlayer imports do NOT write events - they go
    // through [applyBpstatImport] / TrackStatsDao.mergeImportedStats.

    /**
     * P5-STATS-19B - idempotent PLAY. In ONE Room transaction: look the event id up; an equivalent stored event means the occurrence is
     * already applied (nothing changes); the same id with a different payload is rejected with [StatsEventConflictException] (nothing is
     * modified); an absent id inserts the event and updates the aggregate exactly once. Room serialises write transactions, so competing
     * writers of one id cannot both pass the lookup.
     *
     * The lookup is by `eventId`, which has no index on schema 13 (a full scan of `track_listen_events`); see [findStoredByEventId].
     */
    override suspend fun recordPlayOccurrence(
        occurrence: PlayOccurrence,
        songId: Long,
        contentUri: String,
        listenedMs: Long,
        durationMs: Long,
    ): StatsWriteResult = db.withTransaction {
        val incoming = occurrenceEvent(occurrence, songId, TrackListenEventEntity.TYPE_PLAY, listenedMs, durationMs)
        if (isAlreadyApplied(incoming)) return@withTransaction StatsWriteResult.ALREADY_APPLIED
        dao.insertIfAbsent(TrackStatsEntity(songId = songId, contentUri = contentUri))
        dao.incrementPlayCount(songId, nowMs = occurrence.occurredAtMs, listenedMs = listenedMs)
        listenEventDao.insert(incoming)
        StatsWriteResult.APPLIED
    }

    /** `lastListenedAt` is set with MAX(), so a delayed older write can never move it backwards and repeating it is harmless. */
    override suspend fun recordListenStartAt(occurredAtMs: Long, songId: Long, contentUri: String) {
        db.withTransaction {
            dao.insertIfAbsent(TrackStatsEntity(songId = songId, contentUri = contentUri))
            dao.updateLastListenedAt(songId, occurredAtMs)
        }
    }

    /** P5-STATS-19B - idempotent SKIP, same contract as [recordPlayOccurrence]. */
    override suspend fun recordSkipOccurrence(
        occurrence: PlayOccurrence,
        songId: Long,
        contentUri: String,
        durationMs: Long,
    ): StatsWriteResult = db.withTransaction {
        val incoming = occurrenceEvent(occurrence, songId, TrackListenEventEntity.TYPE_SKIP, 0L, durationMs)
        if (isAlreadyApplied(incoming)) return@withTransaction StatsWriteResult.ALREADY_APPLIED
        dao.insertIfAbsent(TrackStatsEntity(songId = songId, contentUri = contentUri))
        dao.incrementSkipCount(songId)
        listenEventDao.insert(incoming)
        StatsWriteResult.APPLIED
    }

    private fun occurrenceEvent(occurrence: PlayOccurrence, songId: Long, type: String, listenedMs: Long, durationMs: Long) =
        TrackListenEventEntity(
            songId = songId,
            eventType = type,
            occurredAt = occurrence.occurredAtMs,
            listenedMs = listenedMs,
            durationMs = durationMs,
            source = TrackListenEventEntity.SOURCE_WAVDROP_PLAYBACK,
            // Stable per-occurrence id, created by the caller at detection time (contract §9.2).
            eventId = occurrence.eventId,
        )

    /**
     * Event equivalence = same song, type, occurrence time, listened and duration. `source` is deliberately NOT compared: an event that
     * was restored on another device keeps its id but gets a different source. Rows with a null `eventId` can never match.
     */
    private fun TrackListenEventEntity.sameOccurrenceAs(o: TrackListenEventEntity) =
        songId == o.songId && eventType == o.eventType && occurredAt == o.occurredAt && listenedMs == o.listenedMs && durationMs == o.durationMs

    /** True when an equivalent event is stored; throws [StatsEventConflictException] if ANY stored row of that id differs. */
    private suspend fun isAlreadyApplied(incoming: TrackListenEventEntity): Boolean {
        val stored = findStoredByEventId(incoming.eventId!!)
        if (stored.isEmpty()) return false
        if (stored.any { !it.sameOccurrenceAs(incoming) }) {
            throw StatsEventConflictException("Listen event id ${incoming.eventId} is already stored with a different payload; nothing was written.")
        }
        return true
    }

    private suspend fun findStoredByEventId(eventId: String): List<TrackListenEventEntity> = listenEventDao.getByEventId(eventId)

    suspend fun toggleFavorite(songId: Long, contentUri: String) {
        db.withTransaction {
            dao.insertIfAbsent(TrackStatsEntity(songId = songId, contentUri = contentUri))
            dao.toggleFavorite(songId)
        }
    }

    // ── Read ops ──────────────────────────────────────────────────────────────

    /**
     * Every listen event ENTITY, most recent first. Only for genuine full-history entity consumers: ordinary analytics screens
     * must not subscribe to it (WC-02). Use [listenEventCount], [analyticsEventTimestamps], [playActivity] or
     * [listenEventsInRange] for counts, timestamps or one selected period.
     */
    fun allListenEvents(): Flow<List<TrackListenEventEntity>> = listenEventDao.observeAll()

    /** Number of listen-event rows (all types) without loading them. */
    fun listenEventCount(): Flow<Int> = listenEventDao.observeCount()

    /** occurredAt of every PLAY and SKIP event (timestamps only): feeds available months/years. */
    fun analyticsEventTimestamps(): Flow<List<Long>> = listenEventDao.observeAnalyticsEventTimestamps()

    /**
     * Bounded most-active weekday/hour + current-year play-date summary for Insights and Statistics (WC-09). The PLAY history is
     * streamed through a cursor into a small accumulator, so no per-event list is ever built (memory is bounded by 7 + 24 buckets and
     * at most 366 dates; the scan itself is still O(N) over the PLAY rows). Re-computes whenever the event table changes (including
     * restored historical events, which a MAX(occurredAt) signal would miss) and cancels an in-flight scan on a newer change.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun playActivity(zone: ZoneId = ZoneId.systemDefault()): Flow<InsightsPlayActivity> =
        listenEventDao.observeCount().mapLatest {
            withContext(Dispatchers.IO) {
                listenEventDao.playTimestampCursor().use { cursor ->
                    InsightsPlayActivityReader.reduce(cursor, zone) { ensureActive() }
                }
            }
        }

    /** Latest PLAY/SKIP event timestamp used to select the bounded Home Wrapped preview year. */
    fun latestAnalyticsEventAt(): Flow<Long?> = listenEventDao.observeLatestAnalyticsEventAt()

    /** Lightweight per-live-song PLAY/SKIP aggregate for the Home Wrapped card over an inclusive window (WC-05). */
    fun homeWrappedActivity(fromMs: Long, toMs: Long): Flow<List<HomeWrappedSongActivity>> =
        listenEventDao.observeHomeWrappedActivity(fromMs, toMs)

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
