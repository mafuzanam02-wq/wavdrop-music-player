package com.launchpoint.wavdrop.data.stats

import com.launchpoint.wavdrop.data.grouping.AlbumGrouper
import com.launchpoint.wavdrop.data.grouping.ArtistGrouper
import com.launchpoint.wavdrop.data.local.entity.TrackStatsEntity
import com.launchpoint.wavdrop.data.model.AlbumReportSummary
import com.launchpoint.wavdrop.data.model.ArtistReportSummary
import com.launchpoint.wavdrop.data.model.ListeningReportSummary
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.model.SongStatsSummary

object ListeningReportBuilder {

    fun build(
        songs: List<Song>,
        stats: List<TrackStatsEntity>,
    ): ListeningReportSummary {
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

        val topSongs = summaries
            .filter { it.playCount > 0 }
            .sortedWith(songPlayComparator)
            .take(REPORT_LIST_LIMIT)
        val topArtists = buildArtistSummaries(summaries)
            .filter { it.playCount > 0 }
            .sortedWith(artistPlayComparator)
            .take(REPORT_LIST_LIMIT)
        val topAlbums = buildAlbumSummaries(summaries)
            .filter { it.playCount > 0 }
            .sortedWith(albumPlayComparator)
            .take(REPORT_LIST_LIMIT)
        val recentlyPlayedSongs = summaries
            .filter { it.lastPlayedAt != null }
            .sortedByDescending { it.lastPlayedAt ?: 0L }
            .take(REPORT_LIST_LIMIT)
        val recentlyActiveArtists = buildArtistSummaries(summaries)
            .filter { it.lastPlayedAt != null }
            .sortedWith(compareByDescending<ArtistReportSummary> { it.lastPlayedAt ?: 0L }
                .thenBy { it.artistKey.lowercase() })
            .take(REPORT_LIST_LIMIT)

        return ListeningReportSummary(
            totalListeningTimeMs = summaries.sumOf { it.totalListeningTimeMs },
            totalPlayCount = summaries.sumOf { it.playCount },
            totalSkipCount = summaries.sumOf { it.skipCount },
            tracksPlayed = summaries.count { it.playCount > 0 },
            artistsPlayed = summaries
                .filter { it.playCount > 0 }
                .map { ArtistGrouper.artistKey(it.song) }
                .toSet()
                .size,
            albumsPlayed = summaries
                .filter { it.playCount > 0 }
                .map { AlbumGrouper.albumKey(it.song) }
                .toSet()
                .size,
            topSongs = topSongs,
            topArtists = topArtists,
            topAlbums = topAlbums,
            mostPlayedTrack = topSongs.firstOrNull(),
            mostPlayedArtist = topArtists.firstOrNull(),
            mostPlayedAlbum = topAlbums.firstOrNull(),
            mostSkippedTrack = summaries
                .filter { it.skipCount > 0 }
                .sortedWith(compareByDescending<SongStatsSummary> { it.skipCount }
                    .thenBy { it.song.title.lowercase() })
                .firstOrNull(),
            recentlyPlayedSongs = recentlyPlayedSongs,
            recentlyActiveArtists = recentlyActiveArtists,
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

    private fun primaryArtist(songs: List<Song>): String =
        songs
            .groupBy { ArtistGrouper.artistKey(it) }
            .maxByOrNull { it.value.size }
            ?.key
            ?: "Unknown Artist"

    private val songPlayComparator =
        compareByDescending<SongStatsSummary> { it.playCount }
            .thenBy { it.song.title.lowercase() }

    private val artistPlayComparator =
        compareByDescending<ArtistReportSummary> { it.playCount }
            .thenBy { it.artistKey.lowercase() }

    private val albumPlayComparator =
        compareByDescending<AlbumReportSummary> { it.playCount }
            .thenBy { it.albumKey.lowercase() }

    private const val REPORT_LIST_LIMIT = 10
}
