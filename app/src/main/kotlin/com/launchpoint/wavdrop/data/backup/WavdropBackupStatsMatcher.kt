package com.launchpoint.wavdrop.data.backup

import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.text.MusicTextNormalizer
import kotlin.math.abs

/**
 * Matches backup rows (stats and listen events) to songs in the current library.
 *
 * MediaStore URIs change on reinstall/rescan, so URI alone is unreliable. The
 * matcher tries identity tiers from strongest to weakest and only accepts a
 * tier when it yields exactly ONE candidate — multiple candidates at the same
 * tier are counted as ambiguous instead of guessing:
 *
 *  1. Exact content URI (same device, no rescan), corroborated by duration, title and - when both are known - artist
 *  2. Folder path + title + duration (±[DURATION_TOLERANCE_MS])
 *  3. Title + artist + album + duration tolerance
 *  4. Title + artist + duration tolerance (album tag changed)
 *  5. Title + duration tolerance (artist/album tags changed)
 *  6. Title + artist + album with no duration check (weak fallback)
 *
 * If several backup stats rows resolve to the same local song, only the
 * strongest match is applied; the rest are dropped and counted as collisions
 * so stats are never silently overwritten by "last write wins".
 */
object WavdropBackupStatsMatcher {

    /** Two songs are "the same duration" within this tolerance (rescans can shift ms). */
    const val DURATION_TOLERANCE_MS = 2_000L

    enum class MatchTier {
        URI,
        PATH_TITLE_DURATION,
        TAGS_DURATION,
        TITLE_ARTIST_DURATION,
        TITLE_DURATION,
        TOLERANT_PATH_TITLE_DURATION,
        TOLERANT_TAGS_DURATION,
        TOLERANT_TITLE_ARTIST_DURATION,
        TAGS_ONLY,
    }

    private sealed interface Resolution {
        data class Matched(val song: Song, val tier: MatchTier) : Resolution
        data object Ambiguous : Resolution
        data object None : Resolution
    }

    private class Resolver(currentSongs: List<Song>) {
        private val byUri = currentSongs.groupBy { it.uri }
        private val byPathTitle = currentSongs
            .filter { !it.folderPath.isNullOrBlank() }
            .groupBy { it.folderPath!!.normPath() to it.title.strictKey() }
        private val byTolerantPathTitle = currentSongs
            .filter { !it.folderPath.isNullOrBlank() }
            .groupBy { it.folderPath!!.normPath() to it.title.tolerantKey() }
        private val byTags = currentSongs.groupBy {
            Triple(it.title.strictKey(), it.artist.strictKey(), it.album.strictKey())
        }
        private val byTolerantTags = currentSongs.groupBy {
            Triple(it.title.tolerantKey(), it.artist.tolerantKey(), it.album.tolerantKey())
        }
        private val byTitleArtist = currentSongs.groupBy { it.title.strictKey() to it.artist.strictKey() }
        private val byTolerantTitleArtist = currentSongs.groupBy {
            it.title.tolerantKey() to it.artist.tolerantKey()
        }
        private val byTitle = currentSongs.groupBy { it.title.strictKey() }

