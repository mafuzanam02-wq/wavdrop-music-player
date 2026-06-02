package com.launchpoint.wavdrop.data.stats

import com.launchpoint.wavdrop.data.grouping.AlbumGrouper
import com.launchpoint.wavdrop.data.grouping.ArtistGrouper
import com.launchpoint.wavdrop.data.local.entity.TrackListenEventEntity
import com.launchpoint.wavdrop.data.local.entity.TrackStatsEntity
import com.launchpoint.wavdrop.data.model.AlbumReportSummary
import com.launchpoint.wavdrop.data.model.ArtistReportSummary
import com.launchpoint.wavdrop.data.model.ListeningAnalyticsEmptyReason
import com.launchpoint.wavdrop.data.model.ListeningAnalyticsEmptyState
import com.launchpoint.wavdrop.data.model.ListeningPeriodRange
import com.launchpoint.wavdrop.data.model.ListeningPeriodSummary
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.model.SongStatsSummary
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Shared pure analytics layer for listening reports.
 *
 * Time-scoped analytics are event-backed only. Aggregate TrackStatsEntity rows are accepted by
 * [build] for a stable call shape, but are intentionally ignored so monthly and historical views
 * never fake activity from imported/all-time counters.
 */
object ListeningAnalyticsBuilder {

    private const val DEFAULT_TOP_LIST_LIMIT = 10

    @Suppress("UNUSED_PARAMETER")
    fun build(
        range: ListeningPeriodRange,
        songs: List<Song>,
        stats: List<TrackStatsEntity>,
        events: List<TrackListenEventEntity>,
        topListLimit: Int = DEFAULT_TOP_LIST_LIMIT,
    ): ListeningPeriodSummary {
        val songsById = songs.associateBy { it.id }
        val limit = topListLimit.coerceAtLeast(0)

        val periodEvents = events.filter { range.contains(it.occurredAt) }
        val playEvents = periodEvents.filter { it.eventType == TrackListenEventEntity.TYPE_PLAY }
        val skipEvents = periodEvents.filter { it.eventType == TrackListenEventEntity.TYPE_SKIP }
        val summaries = buildEventSongSummaries(songsById, playEvents, skipEvents)

        return buildSummary(
            range = range,
            summaries = summaries,
            totalPlayCount = playEvents.size,
            totalSkipCount = skipEvents.size,
            totalListeningTimeMs = playEvents.sumOf { it.listenedMs },
            dayAnalytics = buildDayAnalytics(playEvents, range),
            topListLimit = limit,
            emptyState = eventEmptyState(periodEvents, summaries),
        )
    }

    fun buildAllTimeAggregateFallback(
        songs: List<Song>,
        stats: List<TrackStatsEntity>,
        topListLimit: Int = DEFAULT_TOP_LIST_LIMIT,
        zone: ZoneId = ZoneId.systemDefault(),
    ): ListeningPeriodSummary {
        val songsById = songs.associateBy { it.id }
        val summaries = stats.mapNotNull { stat ->
            val song = songsById[stat.songId] ?: return@mapNotNull null
            SongStatsSummary(
                song = song,
                playCount = stat.playCount,
                skipCount = stat.skipCount,
                lastPlayedAt = stat.lastPlayedAt.takeIf { it > 0L },
                totalListeningTimeMs = stat.totalListeningTimeMs,
            )
        }
        val hasAggregateActivity = summaries.any {
            it.playCount > 0 || it.skipCount > 0 || it.totalListeningTimeMs > 0L
        }

        return buildSummary(
            range = ListeningPeriodRange.allTime(zone),
            summaries = summaries,
            totalPlayCount = summaries.sumOf { it.playCount },
            totalSkipCount = summaries.sumOf { it.skipCount },
            totalListeningTimeMs = summaries.sumOf { it.totalListeningTimeMs },
            dayAnalytics = DayAnalytics.empty(),
            topListLimit = topListLimit.coerceAtLeast(0),
            emptyState = if (hasAggregateActivity) {
                ListeningAnalyticsEmptyState(
                    reason = ListeningAnalyticsEmptyReason.HAS_ACTIVITY,
                    hasEventsInRange = false,
                    hasMatchedLibraryItems = true,
                )
            } else {
                ListeningAnalyticsEmptyState(
                    reason = ListeningAnalyticsEmptyReason.NO_AGGREGATE_ACTIVITY,
                    hasEventsInRange = false,
                    hasMatchedLibraryItems = false,
                )
            },
        )
    }

