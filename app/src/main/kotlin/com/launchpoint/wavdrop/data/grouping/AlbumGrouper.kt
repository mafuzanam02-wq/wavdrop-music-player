package com.launchpoint.wavdrop.data.grouping

import com.launchpoint.wavdrop.data.model.AlbumSummary
import com.launchpoint.wavdrop.data.model.Song

object AlbumGrouper {

    /** Canonical key for a song — what we group and navigate by. */
    fun albumKey(song: Song): String =
        song.album.trim().ifBlank { "Unknown Album" }

    /** Group [songs] into sorted album summaries. */
    fun group(songs: List<Song>): List<AlbumSummary> =
        songs
            .groupBy { albumKey(it) }
            .map { (key, group) ->
                val primaryArtist = group
                    .groupBy { it.artist.trim() }
                    .maxByOrNull { it.value.size }
                    ?.key
                    ?.ifBlank { "Unknown Artist" }
                    ?: "Unknown Artist"
                AlbumSummary(
                    albumId        = group.firstOrNull { it.albumId > 0L }?.albumId ?: 0L,
                    albumKey       = key,
                    artist         = primaryArtist,
                    songCount      = group.size,
                    totalDurationMs = group.sumOf { it.duration },
                )
            }
            .sortedBy { it.albumKey.lowercase() }
}
