package com.launchpoint.wavdrop.data.repository

import com.launchpoint.wavdrop.data.model.SmartCollection
import com.launchpoint.wavdrop.data.model.SmartCollectionType
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.model.SongCompletionSummary
import com.launchpoint.wavdrop.data.local.entity.TrackStatsEntity
import com.launchpoint.wavdrop.data.smart.SmartCollectionBuilder
import com.launchpoint.wavdrop.data.smart.SmartCollectionSongResult
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SmartCollectionRepository @Inject constructor(
    private val songRepository: SongRepository,
    private val statsRepository: StatsRepository,
) {
    fun observeSmartCollections(): Flow<List<SmartCollection>> =
        observeSmartCollectionsFromInputs(
            songRepository.songs,
            statsRepository.allTrackStatsEntities(),
            statsRepository.observeCompletionSummaries(),
            localDayRefreshFlow(),
        )
            .flowOn(Dispatchers.Default)

    fun observeSongsForCollection(type: SmartCollectionType): Flow<List<Song>> =
        observeSongResultForCollection(type).map { it.songs }

    fun observeSongResultForCollection(type: SmartCollectionType): Flow<SmartCollectionSongResult> =
        combine(
            songRepository.songs,
            statsRepository.allTrackStatsEntities(),
            statsRepository.observeCompletionSummaries(),
            localDayRefreshFlow(),
        ) { songs, stats, completions, nowMs ->
            SmartCollectionBuilder.songsResultFor(type, songs, stats, completions, nowMs)
        }
            .flowOn(Dispatchers.Default)
}

internal fun observeSmartCollectionsFromInputs(
    songs: Flow<List<Song>>,
    stats: Flow<List<TrackStatsEntity>>,
    completions: Flow<List<SongCompletionSummary>>,
    dayRefresh: Flow<Long>,
): Flow<List<SmartCollection>> =
    combine(songs, stats, completions, dayRefresh) { currentSongs, currentStats, currentCompletions, nowMs ->
        SmartCollectionBuilder.build(
            songs = currentSongs,
            stats = currentStats,
            completionSummaries = currentCompletions,
            nowMs = nowMs,
        )
    }

internal fun localDayRefreshFlow(
    zone: ZoneId = ZoneId.systemDefault(),
    nowMs: () -> Long = System::currentTimeMillis,
    wait: suspend (Long) -> Unit = { delay(it) },
): Flow<Long> = flow {
    while (currentCoroutineContext().isActive) {
        val currentTimeMs = nowMs()
        emit(currentTimeMs)
        wait(millisUntilNextLocalDay(currentTimeMs, zone))
    }
}

internal fun millisUntilNextLocalDay(nowMs: Long, zone: ZoneId): Long {
    val now = Instant.ofEpochMilli(nowMs).atZone(zone)
    val nextDayStart = now.toLocalDate().plusDays(1).atStartOfDay(zone)
    return (nextDayStart.toInstant().toEpochMilli() - nowMs).coerceAtLeast(1L)
}