    private fun buildEventSongSummaries(
        songsById: Map<Long, Song>,
        playEvents: List<TrackListenEventEntity>,
        skipEvents: List<TrackListenEventEntity>,
    ): List<SongStatsSummary> {
        val activeSongIds = (playEvents.map { it.songId } + skipEvents.map { it.songId }).toSet()
        val songPlayCounts = playEvents.groupBy { it.songId }.mapValues { it.value.size }
        val songSkipCounts = skipEvents.groupBy { it.songId }.mapValues { it.value.size }
        val songListeningMs = playEvents.groupBy { it.songId }
            .mapValues { (_, events) -> events.sumOf { it.listenedMs } }
        val songLastPlayed = playEvents.groupBy { it.songId }
            .mapValues { (_, events) -> events.maxOf { it.occurredAt } }

        return activeSongIds.mapNotNull { songId ->
            val song = songsById[songId] ?: return@mapNotNull null
            SongStatsSummary(
                song = song,
                playCount = songPlayCounts[songId] ?: 0,
                skipCount = songSkipCounts[songId] ?: 0,
                lastPlayedAt = songLastPlayed[songId],
                totalListeningTimeMs = songListeningMs[songId] ?: 0L,
            )
        }
    }

    private fun buildSummary(
        range: ListeningPeriodRange,
        summaries: List<SongStatsSummary>,
        totalPlayCount: Int,
        totalSkipCount: Int,
        totalListeningTimeMs: Long,
        dayAnalytics: DayAnalytics,
        topListLimit: Int,
        emptyState: ListeningAnalyticsEmptyState,
    ): ListeningPeriodSummary {
        val artistSummaries = buildArtistSummaries(summaries)
        val albumSummaries = buildAlbumSummaries(summaries)

        return ListeningPeriodSummary(
            range = range,
            totalPlayCount = totalPlayCount,
            totalSkipCount = totalSkipCount,
            totalListeningTimeMs = totalListeningTimeMs,
            tracksPlayedCount = summaries.count { it.playCount > 0 },
            artistsPlayedCount = artistSummaries.count { it.playCount > 0 },
            albumsPlayedCount = albumSummaries.count { it.playCount > 0 },
            topSongs = summaries
                .filter { it.playCount > 0 }
                .sortedWith(songPlayComparator)
                .take(topListLimit),
            topArtists = artistSummaries
                .filter { it.playCount > 0 }
                .sortedWith(artistPlayComparator)
                .take(topListLimit),
            topAlbums = albumSummaries
                .filter { it.playCount > 0 }
                .sortedWith(albumPlayComparator)
                .take(topListLimit),
            mostSkippedTrack = summaries
                .filter { it.skipCount > 0 }
                .sortedWith(songSkipComparator)
                .firstOrNull(),
            recentlyPlayed = summaries
                .filter { it.lastPlayedAt != null }
                .sortedByDescending { it.lastPlayedAt ?: 0L }
                .take(topListLimit),
            listeningDaysCount = dayAnalytics.listeningDaysCount,
            busiestDay = dayAnalytics.busiestDay,
            busiestDayPlayCount = dayAnalytics.busiestDayPlayCount,
            averagePlaysPerActiveDay = dayAnalytics.averagePlaysPerActiveDay,
            emptyState = emptyState,
        )
    }

    private fun buildArtistSummaries(summaries: List<SongStatsSummary>): List<ArtistReportSummary> =
        summaries
            .groupBy { ArtistGrouper.artistKey(it.song) }
            .map { (artistKey, artistSongs) ->
                ArtistReportSummary(
                    artistKey = artistKey,
                    songCount = artistSongs.size,
                    albumCount = artistSongs.map { AlbumGrouper.albumKey(it.song) }.toSet().size,
                    playCount = artistSongs.sumOf { it.playCount },
                    skipCount = artistSongs.sumOf { it.skipCount },
                    totalListeningTimeMs = artistSongs.sumOf { it.totalListeningTimeMs },
                    lastPlayedAt = artistSongs.mapNotNull { it.lastPlayedAt }.maxOrNull(),
                )
            }

