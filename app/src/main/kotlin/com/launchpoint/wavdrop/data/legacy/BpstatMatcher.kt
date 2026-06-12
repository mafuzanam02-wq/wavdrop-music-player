package com.launchpoint.wavdrop.data.legacy

import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.text.MusicTextNormalizer

/**
 * Matches [BlackPlayerStatImportRow] entries against the Wavdrop song library.
 *
 * Match strategy (in priority order):
 * 1. Strict title + artist + album equality.
 *    This is the only method available without a ContentResolver because [Song.uri] is
 *    a content:// URI that doesn't expose the on-disk file path without a query.
 * 2. Tolerant title + artist + album equality.
 *
 * Tolerant matching handles accent/apostrophe/separator drift, but ambiguous
 * keys are skipped instead of guessed.
 *
 * Pure function — no Android framework dependencies.
 */
object BpstatMatcher {

    fun match(importResult: BlackPlayerImportResult, songs: List<Song>): BpstatMatchResult {
        val songsByStrictKey = songs.groupBy { it.strictKey() }
        val songsByTolerantKey = songs.groupBy { it.tolerantKey() }

        val matched   = mutableListOf<Pair<Song, BlackPlayerStatImportRow>>()
        val unmatched = mutableListOf<BlackPlayerStatImportRow>()

        for (row in importResult.validRows) {
            val song = uniqueSong(songsByStrictKey[row.strictKey()])
                ?: uniqueSong(songsByTolerantKey[row.tolerantKey()])
            if (song != null) matched.add(song to row) else unmatched.add(row)
        }

        return BpstatMatchResult(
            matchedCount    = matched.size,
            unmatchedCount  = unmatched.size,
            unmatchedSample = unmatched.take(10),
            matchedRows     = matched,
        )
    }

    private fun uniqueSong(candidates: List<Song>?): Song? =
        candidates?.takeIf { songs -> songs.distinctBy { it.id }.size == 1 }?.first()

    private fun Song.strictKey() = Triple(
        MusicTextNormalizer.normalizeStrict(title),
        MusicTextNormalizer.normalizeStrict(artist),
        MusicTextNormalizer.normalizeStrict(album),
    )

    private fun BlackPlayerStatImportRow.strictKey() = Triple(
        MusicTextNormalizer.normalizeStrict(title),
        MusicTextNormalizer.normalizeStrict(artist),
        MusicTextNormalizer.normalizeStrict(album),
    )

    private fun Song.tolerantKey() = Triple(
        MusicTextNormalizer.normalizeTolerant(title),
        MusicTextNormalizer.normalizeTolerant(artist),
        MusicTextNormalizer.normalizeTolerant(album),
    )

    private fun BlackPlayerStatImportRow.tolerantKey() = Triple(
        MusicTextNormalizer.normalizeTolerant(title),
        MusicTextNormalizer.normalizeTolerant(artist),
        MusicTextNormalizer.normalizeTolerant(album),
    )
}
