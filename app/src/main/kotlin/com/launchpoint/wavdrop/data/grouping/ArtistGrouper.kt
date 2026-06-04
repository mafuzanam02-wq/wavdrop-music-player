package com.launchpoint.wavdrop.data.grouping

import com.launchpoint.wavdrop.data.artwork.ArtworkResolver
import com.launchpoint.wavdrop.data.model.ArtistSummary
import com.launchpoint.wavdrop.data.model.Song

object ArtistGrouper {

    /** Canonical key for a song — what we group and navigate by. */
    fun artistKey(song: Song): String =
        song.artist.trim().ifBlank { "Unknown Artist" }

    /** Group [songs] into sorted artist summaries. */
    fun group(songs: List<Song>): List<ArtistSummary> =
        songs
            .groupBy { artistKey(it) }
            .map { (key, group) ->
                val distinctAlbums = group
                    .map { AlbumGrouper.albumKey(it) }
                    .toSet()
                    .size
                ArtistSummary(
                    artistKey       = key,
                    songCount       = group.size,
                    albumCount      = distinctAlbums,
                    totalDurationMs = group.sumOf { it.duration },
                    artworkUri      = representativeArtworkUri(group),
                )
            }
            .sortedBy { it.artistKey.lowercase() }

    fun representativeArtworkUri(songs: List<Song>): String? =
        songs
            .firstOrNull { it.albumId > 0L }
            ?.albumId
            ?.let(ArtworkResolver::albumArtworkUri)
}