        /**
         * A URI is supporting evidence only (P5-AUDIT-11). It is accepted when the backup song's duration (within
         * [DURATION_TOLERANCE_MS], both known) AND meaningful normalized title agree with the current song. A URI hit that is contradicted
         * is REJECTED: its local id is added to [rejectedOut] and excluded from every later tier, so the weak TAGS_ONLY fallback cannot
         * re-accept the colliding file. Without a backup song descriptor nothing can corroborate, so a bare URI never attributes anything.
         */
        fun resolve(bSong: BackupSong?, uriHint: String, rejectedOut: MutableSet<Long>? = null): Resolution {
            if (bSong == null) return Resolution.None

            val uriCandidates = listOf(uriHint, bSong.uri).filter { it.isNotBlank() }.distinct()
                .flatMap { byUri[it].orEmpty() }.distinctBy { it.id }
            val corroborated = uriCandidates.filter { uriCorroborated(bSong, it) }
            val excluded = (uriCandidates - corroborated.toSet()).map { it.id }.toSet()
            rejectedOut?.addAll(excluded)
            when (corroborated.size) {
                1 -> return Resolution.Matched(corroborated.single(), MatchTier.URI)
                0 -> Unit
                else -> return Resolution.Ambiguous
            }

            fun durationOk(song: Song): Boolean =
                abs(song.duration - bSong.duration) <= DURATION_TOLERANCE_MS

            val tagsKey = Triple(bSong.title.strictKey(), bSong.artist.strictKey(), bSong.album.strictKey())
            val tolerantTagsKey = Triple(
                bSong.title.tolerantKey(),
                bSong.artist.tolerantKey(),
                bSong.album.tolerantKey(),
            )

            val tiers: List<Pair<MatchTier, List<Song>>> = listOf(
                MatchTier.PATH_TITLE_DURATION to (
                    bSong.folderPath
                        ?.takeIf { it.isNotBlank() }
                        ?.let { byPathTitle[it.normPath() to bSong.title.strictKey()] }
                        ?.filter(::durationOk)
                        .orEmpty()
                    ),
                MatchTier.TAGS_DURATION to byTags[tagsKey]?.filter(::durationOk).orEmpty(),
                MatchTier.TITLE_ARTIST_DURATION to
                    byTitleArtist[bSong.title.strictKey() to bSong.artist.strictKey()]
                        ?.filter(::durationOk).orEmpty(),
                MatchTier.TITLE_DURATION to byTitle[bSong.title.strictKey()]?.filter(::durationOk).orEmpty(),
                MatchTier.TOLERANT_PATH_TITLE_DURATION to (
                    bSong.folderPath
                        ?.takeIf { it.isNotBlank() }
                        ?.let { byTolerantPathTitle[it.normPath() to bSong.title.tolerantKey()] }
                        ?.filter(::durationOk)
                        .orEmpty()
                    ),
                MatchTier.TOLERANT_TAGS_DURATION to
                    byTolerantTags[tolerantTagsKey]?.filter(::durationOk).orEmpty(),
                MatchTier.TOLERANT_TITLE_ARTIST_DURATION to
                    byTolerantTitleArtist[bSong.title.tolerantKey() to bSong.artist.tolerantKey()]
                        ?.filter(::durationOk).orEmpty(),
                MatchTier.TAGS_ONLY to byTags[tagsKey].orEmpty(),
            )

            val firstHit = tiers.map { (tier, songs) -> tier to songs.filter { it.id !in excluded } }
                .firstOrNull { it.second.isNotEmpty() } ?: return Resolution.None
            return if (firstHit.second.distinctBy { it.id }.size == 1) {
                Resolution.Matched(firstHit.second.first(), firstHit.first)
            } else {
                Resolution.Ambiguous
            }
        }
    }

    fun match(backup: WavdropBackup, currentSongs: List<Song>): WavdropBackupMatchResult {
        val resolver = Resolver(currentSongs)
        val backupSongById = backup.songs.associateBy { it.id }

        data class Candidate(val song: Song, val stat: BackupTrackStats, val tier: MatchTier)

        val candidates = mutableListOf<Candidate>()
        var ambiguous  = 0
        var unmatched  = 0
        val tierCounts = mutableMapOf<MatchTier, Int>()

        for (stat in backup.trackStats) {
            when (val res = resolver.resolve(backupSongById[stat.songId], stat.contentUri)) {
                is Resolution.Matched -> candidates += Candidate(res.song, stat, res.tier)
                Resolution.Ambiguous  -> ambiguous++
                Resolution.None       -> unmatched++
            }
        }

        // Collision handling: several backup rows resolving to the same local song.
        // Keep the strongest tier; if the top two tie, keep none (we cannot know
        // which copy's stats belong to the surviving file).
        val matched    = mutableListOf<Pair<Song, BackupTrackStats>>()
        var collisions = 0

        for ((_, rows) in candidates.groupBy { it.song.id }) {
            if (rows.size == 1) {
                val row = rows.single()
                matched += row.song to row.stat
                tierCounts.merge(row.tier, 1, Int::plus)
                continue
            }
            val sorted = rows.sortedBy { it.tier.ordinal }
            if (sorted[0].tier == sorted[1].tier) {
                collisions += rows.size
            } else {
                matched += sorted[0].song to sorted[0].stat
                tierCounts.merge(sorted[0].tier, 1, Int::plus)
                collisions += rows.size - 1
            }
        }

        val diagnostics = WavdropBackupMatchDiagnostics(
            statsInBackup                = backup.trackStats.size,
            matchedByUri                 = tierCounts[MatchTier.URI] ?: 0,
            matchedByPath                =
                (tierCounts[MatchTier.PATH_TITLE_DURATION] ?: 0) +
                    (tierCounts[MatchTier.TOLERANT_PATH_TITLE_DURATION] ?: 0),
            matchedByTagsDuration        =
                (tierCounts[MatchTier.TAGS_DURATION] ?: 0) +
                    (tierCounts[MatchTier.TOLERANT_TAGS_DURATION] ?: 0),
            matchedByTitleArtistDuration =
                (tierCounts[MatchTier.TITLE_ARTIST_DURATION] ?: 0) +
                    (tierCounts[MatchTier.TOLERANT_TITLE_ARTIST_DURATION] ?: 0),
            matchedByTitleDuration       = tierCounts[MatchTier.TITLE_DURATION] ?: 0,
            matchedByTagsOnly            = tierCounts[MatchTier.TAGS_ONLY] ?: 0,
            ambiguous                    = ambiguous,
            collisions                   = collisions,
            unmatched                    = unmatched,
        )

        return WavdropBackupMatchResult(
            matchedRows    = matched,
            unmatchedCount = unmatched + ambiguous + collisions,
            diagnostics    = diagnostics,
        )
    }

