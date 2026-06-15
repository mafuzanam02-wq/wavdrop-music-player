package com.launchpoint.wavdrop.data.backup

import com.launchpoint.wavdrop.data.local.entity.TrackStatsEntity
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.text.MusicTextNormalizer

data class DesktopBackupMatchedRow(
    val song: Song,
    val desktopSong: DesktopBackupSong,
    val mergedStats: TrackStatsEntity,
    val statsWillIncrease: Boolean,
    val favoriteWillApply: Boolean,
)

data class DesktopPlaylistPlan(
    val name: String,
    val resolvedSongIds: List<Long>,
    val skippedUnmatched: Int,
)

data class DesktopBackupImportPlan(
    val matchedRows: List<DesktopBackupMatchedRow>,
    val unmatchedSongs: List<DesktopBackupSong>,
    val ambiguousSongs: List<DesktopBackupSong>,
    val playlistPlans: List<DesktopPlaylistPlan> = emptyList(),
    val playlistsSkippedEmpty: Int = 0,
    val playlistsInBackup: Int = 0,
    val playlistEntriesInBackup: Int = 0,
) {
    val matchedCount: Int get() = matchedRows.size
    val unmatchedCount: Int get() = unmatchedSongs.size
    val ambiguousCount: Int get() = ambiguousSongs.size
    val statsWillIncreaseCount: Int get() = matchedRows.count { it.statsWillIncrease }
    val favoritesWillApplyCount: Int get() = matchedRows.count { it.favoriteWillApply }
    val playlistsToImportCount: Int get() = playlistPlans.size
    val playlistSongsMatchedCount: Int get() = playlistPlans.sumOf { it.resolvedSongIds.size }
    val playlistSongsSkippedCount: Int get() = playlistPlans.sumOf { it.skippedUnmatched }

    fun toApplyResult(): WavdropBackupImportApplyResult = WavdropBackupImportApplyResult(
        matchedTracks            = matchedCount,
        unmatchedTracks          = unmatchedCount,
        ambiguousTracks          = ambiguousCount,
        statsUpdated             = statsWillIncreaseCount,
        favoritesRestored        = favoritesWillApplyCount,
        favoritesInBackup        = matchedRows.count { it.desktopSong.favorite } +
            unmatchedSongs.count { it.favorite } +
            ambiguousSongs.count { it.favorite },
        favoritesUnmatched       = unmatchedSongs.count { it.favorite } + ambiguousSongs.count { it.favorite },
        playlistsInBackup        = playlistsInBackup,
        playlistEntriesInBackup  = playlistEntriesInBackup,
        playlistEntriesUnmatched = playlistSongsSkippedCount,
        eventsRestored           = 0,
        eventsSkipped            = 0,
        eventsSkippedDuplicate   = 0,
        eventsSkippedUnmatched   = 0,
        dataRestored             = matchedRows.isNotEmpty(),
        warnings                 = listOf(
            "Desktop song IDs are not Android song IDs. Songs were matched by metadata.",
        ),
    )
}

object DesktopWavdropBackupImportPlanner {

    private data class Candidate(val song: Song, val desktopSong: DesktopBackupSong)

    fun plan(
        backup: DesktopWavdropBackup,
        currentSongs: List<Song>,
        currentStats: List<TrackStatsEntity>,
    ): DesktopBackupImportPlan {
        val matcher = DesktopSongMatcher(currentSongs)
        val currentStatsById = currentStats.associateBy { it.songId }

        val candidates = mutableListOf<Candidate>()
        val unmatched = mutableListOf<DesktopBackupSong>()
        val ambiguous = mutableListOf<DesktopBackupSong>()

        for (desktopSong in backup.songs) {
            when (val result = matcher.resolve(desktopSong)) {
                is DesktopSongMatch.Matched -> candidates += Candidate(result.song, desktopSong)
                DesktopSongMatch.Ambiguous -> ambiguous += desktopSong
                DesktopSongMatch.None -> unmatched += desktopSong
            }
        }

        val rows = mutableListOf<DesktopBackupMatchedRow>()
        for ((_, group) in candidates.groupBy { it.song.id }) {
            if (group.size != 1) {
                ambiguous += group.map { it.desktopSong }
                continue
            }

            val candidate = group.single()
            val current = currentStatsById[candidate.song.id]
            rows += candidate.toMatchedRow(current)
        }

        // Build desktop string songId → Android Song map for playlist translation.
        // Match every backup song (not just those with stats) so playlist-only songs resolve.
        val desktopIdToAndroidSong: Map<String, Song> = backup.songs.mapNotNull { dSong ->
            when (val res = matcher.resolve(dSong)) {
                is DesktopSongMatch.Matched -> dSong.id to res.song
                else -> null
            }
        }.toMap()

        val playlistPlans = mutableListOf<DesktopPlaylistPlan>()
        var playlistsSkippedEmpty = 0
        var totalPlaylistEntries = 0

        for (playlist in backup.playlists) {
            val name = playlist.name.trim()
            if (name.isBlank()) continue
            totalPlaylistEntries += playlist.songIds.size

            val seenAndroidIds = mutableSetOf<Long>()
            val resolvedSongIds = mutableListOf<Long>()
            var skippedUnmatched = 0

            // songIds is ordered — position is implicit in the array index.
            for (desktopSongId in playlist.songIds) {
                val androidSong = desktopIdToAndroidSong[desktopSongId]
                if (androidSong == null) {
                    skippedUnmatched++
                    continue
                }
                if (androidSong.id in seenAndroidIds) continue
                seenAndroidIds += androidSong.id
                resolvedSongIds += androidSong.id
            }

            if (resolvedSongIds.isEmpty()) {
                playlistsSkippedEmpty++
                continue
            }

            playlistPlans += DesktopPlaylistPlan(
                name             = name,
                resolvedSongIds  = resolvedSongIds,
                skippedUnmatched = skippedUnmatched,
            )
        }

        return DesktopBackupImportPlan(
            matchedRows            = rows,
            unmatchedSongs         = unmatched,
            ambiguousSongs         = ambiguous,
            playlistPlans          = playlistPlans,
            playlistsSkippedEmpty  = playlistsSkippedEmpty,
            playlistsInBackup      = backup.playlists.size,
            playlistEntriesInBackup = totalPlaylistEntries,
        )
    }

