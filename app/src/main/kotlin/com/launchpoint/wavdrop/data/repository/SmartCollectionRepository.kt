package com.launchpoint.wavdrop.data.repository

import com.launchpoint.wavdrop.data.model.SmartCollection
import com.launchpoint.wavdrop.data.model.SmartCollectionType
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.model.SongCompletionSummary
import com.launchpoint.wavdrop.data.local.entity.TrackStatsEntity
import com.launchpoint.wavdrop.data.smart.SmartCollectionBuilder
import com.launchpoint.wavdrop.data.smart.SmartCollectionBuilder.Dependency
import com.launchpoint.wavdrop.data.smart.SmartCollectionSongResult
import com.launchpoint.wavdrop.data.smart.SmartCollectionsAssembler
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
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.isActive
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SmartCollectionRepository @Inject constructor(
    private val songRepository: SongRepository,
    private val statsRepository: StatsRepository,
) {
    /**
     * All non-empty collections in canonical order. Dependency-aware (WC-06): a stats, completion or day emission re-evaluates only
     * the collection families that read it. Each collector builds its own pipeline (there is no injectable application scope to
     * share one under, and a ViewModel-owned global would be the wrong lifetime).
     */
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

    /** One collection's result; subscribes ONLY to the inputs that collection's rules read. */
    fun observeSongResultForCollection(type: SmartCollectionType): Flow<SmartCollectionSongResult> =
        smartCollectionResultFlow(
            type = type,
            songs = songRepository.songs,
            stats = { statsRepository.allTrackStatsEntities() },
            completions = { statsRepository.observeCompletionSummaries() },
            dayRefresh = { localDayRefreshFlow() },
        )
            .flowOn(Dispatchers.Default)
}

private sealed interface SmartInputEvent {
    class Songs(val value: List<Song>) : SmartInputEvent
    class Stats(val value: List<TrackStatsEntity>) : SmartInputEvent
    class Completions(val value: List<SongCompletionSummary>) : SmartInputEvent
    class Day(val value: Long) : SmartInputEvent
}

internal fun observeSmartCollectionsFromInputs(
    songs: Flow<List<Song>>,
    stats: Flow<List<TrackStatsEntity>>,
    completions: Flow<List<SongCompletionSummary>>,
    dayRefresh: Flow<Long>,
    onEvaluate: (SmartCollectionType) -> Unit = {},
): Flow<List<SmartCollection>> = flow {
    val assembler = SmartCollectionsAssembler(onEvaluate)
    merge(
        songs.map<List<Song>, SmartInputEvent> { SmartInputEvent.Songs(it) },
        stats.map { SmartInputEvent.Stats(it) },
        completions.map { SmartInputEvent.Completions(it) },
        dayRefresh.map { SmartInputEvent.Day(it) },
    )
        .mapNotNull { event ->
            when (event) {
                is SmartInputEvent.Songs -> assembler.onSongs(event.value)
                is SmartInputEvent.Stats -> assembler.onStats(event.value)
                is SmartInputEvent.Completions -> assembler.onCompletions(event.value)
                is SmartInputEvent.Day -> assembler.onDay(event.value)
            }
        }
        .collect { emit(it) }
}

/**
 * Single-collection flow (WC-06). The input flows are lazy providers so a collection never even constructs, let alone subscribes
 * to, an input its rules do not read (e.g. LONG_TRACKS: songs only; FAVORITES: songs + stats; ALWAYS_FINISH: songs + completions).
 */
internal fun smartCollectionResultFlow(
    type: SmartCollectionType,
    songs: Flow<List<Song>>,
    stats: () -> Flow<List<TrackStatsEntity>>,
    completions: () -> Flow<List<SongCompletionSummary>>,
    dayRefresh: () -> Flow<Long>,
    onEvaluate: (SmartCollectionType) -> Unit = {},
): Flow<SmartCollectionSongResult> = when (SmartCollectionBuilder.dependencyOf(type)) {
    Dependency.SONGS_ONLY -> songs.map { currentSongs ->
        onEvaluate(type)
        SmartCollectionBuilder.resultFor(type, currentSongs, emptyMap(), emptyMap(), 0L)
    }
    Dependency.STATS -> combine(songs, stats()) { currentSongs, currentStats ->
        onEvaluate(type)
        SmartCollectionBuilder.resultFor(type, currentSongs, SmartCollectionBuilder.liveStatsById(currentSongs, currentStats), emptyMap(), 0L)
    }
    Dependency.STATS_AND_TIME -> combine(songs, stats(), dayRefresh()) { currentSongs, currentStats, nowMs ->
        onEvaluate(type)
        SmartCollectionBuilder.resultFor(type, currentSongs, SmartCollectionBuilder.liveStatsById(currentSongs, currentStats), emptyMap(), nowMs)
    }
    Dependency.COMPLETION -> combine(songs, completions()) { currentSongs, currentCompletions ->
        onEvaluate(type)
        SmartCollectionBuilder.resultFor(type, currentSongs, emptyMap(), SmartCollectionBuilder.liveCompletionsById(currentSongs, currentCompletions), 0L)
    }
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
