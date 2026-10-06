package com.launchpoint.wavdrop.data.backup

import com.launchpoint.wavdrop.data.local.entity.LyricsOverrideEntity
import com.launchpoint.wavdrop.data.local.entity.TrackStatsEntity
import com.launchpoint.wavdrop.data.model.Song

/**
 * Pure planner for an authoritative Recovery Restore. It resolves EVERY matching decision (songs, events, playlists, lyrics,
 * baselines, extension) from the backup and the current library before a single destructive write happens; the repository then
 * clears and writes inside one Room transaction without re-deciding anything.
 *
 * Matching is exactly the conservative matcher/resolver Merge uses (no new thresholds, no rematching, no portable identity):
 * a wrong match is worse than unresolved data, so unmatched rows are preserved in pending/quarantine by the repository.
 *
 * Unlike Merge nothing is MAX-merged against local state: for a matched song every represented field takes the backup value,
 * including lower values, a cleared favourite and the exact v2 lastListenedAt. No events are fabricated from counters.
 */
object RecoveryRestorePlanner {

    data class PlannedPlaylist(
        val name: String,
        val createdAt: Long,
        val updatedAt: Long,
        /** Current song ids in backup order; positions are assigned 0..n-1 by the repository. */
        val songIds: List<Long>,
    )

    data class Plan(
        val matchedRows: List<Pair<Song, BackupTrackStats>>,
        val unmatchedStatsRows: Int,
        val resolvedBySongId: Map<Long, Song>,
        val diagnostics: WavdropBackupMatchDiagnostics,
        val stats: List<TrackStatsEntity>,
        val eventPlan: ListenEventRestorePlanner.Plan,
        val lyrics: List<LyricsOverrideEntity>,
        val lyricsInBackup: Int,
        val lyricsUnmatched: Int,
        val baselinePlan: ImportBaselineRestorePlanner.Plan,
        val playlists: List<PlannedPlaylist>,
        val playlistEntriesInBackup: Int,
        val playlistEntriesUnmatched: Int,
        /** Entries dropped because a different backup song collapsed onto an already-listed current song. */
        val playlistEntriesCollapsed: Int,
        /** Raw desktopOverlay extension to store verbatim; null = the backup carries none (existing one is removed). */
        val desktopOverlayRawJson: String?,
    ) {
        val playlistEntriesRestored: Int get() = playlists.sumOf { it.songIds.size }
        val favoritesRestored: Int get() = stats.count { it.isFavorite }
    }

    fun plan(backup: WavdropBackup, currentSongs: List<Song>): Plan {
        val match = WavdropBackupStatsMatcher.match(backup, currentSongs)
        val resolvedBySongId = WavdropBackupStatsMatcher.resolveBackupSongIds(backup, currentSongs)
        val linkResolver = BackupSongLinkResolver(currentSongs, resolvedBySongId)
        val backupSongById = backup.songs.associateBy { it.id }

        // Stats: one authoritative row per matched current song (the matcher already drops collisions; the map is belt and braces).
        val statsBySong = LinkedHashMap<Long, TrackStatsEntity>()
        for ((song, s) in match.matchedRows) {
            statsBySong.putIfAbsent(
                song.id,
                TrackStatsEntity(
                    songId               = song.id,
                    contentUri           = song.uri,
                    playCount            = s.playCount,
                    skipCount            = s.skipCount,
                    lastPlayedAt         = s.lastPlayedAt,
                    lastListenedAt       = s.lastListenedAt ?: s.lastPlayedAt,
                    totalListeningTimeMs = s.totalListeningTimeMs,
                    isFavorite           = s.isFavorite,
                ),
            )
        }

        // Events: the backup's set becomes the restored set. Empty "existing" sets because the exported-source local events are
        // removed in the same transaction; dedup inside the backup (eventId / fingerprint) still applies.
        val eventPlan = ListenEventRestorePlanner.plan(
            events = backup.listenEvents,
            resolveSong = { e ->
                linkResolver.resolve(e.songId, e.contentUri, e.title, e.artist, e.album)
            },
            existingFingerprints = emptySet(),
            existingEventIds = emptySet(),
        )

        // Lyrics: matched overrides only; unmatched ones are preserved by the quarantine step. Newest wins if two collapse.
        val lyricsBySong = LinkedHashMap<Long, LyricsOverrideEntity>()
        var lyricsUnmatched = 0
        for (o in backup.lyricsOverrides) {
            val bs = backupSongById[o.songId]
            val song = linkResolver.resolve(o.songId, o.contentUri, bs?.title, bs?.artist, bs?.album)
            if (song == null) {
                lyricsUnmatched++
                continue
            }
            val existing = lyricsBySong[song.id]
            if (existing == null || o.updatedAt > existing.updatedAt) {
                lyricsBySong[song.id] = LyricsOverrideEntity(song.id, song.uri, o.lyrics, o.updatedAt)
            }
        }

        // Baselines: restored as-is (no local baselines survive the transaction, so nothing to compare against).
        val baselinePlan = ImportBaselineRestorePlanner.plan(
            baselines = backup.importBaselines,
            resolveSongId = { id -> resolvedBySongId[id]?.id },
            existing = emptyList(),
        )

        // Playlists: backup order and names are authoritative. Names are case-insensitively unique locally, so a backup that
        // somehow carries two spellings folds into the first. Duplicate entries of the SAME backup song are preserved; a
        // different backup song collapsing onto an already-listed current song is dropped (never guess).
        val planned = LinkedHashMap<String, MutableList<Long>>()
        val meta = LinkedHashMap<String, Pair<String, BackupPlaylist>>()
        val firstBackupIdOfSong = HashMap<String, MutableMap<Long, Long>>()
        var entriesUnmatched = 0
        var entriesCollapsed = 0
        for (p in backup.playlists) {
            val name = p.name.trim()
            if (name.isBlank()) continue
            val key = name.lowercase()
            val songIds = planned.getOrPut(key) { mutableListOf() }
            meta.getOrPut(key) { name to p }
            val owners = firstBackupIdOfSong.getOrPut(key) { HashMap() }
            for (entry in p.songs.sortedBy { it.position }) {
                val song = linkResolver.resolve(entry.songId, entry.contentUri, entry.title, entry.artist, entry.album)
                if (song == null) {
                    entriesUnmatched++
                    continue
                }
                val owner = owners.getOrPut(song.id) { entry.songId }
                if (owner != entry.songId) {
                    entriesCollapsed++
                    continue
                }
                songIds += song.id
            }
        }
        val playlists = planned.map { (key, ids) ->
            val (name, source) = meta.getValue(key)
            PlannedPlaylist(name, source.createdAt, source.updatedAt, ids)
        }

        return Plan(
            matchedRows = match.matchedRows,
            unmatchedStatsRows = match.unmatchedCount,
            resolvedBySongId = resolvedBySongId,
            diagnostics = match.diagnostics,
            stats = statsBySong.values.toList(),
            eventPlan = eventPlan,
            lyrics = lyricsBySong.values.toList(),
            lyricsInBackup = backup.lyricsOverrides.size,
            lyricsUnmatched = lyricsUnmatched,
            baselinePlan = baselinePlan,
            playlists = playlists,
            playlistEntriesInBackup = backup.playlists.sumOf { it.songs.size },
            playlistEntriesUnmatched = entriesUnmatched,
            playlistEntriesCollapsed = entriesCollapsed,
            desktopOverlayRawJson = backup.desktopOverlay?.rawJson,
        )
    }
}
