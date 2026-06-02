package com.launchpoint.wavdrop.data.legacy

import com.launchpoint.wavdrop.data.model.Song

/**
 * Matches [BlackPlayerStatImportRow] entries against the Wavdrop song library.
 *
 * Match strategy (in priority order):
 * 1. Case-insensitive, trimmed title + artist + album equality.
 *    This is the only method available without a ContentResolver because [Song.uri] is
 *    a content:// URI that doesn't expose the on-disk file path without a query.
 * 2. Exact filePath resolution — deferred to a future apply pass where a ContentResolver
 *    lookup maps the import row's absolute path to a media ID.
 *
 * When two songs share the same normalised (title, artist, album) key, [associateBy] keeps
 * the last one in the list. This is acceptable for V1; fuzzy deduplication is future work.
 *
 * Pure function — no Android framework dependencies.
 */
object BpstatMatcher {

    fun match(importResult: BlackPlayerImportResult, songs: List<Song>): BpstatMatchResult {
        val songByKey: Map<Triple<String, String, String>, Song> =
            songs.associateBy { Triple(it.title.norm(), it.artist.norm(), it.album.norm()) }

        val matched   = mutableListOf<Pair<Song, BlackPlayerStatImportRow>>()
        val unmatched = mutableListOf<BlackPlayerStatImportRow>()

        for (row in importResult.validRows) {
            val key  = Triple(row.title.norm(), row.artist.norm(), row.album.norm())
            val song = songByKey[key]
            if (song != null) matched.add(song to row) else unmatched.add(row)
        }

        return BpstatMatchResult(
            matchedCount    = matched.size,
            unmatchedCount  = unmatched.size,
            unmatchedSample = unmatched.take(10),
            matchedRows     = matched,
        )
    }

    private fun String.norm() = trim().lowercase()
}