    private fun Candidate.toMatchedRow(
        current: TrackStatsEntity?,
    ): DesktopBackupMatchedRow {
        val existing = current ?: TrackStatsEntity(songId = song.id, contentUri = song.uri)
        val merged = existing.copy(
            playCount = maxOf(existing.playCount, desktopSong.playCount),
            totalListeningTimeMs = maxOf(existing.totalListeningTimeMs, desktopSong.totalListeningTimeMs),
            lastPlayedAt = maxOf(existing.lastPlayedAt, desktopSong.lastPlayedAt),
            isFavorite = existing.isFavorite || desktopSong.favorite,
        )
        return DesktopBackupMatchedRow(
            song = song,
            desktopSong = desktopSong,
            mergedStats = merged,
            statsWillIncrease = merged.playCount != existing.playCount ||
                merged.totalListeningTimeMs != existing.totalListeningTimeMs ||
                merged.lastPlayedAt != existing.lastPlayedAt,
            favoriteWillApply = !existing.isFavorite && desktopSong.favorite,
        )
    }

    private sealed interface DesktopSongMatch {
        data class Matched(val song: Song) : DesktopSongMatch
        data object Ambiguous : DesktopSongMatch
        data object None : DesktopSongMatch
    }

    private class DesktopSongMatcher(currentSongs: List<Song>) {
        private val strict = currentSongs.groupBy { it.strictMetadataKey() }
        private val tolerant = currentSongs.groupBy { it.tolerantMetadataKey() }

        fun resolve(desktopSong: DesktopBackupSong): DesktopSongMatch =
            resolveCandidates(strict[desktopSong.strictMetadataKey()])
                ?: resolveCandidates(tolerant[desktopSong.tolerantMetadataKey()])
                ?: DesktopSongMatch.None

        private fun resolveCandidates(candidates: List<Song>?): DesktopSongMatch? {
            val distinct = candidates.orEmpty().distinctBy { it.id }
            return when (distinct.size) {
                0 -> null
                1 -> DesktopSongMatch.Matched(distinct.single())
                else -> DesktopSongMatch.Ambiguous
            }
        }
    }

    private data class MetadataKey(val title: String, val artist: String, val album: String)

    private fun Song.strictMetadataKey() = MetadataKey(
        title = MusicTextNormalizer.normalizeStrict(title),
        artist = MusicTextNormalizer.normalizeStrict(artist),
        album = MusicTextNormalizer.normalizeStrict(album),
    )

    private fun Song.tolerantMetadataKey() = MetadataKey(
        title = MusicTextNormalizer.normalizeTolerant(title),
        artist = MusicTextNormalizer.normalizeTolerant(artist),
        album = MusicTextNormalizer.normalizeTolerant(album),
    )

    private fun DesktopBackupSong.strictMetadataKey() = MetadataKey(
        title = MusicTextNormalizer.normalizeStrict(title),
        artist = MusicTextNormalizer.normalizeStrict(artist),
        album = MusicTextNormalizer.normalizeStrict(album),
    )

    private fun DesktopBackupSong.tolerantMetadataKey() = MetadataKey(
        title = MusicTextNormalizer.normalizeTolerant(title),
        artist = MusicTextNormalizer.normalizeTolerant(artist),
        album = MusicTextNormalizer.normalizeTolerant(album),
    )
}
