package com.launchpoint.wavdrop.data.backup

import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.text.MusicTextNormalizer

/**
 * Resolves song-linked backup rows (lyrics overrides, playlist entries, listen
 * events) to current library songs.
 *
 * Resolution order, strongest first:
 *  1. Backup songId through [resolvedBySongId] — the matcher mapping computed once
 *     per restore, which survives reinstall ID changes and already applies the
 *     URI-corroboration rule (P5-AUDIT-11).
 *  2. Title+artist+album tags — but ONLY when exactly one current song carries
 *     those tags, and never a song the matcher REJECTED for this backup song
 *     ([rejectedLocalIdsByBackupSongId], a contradicted URI collision).
 *
 * A bare content URI is deliberately NOT a resolution step: a MediaStore URI is not durable identity and
 * this resolver has no duration, so it cannot corroborate one. Unresolved rows return null; callers count them as unmatched.
 */
class BackupSongLinkResolver(
    currentSongs: List<Song>,
    private val resolvedBySongId: Map<Long, Song>,
    private val rejectedLocalIdsByBackupSongId: Map<Long, Set<Long>> = emptyMap(),
) {
    // groupBy + size==1 filter instead of associateBy: associateBy silently collapses
    // duplicate-tag songs to the last one, which mis-attaches data.
    private val uniqueByTags: Map<Triple<String, String, String>, Song> =
        currentSongs
            .groupBy {
                Triple(
                    MusicTextNormalizer.normalizeStrict(it.title),
                    MusicTextNormalizer.normalizeStrict(it.artist),
                    MusicTextNormalizer.normalizeStrict(it.album),
                )
            }
            .filterValues { it.size == 1 }
            .mapValues { (_, songs) -> songs.single() }

    @Suppress("UNUSED_PARAMETER") // contentUri is kept for call-site compatibility; a URI alone never resolves a row.
    fun resolve(
        backupSongId: Long,
        contentUri: String?,
        title: String?,
        artist: String?,
        album: String?,
    ): Song? {
        resolvedBySongId[backupSongId]?.let { return it }
        if (title != null && artist != null && album != null) {
            val candidate = uniqueByTags[
                Triple(
                    MusicTextNormalizer.normalizeStrict(title),
                    MusicTextNormalizer.normalizeStrict(artist),
                    MusicTextNormalizer.normalizeStrict(album),
                )
            ] ?: return null
            if (candidate.id in rejectedLocalIdsByBackupSongId[backupSongId].orEmpty()) return null
            return candidate
        }
        return null
    }
}
