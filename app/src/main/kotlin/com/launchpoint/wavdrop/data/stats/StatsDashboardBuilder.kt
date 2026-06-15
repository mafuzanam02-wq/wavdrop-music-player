package com.launchpoint.wavdrop.data.stats

import com.launchpoint.wavdrop.data.local.entity.TrackStatsEntity
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.model.SongStatsSummary
import com.launchpoint.wavdrop.data.model.StatsDashboardSummary

object StatsDashboardBuilder {

    fun build(
        songs: List<Song>,
        stats: List<TrackStatsEntity>,
    ): StatsDashboardSummary {
        val songsById = songs.associateBy { it.id }
        val summaries = stats.mapNotNull { stat ->
            val song = songsById[stat.songId] ?: return@mapNotNull null
            SongStatsSummary(
                song = song,
                playCount = stat.playCount,
                skipCount = stat.skipCount,
                lastPlayedAt = stat.lastPlayedAt.takeIf { it > 0L },
                totalListeningTimeMs = ListeningTimeRules.effectiveListeningTimeMs(
                    playCount = stat.playCount,
                    durationMs = song.duration,
                    totalListeningTimeMs = stat.totalListeningTimeMs,
                ),
            )
        }

        return StatsDashboardSummary(
            totalSongs = songs.size,
            totalPlayedTracks = summaries.count { it.playCount > 0 },
            totalPlayCount = summaries.sumOf { it.playCount },
            totalSkipCount = summaries.sumOf { it.skipCount },
            totalListeningTimeMs = summaries.sumOf { it.totalListeningTimeMs },
            mostPlayedSongs = summaries
                .filter { it.playCount > 0 }
                .sortedWith(compareByDescending<SongStatsSummary> { it.playCount }
                    .thenBy { it.song.title.lowercase() })
                .take(TOP_LIST_LIMIT),
            recentlyPlayedSongs = summaries
                .filter { it.lastPlayedAt != null }
                .sortedByDescending { it.lastPlayedAt ?: 0L }
                .take(TOP_LIST_LIMIT),
            mostSkippedSongs = summaries
                .filter { it.skipCount > 0 }
                .sortedWith(compareByDescending<SongStatsSummary> { it.skipCount }
                    .thenBy { it.song.title.lowercase() })
                .take(TOP_LIST_LIMIT),
        )
    }

    private const val TOP_LIST_LIMIT = 10
}
