package com.launchpoint.wavdrop.data.playlists

import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.text.MusicTextNormalizer
import kotlin.math.abs

/**
 * Conservatively maps stale MediaStore song IDs to newly introduced IDs during one library sync.
 *
 * This intentionally uses fewer and stricter tiers than backup restore. A missed match leaves the
 * playlist membership safely orphaned; a false match would attach it to the wrong song.
 */
object PlaylistSongRemapPlanner {

    const val DURATION_TOLERANCE_MS = 2_000L

    enum class MatchTier {
        PATH_TITLE_DURATION,
        TAGS_DURATION,
        TITLE_ARTIST_DURATION,
    }

    data class Mapping(
        val oldSongId: Long,
        val newSongId: Long,
        val tier: MatchTier,
    )

    data class Plan(
        val mappings: List<Mapping>,
        val unmatchedOldSongIds: Set<Long>,
        val ambiguousOldSongIds: Set<Long>,
        val conflictingOldSongIds: Set<Long>,
    )

    fun plan(
        staleSongs: List<Song>,
        newSongs: List<Song>,
    ): Plan {
        if (staleSongs.isEmpty()) {
            return Plan(
                mappings = emptyList(),
                unmatchedOldSongIds = emptySet(),
                ambiguousOldSongIds = emptySet(),
                conflictingOldSongIds = emptySet(),
            )
        }

        val targets = newSongs.distinctBy { it.id }.sortedBy { it.id }
        val proposed = mutableListOf<Mapping>()
        val unmatched = sortedSetOf<Long>()
        val ambiguous = sortedSetOf<Long>()

        staleSongs.distinctBy { it.id }.sortedBy { it.id }.forEach { staleSong ->
            when (val resolution = resolve(staleSong, targets)) {
                is Resolution.Match -> proposed += Mapping(
                    oldSongId = staleSong.id,
                    newSongId = resolution.song.id,
                    tier = resolution.tier,
                )
                Resolution.Ambiguous -> ambiguous += staleSong.id
                Resolution.Unmatched -> unmatched += staleSong.id
            }
        }

        val conflicting = proposed
            .groupBy { it.newSongId }
            .filterValues { it.size > 1 }
            .values
            .flatten()
            .mapTo(sortedSetOf()) { it.oldSongId }

        return Plan(
            mappings = proposed
                .filterNot { it.oldSongId in conflicting }
                .sortedWith(compareBy(Mapping::oldSongId, Mapping::newSongId)),
            unmatchedOldSongIds = unmatched,
            ambiguousOldSongIds = ambiguous,
            conflictingOldSongIds = conflicting,
        )
    }

    private fun resolve(staleSong: Song, targets: List<Song>): Resolution {
        val tiers = listOf(
            MatchTier.PATH_TITLE_DURATION to targets.filter { candidate ->
                val stalePath = normalizePath(staleSong.folderPath)
                val candidatePath = normalizePath(candidate.folderPath)
                stalePath.isNotEmpty() &&
                    candidatePath.isNotEmpty() &&
                    stalePath == candidatePath &&
                    strict(staleSong.title) == strict(candidate.title) &&
                    durationMatches(staleSong, candidate)
            },
            MatchTier.TAGS_DURATION to targets.filter { candidate ->
                strict(staleSong.title) == strict(candidate.title) &&
                    strict(staleSong.artist) == strict(candidate.artist) &&
                    strict(staleSong.album) == strict(candidate.album) &&
                    durationMatches(staleSong, candidate)
            },
            MatchTier.TITLE_ARTIST_DURATION to targets.filter { candidate ->
                strict(staleSong.title) == strict(candidate.title) &&
                    strict(staleSong.artist) == strict(candidate.artist) &&
                    durationMatches(staleSong, candidate)
            },
        )

        for ((tier, candidates) in tiers) {
            val distinct = candidates.distinctBy { it.id }
            if (distinct.size == 1) return Resolution.Match(distinct.single(), tier)
            if (distinct.size > 1) return Resolution.Ambiguous
        }
        return Resolution.Unmatched
    }

    private fun durationMatches(old: Song, new: Song): Boolean =
        abs(old.duration - new.duration) <= DURATION_TOLERANCE_MS

    private fun strict(value: String?): String =
        MusicTextNormalizer.normalizeStrict(value)

    private fun normalizePath(value: String?): String =
        MusicTextNormalizer.normalizeStrict(
            value
                .orEmpty()
                .trim()
                .replace('\\', '/')
                .trim('/'),
        )

    private sealed interface Resolution {
        data class Match(val song: Song, val tier: MatchTier) : Resolution
        data object Ambiguous : Resolution
        data object Unmatched : Resolution
    }
}
