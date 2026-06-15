package com.launchpoint.wavdrop.data.stats

import com.launchpoint.wavdrop.data.grouping.AlbumGrouper
import com.launchpoint.wavdrop.data.local.entity.TrackStatsEntity
import com.launchpoint.wavdrop.data.model.ArtistAlbumInsight
import com.launchpoint.wavdrop.data.model.ArtistInsightsSummary
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.model.SongStatsSummary

object ArtistInsightsBuilder {

    fun build(
        songs: List<Song>,
        stats: List<TrackStatsEntity>,
    ): ArtistInsightsSummary {
        val statsBySongId = stats.associateBy { it.songId }
        val songSummaries = songs.map { song ->
            val stat = statsBySongId[song.id]
            SongStatsSummary(
                song = song,
                playCount = stat?.playCount ?: 0,
                skipCount = stat?.skipCount ?: 0,
                lastPlayedAt = stat?.lastPlayedAt?.takeIf { it > 0L },
                totalListeningTimeMs = ListeningTimeRules.effectiveListeningTimeMs(
                    playCount = stat?.playCount ?: 0,
                    durationMs = song.duration,
                    totalListeningTimeMs = stat?.totalListeningTimeMs ?: 0L,
                ),
            )
        }

        return ArtistInsightsSummary(
            totalSongs = songs.size,
            totalAlbums = songs.map { AlbumGrouper.albumKey(it) }.toSet().size,
            totalPlayCount = songSummaries.sumOf { it.playCount },
            totalSkipCount = songSummaries.sumOf { it.skipCount },
            totalListeningTimeMs = songSummaries.sumOf { it.totalListeningTimeMs },
            lastPlayedAt = songSummaries.mapNotNull { it.lastPlayedAt }.maxOrNull(),
            topSongs = songSummaries
                .filter { it.playCount > 0 }
                .sortedWith(compareByDescending<SongStatsSummary> { it.playCount }
                    .thenBy { it.song.title.lowercase() })
                .take(INSIGHT_LIST_LIMIT),
            topAlbums = buildTopAlbums(songSummaries),
            recentActivity = songSummaries
                .filter { it.lastPlayedAt != null }
                .sortedByDescending { it.lastPlayedAt ?: 0L }
                .take(INSIGHT_LIST_LIMIT),
        )
    }

    private fun buildTopAlbums(summaries: List<SongStatsSummary>): List<ArtistAlbumInsight> =
        summaries
            .groupBy { AlbumGrouper.albumKey(it.song) }
            .map { (albumKey, albumSongs) ->
                ArtistAlbumInsight(
                    albumKey = albumKey,
                    songCount = albumSongs.size,
                    playCount = albumSongs.sumOf { it.playCount },
                    skipCount = albumSongs.sumOf { it.skipCount },
                    totalListeningTimeMs = albumSongs.sumOf { it.totalListeningTimeMs },
                    lastPlayedAt = albumSongs.mapNotNull { it.lastPlayedAt }.maxOrNull(),
                )
            }
            .filter { it.playCount > 0 }
            .sortedWith(compareByDescending<ArtistAlbumInsight> { it.playCount }
                .thenBy { it.albumKey.lowercase() })
            .take(INSIGHT_LIST_LIMIT)

    private const val INSIGHT_LIST_LIMIT = 5
}
