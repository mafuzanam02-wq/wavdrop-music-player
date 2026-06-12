package com.launchpoint.wavdrop.data.repository

import androidx.room.withTransaction
import com.launchpoint.wavdrop.data.backup.StatsImportMerger
import com.launchpoint.wavdrop.data.legacy.BpstatApplyResult
import com.launchpoint.wavdrop.data.legacy.BlackPlayerStatImportRow
import com.launchpoint.wavdrop.data.legacy.ImportSourceTypes
import com.launchpoint.wavdrop.data.local.WavdropDatabase
import com.launchpoint.wavdrop.data.local.dao.ImportBaselineDao
import com.launchpoint.wavdrop.data.local.dao.TrackListenEventDao
import com.launchpoint.wavdrop.data.local.dao.TrackStatsDao
import com.launchpoint.wavdrop.data.local.entity.ImportBaselineEntity
import com.launchpoint.wavdrop.data.local.entity.TrackListenEventEntity
import com.launchpoint.wavdrop.data.local.entity.TrackStatsEntity
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.model.TrackStats
import kotlinx.coroutines.flow.Flow
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
                )
            )
        }
    }

    /**
     * Records a skip: updates the aggregate row and appends a SKIP event.
     *
     * [durationMs] is the track's total duration — 0 if unknown. Used only for the event record.
     */
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

    /** All listen events (PLAY + SKIP), most recent first. Used by Monthly Reports / analytics. */
    fun allListenEvents(): Flow<List<TrackListenEventEntity>> = listenEventDao.observeAll()

    fun favoriteSongIds(): Flow<Set<Long>> =
        dao.favoriteSongIds().map { it.toSet() }

    fun observeStats(songId: Long): Flow<TrackStats?> =
        dao.observeStatsBySongId(songId).map { it?.toDomain() }

    fun allPlayCounts(): Flow<Map<Long, Int>> =
        dao.getAllStats().map { list -> list.associate { it.songId to it.playCount } }

    fun allTrackStatsEntities(): Flow<List<TrackStatsEntity>> =
        dao.getAllStats()

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
     * Merge strategy: MAX-reconciliation.
     * - newPlayCount  = MAX(current, imported)
     * - newSkipCount  = MAX(current, imported)
     * - lastPlayedAt  = MAX(current, imported)
     *
     * This is idempotent — importing the same file twice produces no change on the
     * second import. Local stats that are already higher are never reduced.
     * totalListeningTimeMs is not available from BlackPlayer and is left unchanged.
     *
     * Import baselines are still written for historical tracking but are no longer
     * required for idempotency (MAX semantics guarantee that).
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
        var skipsImported = 0L
        val importedAt = System.currentTimeMillis()

        // Pre-load all current stats to compute reporting deltas without N individual queries.
        val currentStatsById = dao.getAllStatsSnapshot().associateBy { it.songId }

        for ((song, row) in matchedRows) {
            val current = currentStatsById[song.id]

            // Ensure a stats row exists before updating.
            dao.insertIfAbsent(TrackStatsEntity(songId = song.id, contentUri = song.uri))

            // MAX-reconciliation: each counter is set to MAX(current, imported).
            // Pass 0 for totalListeningTimeMs — BlackPlayer does not provide listening time,
            // so MAX(current, 0) = current, leaving it unchanged.
            dao.mergeMaxStats(
                songId                  = song.id,
                importedPlayCount       = row.playCount,
                importedSkipCount       = row.skipCount,
                importedListeningTimeMs = 0L,
                importedLastPlayedAt    = row.lastPlayedMs,
            )

            val effect = StatsImportMerger.computeEffect(
                currentPlayCount        = current?.playCount ?: 0,
                currentSkipCount        = current?.skipCount ?: 0,
                currentListeningTimeMs  = current?.totalListeningTimeMs ?: 0L,
                importedPlayCount       = row.playCount,
                importedSkipCount       = row.skipCount,
                importedListeningTimeMs = 0L,
            )
            if (effect.anyUpdated) {
                tracksUpdated++
                playsImported += effect.playDelta
                skipsImported += effect.skipDelta
            }

            // Write baseline for historical tracking.
            importBaselineDao.upsertBaseline(
                ImportBaselineEntity(
                    songId                = song.id,
                    sourceType            = ImportSourceTypes.BLACKPLAYER_BPSTAT,
                    sourceKey             = row.blackPlayerBpstatSourceKey(),
                    lastImportedPlayCount = row.playCount,
                    lastImportedSkipCount = row.skipCount,
                    lastImportedAt        = importedAt,
                )
            )
        }

        BpstatApplyResult(
            tracksMatched           = matchedRows.size,
            tracksUpdated           = tracksUpdated,
            tracksSkippedNoNewStats = matchedRows.size - tracksUpdated,
            playsImported           = playsImported,
            skipsImported           = skipsImported,
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
