package com.launchpoint.wavdrop.data.smart

import com.launchpoint.wavdrop.data.local.entity.TrackStatsEntity
import com.launchpoint.wavdrop.data.model.SmartCollection
import com.launchpoint.wavdrop.data.model.SmartCollectionType
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.model.SongCompletionSummary
import com.launchpoint.wavdrop.data.smart.SmartCollectionBuilder.Dependency

/**
 * Dependency-aware assembly of the all-collections list (WC-06). Holds the latest of each upstream input and a per-type cache; an
 * upstream emission re-evaluates ONLY the dependency families it can affect ([SmartCollectionBuilder.dependencyOf]):
 *  - songs changed      -> every family (and both prepared live-only maps)
 *  - stats changed      -> STATS + STATS_AND_TIME
 *  - completions changed-> COMPLETION
 *  - day tick           -> STATS_AND_TIME (Forgotten Gems) only
 * The prepared live-only stats/completion maps are built once per relevant upstream emission and shared by every type. Nothing is
 * emitted until all four inputs have arrived once (as with the combine it replaces). The result is every non-empty collection in
 * canonical enum order. Single-threaded: callers must serialize events. [onEvaluate] is a test hook invoked once per evaluated type.
 */
internal class SmartCollectionsAssembler(
    private val onEvaluate: (SmartCollectionType) -> Unit = {},
) {
    private var songs: List<Song>? = null
    private var rawStats: List<TrackStatsEntity>? = null
    private var rawCompletions: List<SongCompletionSummary>? = null
    private var nowMs: Long? = null

    private var statsById: Map<Long, TrackStatsEntity> = emptyMap()
    private var completionById: Map<Long, SongCompletionSummary> = emptyMap()
    private var statsStale = true
    private var completionsStale = true

    // Everything is dirty until the first complete evaluation.
    private val dirty: MutableSet<Dependency> = Dependency.values().toMutableSet()
    private val cache = HashMap<SmartCollectionType, SmartCollection?>()

    fun onSongs(value: List<Song>): List<SmartCollection>? {
        songs = value
        statsStale = true
        completionsStale = true
        dirty.addAll(Dependency.values())
        return flush()
    }

    fun onStats(value: List<TrackStatsEntity>): List<SmartCollection>? {
        rawStats = value
        statsStale = true
        dirty.add(Dependency.STATS)
        dirty.add(Dependency.STATS_AND_TIME)
        return flush()
    }

    fun onCompletions(value: List<SongCompletionSummary>): List<SmartCollection>? {
        rawCompletions = value
        completionsStale = true
        dirty.add(Dependency.COMPLETION)
        return flush()
    }

    fun onDay(value: Long): List<SmartCollection>? {
        nowMs = value
        dirty.add(Dependency.STATS_AND_TIME)
        return flush()
    }

    private fun flush(): List<SmartCollection>? {
        val currentSongs = songs ?: return null
        val currentStats = rawStats ?: return null
        val currentCompletions = rawCompletions ?: return null
        val currentNow = nowMs ?: return null

        if (statsStale) { statsById = SmartCollectionBuilder.liveStatsById(currentSongs, currentStats); statsStale = false }
        if (completionsStale) { completionById = SmartCollectionBuilder.liveCompletionsById(currentSongs, currentCompletions); completionsStale = false }

        for (dependency in dirty) {
            for (type in SmartCollectionBuilder.typesWith(dependency)) {
                onEvaluate(type)
                cache[type] = SmartCollectionBuilder.summaryFor(
                    type,
                    SmartCollectionBuilder.resultFor(type, currentSongs, statsById, completionById, currentNow),
                )
            }
        }
        dirty.clear()
        return SmartCollectionType.values().mapNotNull { cache[it] }
    }
}
