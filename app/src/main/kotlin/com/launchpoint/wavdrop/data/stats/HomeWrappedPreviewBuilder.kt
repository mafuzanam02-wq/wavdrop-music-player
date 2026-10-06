package com.launchpoint.wavdrop.data.stats

import com.launchpoint.wavdrop.data.grouping.ArtistGrouper
import com.launchpoint.wavdrop.data.model.HomeWrappedPreview
import com.launchpoint.wavdrop.data.model.HomeWrappedSongActivity
import com.launchpoint.wavdrop.data.model.Song

/**
 * Narrow replacement for building a complete WrappedSummary just to feed the Home card (WC-05). Reproduces the old
 * `WrappedBuilder.buildYear(...).takeIf { hasActivity && !emptyState.isEmpty }` result for the fields Home shows, preserving
 * Wrapped's existing ranking semantics:
 *  - a preview exists iff at least one activity row belongs to a live song (skip-only live activity counts; orphan-only does not);
 *  - the total is the all-event PLAY total (orphan PLAYs included);
 *  - top song ranks live-song PLAY counts with ListeningAnalyticsBuilder's comparator (plays DESC, lowercase title, id);
 *  - top artist groups by [ArtistGrouper.artistKey] in Kotlin and uses ListeningAnalyticsBuilder's `artistPlayComparator`
 *    (plays DESC, lowercase key) — a STABLE sort over the artist summaries. Only when that comparator returns equality (different
 *    keys equal under lowercase(), e.g. "Artist" / "artist", with the same plays) did the old result depend on the pre-sort order:
 *    summaries followed the PLAY events' `occurredAt DESC` order, so an artist sat at the position of its most recently played song.
 *    That order is reproduced here from each song's latest PLAY timestamp, used ONLY as that final tie-break. Two artists whose
 *    latest PLAYs share the exact same timestamp were unspecified before (no secondary SQL order) and stay unspecified here
 *    (songId order of the rows).
 */
object HomeWrappedPreviewBuilder {

    fun build(year: Int, songsById: Map<Long, Song>, activity: List<HomeWrappedSongActivity>): HomeWrappedPreview? {
        val live = activity.mapNotNull { row -> songsById[row.songId]?.let { it to row } }
        if (live.isEmpty()) return null

        val topSong = live
            .filter { (_, row) -> row.playCount > 0 }
            .minWithOrNull(
                compareByDescending<Pair<Song, HomeWrappedSongActivity>> { it.second.playCount }
                    .thenBy { it.first.title.lowercase() }
                    .thenBy { it.first.id },
            )?.first

        val topArtistKey = live
            .filter { (_, row) -> row.playCount > 0 }
            .groupBy { ArtistGrouper.artistKey(it.first) }
            .map { (key, rows) ->
                ArtistPlays(
                    key = key,
                    playCount = rows.sumOf { it.second.playCount },
                    latestPlayAt = rows.maxOf { it.second.latestPlayAt ?: Long.MIN_VALUE },
                )
            }
            .sortedWith(
                compareByDescending<ArtistPlays> { it.playCount }
                    .thenBy { it.key.lowercase() }
                    .thenByDescending { it.latestPlayAt }, // reproduces the old stable pre-sort order; never ranks ahead of the above
            )
            .firstOrNull()?.key

        return HomeWrappedPreview(
            year = year,
            displayLabel = year.toString(), // == WrappedPeriod.Yearly.displayLabel
            totalPlayCount = live.first().second.totalPlayCount,
            topArtistKey = topArtistKey,
            topSong = topSong,
        )
    }

    private class ArtistPlays(val key: String, val playCount: Int, val latestPlayAt: Long)
}