    private fun buildAlbumSummaries(summaries: List<SongStatsSummary>): List<AlbumReportSummary> =
        summaries
            .groupBy { AlbumGrouper.albumKey(it.song) }
            .map { (albumKey, albumSongs) ->
                AlbumReportSummary(
                    albumKey = albumKey,
                    artist = primaryArtist(albumSongs.map { it.song }),
                    songCount = albumSongs.size,
                    playCount = albumSongs.sumOf { it.playCount },
                    skipCount = albumSongs.sumOf { it.skipCount },
                    totalListeningTimeMs = albumSongs.sumOf { it.totalListeningTimeMs },
                    lastPlayedAt = albumSongs.mapNotNull { it.lastPlayedAt }.maxOrNull(),
                )
            }

    private fun buildDayAnalytics(
        playEvents: List<TrackListenEventEntity>,
        range: ListeningPeriodRange,
    ): DayAnalytics {
        val playsByDay = playEvents
            .map { event -> Instant.ofEpochMilli(event.occurredAt).atZone(range.zone).toLocalDate() }
            .groupBy { it }
            .mapValues { it.value.size }
        val busiestDayEntry = playsByDay.maxByOrNull { it.value }
        val listeningDaysCount = playsByDay.size
        return DayAnalytics(
            listeningDaysCount = listeningDaysCount,
            busiestDay = busiestDayEntry?.key,
            busiestDayPlayCount = busiestDayEntry?.value ?: 0,
            averagePlaysPerActiveDay = if (listeningDaysCount > 0) {
                playEvents.size.toDouble() / listeningDaysCount
            } else {
                0.0
            },
        )
    }

    private fun eventEmptyState(
        periodEvents: List<TrackListenEventEntity>,
        summaries: List<SongStatsSummary>,
    ): ListeningAnalyticsEmptyState = when {
        periodEvents.isEmpty() -> ListeningAnalyticsEmptyState(
            reason = ListeningAnalyticsEmptyReason.NO_EVENTS_IN_RANGE,
            hasEventsInRange = false,
            hasMatchedLibraryItems = false,
        )
        summaries.isEmpty() -> ListeningAnalyticsEmptyState(
            reason = ListeningAnalyticsEmptyReason.ONLY_ORPHAN_EVENTS,
            hasEventsInRange = true,
            hasMatchedLibraryItems = false,
        )
        else -> ListeningAnalyticsEmptyState(
            reason = ListeningAnalyticsEmptyReason.HAS_ACTIVITY,
            hasEventsInRange = true,
            hasMatchedLibraryItems = true,
        )
    }

    private fun primaryArtist(songs: List<Song>): String =
        songs
            .groupBy { ArtistGrouper.artistKey(it) }
            .maxByOrNull { it.value.size }
            ?.key
            ?: "Unknown Artist"

    private val songPlayComparator =
        compareByDescending<SongStatsSummary> { it.playCount }
            .thenBy { it.song.title.lowercase() }
            .thenBy { it.song.id }

    private val songSkipComparator =
        compareByDescending<SongStatsSummary> { it.skipCount }
            .thenBy { it.song.title.lowercase() }
            .thenBy { it.song.id }

    private val artistPlayComparator =
        compareByDescending<ArtistReportSummary> { it.playCount }
            .thenBy { it.artistKey.lowercase() }

    private val albumPlayComparator =
        compareByDescending<AlbumReportSummary> { it.playCount }
            .thenBy { it.albumKey.lowercase() }

    private data class DayAnalytics(
        val listeningDaysCount: Int,
        val busiestDay: LocalDate?,
        val busiestDayPlayCount: Int,
        val averagePlaysPerActiveDay: Double,
    ) {
        companion object {
            fun empty(): DayAnalytics = DayAnalytics(
                listeningDaysCount = 0,
                busiestDay = null,
                busiestDayPlayCount = 0,
                averagePlaysPerActiveDay = 0.0,
            )
        }
    }
}
