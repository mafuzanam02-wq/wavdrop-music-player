package com.launchpoint.wavdrop.data.backup

import com.launchpoint.wavdrop.data.model.Song

/**
 * Resolves song-linked backup rows (lyrics overrides, playlist entries, listen
 * events) to current library songs.
 *
 * Resolution order, strongest first:
 *  1. Backup songId through [resolvedBySongId] — the 6-tier matcher mapping
 *     computed once per restore, which survives reinstall ID changes.
 *  2. Exact content URI (same device, no rescan).
 *  3. Title+artist+album tags — but ONLY when exactly one current song carries
 *     those tags. Duplicate-tag songs are never guessed at: attaching lyrics or
 *     playlist entries to the wrong duplicate is worse than skipping the row.
 *
 * Unresolved rows return null; callers count them as unmatched.
 */
class BackupSongLinkResolver(
    currentSongs: List<Song>,
    private val resolvedBySongId: Map<Long, Song>,
) {
    private val byUri = currentSongs.associateBy { it.uri }

    // groupBy + size==1 filter instead of associateBy: associateBy silently collapses
    // duplicate-tag songs to the last one, which mis-attaches data.
    private val uniqueByTags: Map<Triple<String, String, String>, Song> =
        currentSongs
            .groupBy { Triple(it.title.norm(), it.artist.norm(), it.album.norm()) }
            .filterValues { it.size == 1 }
            .mapValues { (_, songs) -> songs.single() }

    fun resolve(
        backupSongId: Long,
        contentUri: String?,
        title: String?,
        artist: String?,
        album: String?,
    ): Song? {
        resolvedBySongId[backupSongId]?.let { return it }
        contentUri?.let { uri -> byUri[uri]?.let { return it } }
        if (title != null && artist != null && album != null) {
            return uniqueByTags[Triple(title.norm(), artist.norm(), album.norm())]
        }
        return null
    }

    private fun String.norm() = trim().lowercase()
}