    /**
     * Resolves every backup song id to a current library song using the same
     * tier logic as stats matching. Used to map listen events (and any other
     * songId-keyed backup rows) to their new local song ids after a reinstall.
     * Ambiguous or unmatched backup songs are simply absent from the map.
     */
    fun resolveBackupSongIds(backup: WavdropBackup, currentSongs: List<Song>): Map<Long, Song> =
        resolveBackupSongs(backup, currentSongs).resolved

    /** [resolved]: backup song id -> current song. [rejectedLocalIds]: backup song id -> URI-colliding local ids that must never be re-attached. */
    data class BackupSongResolution(val resolved: Map<Long, Song>, val rejectedLocalIds: Map<Long, Set<Long>>)

    fun resolveBackupSongs(backup: WavdropBackup, currentSongs: List<Song>): BackupSongResolution {
        val resolver = Resolver(currentSongs)
        val resolved = mutableMapOf<Long, Song>()
        val rejected = mutableMapOf<Long, Set<Long>>()
        for (bSong in backup.songs) {
            val out = mutableSetOf<Long>()
            val res = resolver.resolve(bSong, bSong.uri, out)
            if (res is Resolution.Matched) resolved[bSong.id] = res.song
            if (out.isNotEmpty()) rejected[bSong.id] = out
        }
        return BackupSongResolution(resolved, rejected)
    }

    private val PLACEHOLDER_TITLES = setOf("", "unknown", "unknown title", "untitled", "unknown track", "<unknown>")

    /** Tolerant keys (lower-case, marks/apostrophes dropped) that mean "no artist information". */
    private val PLACEHOLDER_ARTISTS = setOf("", "unknown", "unknown artist", "<unknown>")

    private fun uriCorroborated(b: BackupSong, local: Song): Boolean {
        if (b.duration <= 0L || local.duration <= 0L) return false
        if (abs(local.duration - b.duration) > DURATION_TOLERANCE_MS) return false
        val strict = b.title.strictKey()
        val bt = b.title.tolerantKey()
        if (bt.isBlank() || strict in PLACEHOLDER_TITLES || bt in PLACEHOLDER_TITLES) return false
        if (bt != local.title.tolerantKey()) return false
        return !artistsContradict(b.artist, local.artist)
    }

    /**
     * P5-REL-04B1: a reused or stale URI can point at a DIFFERENT artist's song that shares a title and a near-equal duration. Two
     * artists contradict each other only when BOTH are known (not blank, not an "unknown" placeholder) and their tolerant keys differ.
     * An unknown artist neither corroborates nor contradicts, so it can never reject a URI nor turn one into a match; title and duration
     * are still required above. Case, whitespace, accent and apostrophe differences are normalized away by the tolerant key.
     */
    private fun artistsContradict(backupArtist: String, localArtist: String): Boolean {
        val b = backupArtist.tolerantKey()
        val l = localArtist.tolerantKey()
        if (b in PLACEHOLDER_ARTISTS || l in PLACEHOLDER_ARTISTS) return false
        return b != l
    }

    private fun String.strictKey() = MusicTextNormalizer.normalizeStrict(this)

    private fun String.tolerantKey() = MusicTextNormalizer.normalizeTolerant(this)

    private fun String.normPath() = MusicTextNormalizer.normalizeStrict(trim().trim('/', '\\'))
}
