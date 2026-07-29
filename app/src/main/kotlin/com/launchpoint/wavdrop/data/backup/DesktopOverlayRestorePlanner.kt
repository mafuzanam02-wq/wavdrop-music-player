package com.launchpoint.wavdrop.data.backup

import android.util.Log
import com.launchpoint.wavdrop.BuildConfig
import com.launchpoint.wavdrop.data.local.entity.TrackListenEventEntity
import com.launchpoint.wavdrop.data.local.entity.TrackStatsEntity
import com.launchpoint.wavdrop.data.model.ListeningPeriodRange
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.text.MusicTextNormalizer
import java.time.Instant
import java.time.ZoneId

data class DesktopOverlayMatchedStats(
    val song: Song,
    val stats: BackupDesktopOverlayTrackStats,
    val statsWillIncrease: Boolean,
    val favoriteWillApply: Boolean,
)

data class DesktopOverlayEventPlan(
    val toInsert: List<TrackListenEventEntity>,
    val eventsInOverlay: Int,
    val restored: Int,
    val skippedDuplicate: Int,
    val skippedUnmatched: Int,
    val skippedInvalid: Int,
    val currentMonthRestored: Int,
) {
    val skippedTotal: Int get() = skippedDuplicate + skippedUnmatched + skippedInvalid
}

data class DesktopOverlayRestorePlan(
    val matchedStats: List<DesktopOverlayMatchedStats>,
    /**
     * Overlay track stats that could not be resolved to a unique local song.
     * Includes both truly-unmatched rows (no candidate) and ambiguous rows (multiple
     * candidates) — the batch matcher does not expose per-row classification without an
     * extra O(M) pass, so both are reported here for preservation/quarantine decisions.
     */
    val unresolvedStats: List<BackupDesktopOverlayTrackStats>,
    val eventPlan: DesktopOverlayEventPlan,
) {
    val hasWrites: Boolean =
        matchedStats.any { it.statsWillIncrease || it.favoriteWillApply } ||
            eventPlan.restored > 0

    val hasPreservedOverlayRows: Boolean =
        unresolvedStats.isNotEmpty() || eventPlan.skippedUnmatched > 0
}

object DesktopOverlayRestorePlanner {

    fun fingerprint(songId: Long, event: BackupDesktopOverlayListenEvent): String =
        "$songId|${event.occurredAt}|${event.eventType}|${event.listenedMs}"

    fun plan(
        overlay: BackupDesktopOverlay,
        currentSongs: List<Song>,
        currentStats: Map<Long, TrackStatsEntity>,
        existingEventFingerprints: Set<String>,
        existingEventIds: Set<String>,
        nowMs: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): DesktopOverlayRestorePlan {
        val totalStart = System.currentTimeMillis()
        if (BuildConfig.DEBUG) Log.d(TAG, "plan start stats=${overlay.trackStats.size} " +
            "events=${overlay.listenEvents.size} currentSongs=${currentSongs.size}")
        val matchedStats = mutableListOf<DesktopOverlayMatchedStats>()
        val unresolvedStats = mutableListOf<BackupDesktopOverlayTrackStats>()
        val matchedByDesktopId = mutableMapOf<String, Song>()

        if (overlay.trackStats.isNotEmpty()) {
            // Batch-resolve all overlay track stats in a single WavdropBackupStatsMatcher.match()
            // call. The previous approach called resolve() once per track, which built a new
            // Resolver (8 hash maps over the full local library) for every single overlay track —
            // O(N×M) total. The batch assigns each overlay track a synthetic Long id so all N
            // tracks are resolved against the one Resolver — O(M + N) total.
            val statsMatchStart = System.currentTimeMillis()
            val batchResult = WavdropBackupStatsMatcher.match(
                WavdropBackup(
                    exportedAt = "",
                    songs = overlay.trackStats.mapIndexed { i, s ->
                        BackupSong(
                            id = (i + 1L), uri = "", title = s.title, artist = s.artist,
                            album = s.album, albumId = 0L, duration = s.durationMs,
                            dateAdded = 0L, trackNumber = 0, year = 0,
                        )
                    },
                    trackStats = overlay.trackStats.mapIndexed { i, s ->
                        BackupTrackStats(
                            songId = (i + 1L), contentUri = "",
                            playCount = s.playCount, skipCount = s.skipCount,
                            lastPlayedAt = s.lastPlayedAt,
                            totalListeningTimeMs = s.totalListeningTimeMs,
                            isFavorite = s.favorite,
                            lastListenedAt = s.lastListenedAt,
                        )
                    },
                    importBaselines = emptyList(),
                ),
                currentSongs,
            )
            if (BuildConfig.DEBUG) Log.d(TAG, "batch stats match done matched=${batchResult.matchedRows.size} " +
                "unmatched=${batchResult.unmatchedCount} ms=${System.currentTimeMillis() - statsMatchStart}")
            val desktopIdStart = System.currentTimeMillis()
            val matchedBySynId: Map<Long, Song> =
                batchResult.matchedRows.associate { (song, stat) -> stat.songId to song }

            overlay.trackStats.forEachIndexed { i, stats ->
                val song = matchedBySynId[(i + 1L)]
                // WD-05: an implausible overlay row is preserved as raw/unresolved
                // rather than applied. The overlay's rawJson is retained verbatim
                // regardless; we simply never fold out-of-range magnitudes into
                // local stats via MAX. Overlay counts/timestamps are floored to 0
                // at parse time, so only the upper bounds can trip here.
                if (song != null && stats.isPlausible(nowMs)) {
                    matchedStats += stats.toMatchedStats(song, currentStats[song.id])
                    matchedByDesktopId[stats.desktopTrackId] = song
                } else {
                    unresolvedStats += stats
                }
            }
            if (BuildConfig.DEBUG) Log.d(TAG, "matchedByDesktopId built matched=${matchedByDesktopId.size} " +
                "unresolved=${unresolvedStats.size} ms=${System.currentTimeMillis() - desktopIdStart}")
        }

        val statsByDesktopId = overlay.trackStats.associateBy { it.desktopTrackId }

        val eventStart = System.currentTimeMillis()
        val eventPlan = planEvents(
            events = overlay.listenEvents,
            currentSongs = currentSongs,
            matchedByDesktopId = matchedByDesktopId,
            statsByDesktopId = statsByDesktopId,
            existingEventFingerprints = existingEventFingerprints,
            existingEventIds = existingEventIds,
            nowMs = nowMs,
            zone = zone,
        )
        if (BuildConfig.DEBUG) Log.d(TAG, "event planning done restored=${eventPlan.restored} " +
            "skipped=${eventPlan.skippedTotal} ms=${System.currentTimeMillis() - eventStart}")

        return DesktopOverlayRestorePlan(
            matchedStats = matchedStats,
            unresolvedStats = unresolvedStats,
            eventPlan = eventPlan,
        ).also {
            if (BuildConfig.DEBUG) Log.d(TAG, "plan done matched=${it.matchedStats.size} " +
                "unresolved=${it.unresolvedStats.size} toInsert=${it.eventPlan.toInsert.size} " +
                "totalMs=${System.currentTimeMillis() - totalStart}")
        }
    }

    private fun planEvents(
        events: List<BackupDesktopOverlayListenEvent>,
        currentSongs: List<Song>,
        matchedByDesktopId: Map<String, Song>,
        statsByDesktopId: Map<String, BackupDesktopOverlayTrackStats>,
        existingEventFingerprints: Set<String>,
        existingEventIds: Set<String>,
        nowMs: Long,
        zone: ZoneId,
    ): DesktopOverlayEventPlan {
        val now = Instant.ofEpochMilli(nowMs).atZone(zone)
        val currentMonth = ListeningPeriodRange.month(now.year, now.monthValue, zone)
        val seenFingerprints = existingEventFingerprints.toHashSet()
        val seenEventIds = existingEventIds.toHashSet()
        val toInsert = mutableListOf<TrackListenEventEntity>()
        var skippedDuplicate = 0
        var skippedUnmatched = 0
        var skippedInvalid = 0
        var currentMonthRestored = 0
        var fallbackResolveCount = 0

        val fallbackResolveStart = System.currentTimeMillis()
        val fallbackMatches = buildFallbackEventMatches(
            events = events,
            currentSongs = currentSongs,
            matchedByDesktopId = matchedByDesktopId,
            statsByDesktopId = statsByDesktopId,
        )
        if (BuildConfig.DEBUG) {
            fallbackResolveCount = fallbackMatches.requestedKeys
            Log.d(TAG, "fallback event resolve done keys=${fallbackMatches.requestedKeys} " +
                "matched=${fallbackMatches.matchedByKey.size} " +
                "ms=${System.currentTimeMillis() - fallbackResolveStart}")
        }

        for (event in events) {
            if (!event.hasValidShape()) {
                skippedInvalid++
                continue
            }

            val song = event.desktopTrackId?.let { matchedByDesktopId[it] }
                ?: fallbackMatches.matchedByKey[event.matchKey(statsByDesktopId[event.desktopTrackId])]
            if (song == null) {
                skippedUnmatched++
                continue
            }

            val incomingEventId = event.eventId
            if (incomingEventId != null) {
                if (incomingEventId in seenEventIds) {
                    skippedDuplicate++
                    continue
                }
                seenEventIds += incomingEventId
            } else {
                val key = fingerprint(song.id, event)
                if (key in seenFingerprints) {
                    skippedDuplicate++
                    continue
                }
                seenFingerprints += key
            }

            toInsert += TrackListenEventEntity(
                songId = song.id,
                eventType = event.eventType,
                occurredAt = event.occurredAt,
                listenedMs = event.listenedMs,
                durationMs = event.durationMs,
                source = TrackListenEventEntity.SOURCE_DESKTOP_PLAYBACK,
                eventId = incomingEventId,
            )
            if (currentMonth.contains(event.occurredAt)) currentMonthRestored++
        }

        return DesktopOverlayEventPlan(
            toInsert = toInsert,
            eventsInOverlay = events.size,
            restored = toInsert.size,
            skippedDuplicate = skippedDuplicate,
            skippedUnmatched = skippedUnmatched,
            skippedInvalid = skippedInvalid,
            currentMonthRestored = currentMonthRestored,
        ).also {
            if (BuildConfig.DEBUG) Log.d(TAG, "event loop done events=${events.size} " +
                "fallbackResolves=$fallbackResolveCount restored=${it.restored} " +
                "duplicate=${it.skippedDuplicate} unmatched=${it.skippedUnmatched} invalid=${it.skippedInvalid}")
        }
    }

    private fun buildFallbackEventMatches(
        events: List<BackupDesktopOverlayListenEvent>,
        currentSongs: List<Song>,
        matchedByDesktopId: Map<String, Song>,
        statsByDesktopId: Map<String, BackupDesktopOverlayTrackStats>,
    ): FallbackEventMatches {
        val statsByKey = linkedMapOf<EventMatchKey, BackupDesktopOverlayTrackStats>()
        for (event in events) {
            if (!event.hasValidShape()) continue
            val desktopTrackId = event.desktopTrackId
            if (desktopTrackId != null && matchedByDesktopId[desktopTrackId] != null) continue
            val stats = event.toTrackStats(statsByDesktopId[desktopTrackId])
            statsByKey.putIfAbsent(event.matchKey(statsByDesktopId[desktopTrackId]), stats)
        }
        if (statsByKey.isEmpty()) return FallbackEventMatches(emptyMap(), 0)

        val keyBySyntheticId = mutableMapOf<Long, EventMatchKey>()
        val songs = mutableListOf<BackupSong>()
        statsByKey.entries.forEachIndexed { index, (key, stats) ->
            val id = index + 1L
            keyBySyntheticId[id] = key
            songs += BackupSong(
                id = id,
                uri = "",
                title = stats.title,
                artist = stats.artist,
                album = stats.album,
                albumId = 0L,
                duration = stats.durationMs,
                dateAdded = 0L,
                trackNumber = 0,
                year = 0,
            )
        }

        val resolvedBySyntheticId = WavdropBackupStatsMatcher.resolveBackupSongIds(
            WavdropBackup(
                exportedAt = "",
                songs = songs,
                trackStats = emptyList(),
                importBaselines = emptyList(),
            ),
            currentSongs,
        )

        val matchedByKey = resolvedBySyntheticId.mapNotNull { (syntheticId, song) ->
            keyBySyntheticId[syntheticId]?.let { it to song }
        }.toMap()
        return FallbackEventMatches(matchedByKey, statsByKey.size)
    }

    private fun BackupDesktopOverlayTrackStats.isPlausible(nowMs: Long): Boolean =
        ImportedStatPlausibility.isPlausibleTrackStats(
            playCount = playCount,
            skipCount = skipCount,
            totalListeningTimeMs = totalListeningTimeMs,
            lastPlayedAt = lastPlayedAt,
            lastListenedAt = lastListenedAt,
            nowMs = nowMs,
        )

    private fun BackupDesktopOverlayTrackStats.toMatchedStats(
        song: Song,
        current: TrackStatsEntity?,
    ): DesktopOverlayMatchedStats {
        val local = current ?: TrackStatsEntity(songId = song.id, contentUri = song.uri)
        return DesktopOverlayMatchedStats(
            song = song,
            stats = this,
            statsWillIncrease =
                playCount > local.playCount ||
                    skipCount > local.skipCount ||
                    totalListeningTimeMs > local.totalListeningTimeMs ||
                    lastPlayedAt > local.lastPlayedAt ||
                    lastListenedAt > local.lastListenedAt,
            favoriteWillApply = favorite && !local.isFavorite,
        )
    }

    private fun BackupDesktopOverlayListenEvent.toTrackStats(
        fallback: BackupDesktopOverlayTrackStats?,
    ): BackupDesktopOverlayTrackStats =
        BackupDesktopOverlayTrackStats(
            desktopTrackId = desktopTrackId.orEmpty(),
            title = title.ifBlank { fallback?.title.orEmpty() },
            artist = artist.ifBlank { fallback?.artist.orEmpty() },
            album = album.ifBlank { fallback?.album.orEmpty() },
            durationMs = durationMs.takeIf { it > 0L } ?: (fallback?.durationMs ?: 0L),
            playCount = 0,
            skipCount = 0,
            totalListeningTimeMs = 0L,
            lastPlayedAt = 0L,
            lastListenedAt = 0L,
            favorite = false,
        )

    private fun BackupDesktopOverlayListenEvent.matchKey(
        fallback: BackupDesktopOverlayTrackStats?,
    ): EventMatchKey {
        desktopTrackId?.takeIf { it.isNotBlank() }?.let { return EventMatchKey.DesktopId(it) }
        val stats = toTrackStats(fallback)
        return EventMatchKey.Metadata(
            title = MusicTextNormalizer.normalizeStrict(stats.title),
            artist = MusicTextNormalizer.normalizeStrict(stats.artist),
            album = MusicTextNormalizer.normalizeStrict(stats.album),
            durationMs = stats.durationMs,
        )
    }

    private fun BackupDesktopOverlayListenEvent.hasValidShape(): Boolean =
        source == TrackListenEventEntity.SOURCE_DESKTOP_PLAYBACK &&
            occurredAt > 0L &&
            durationMs >= 0L &&
            when (eventType) {
                TrackListenEventEntity.TYPE_PLAY -> listenedMs > 0L
                TrackListenEventEntity.TYPE_SKIP -> listenedMs == 0L
                else -> false
            }

    private sealed interface EventMatchKey {
        data class DesktopId(val value: String) : EventMatchKey
        data class Metadata(
            val title: String,
            val artist: String,
            val album: String,
            val durationMs: Long,
        ) : EventMatchKey
    }

    private data class FallbackEventMatches(
        val matchedByKey: Map<EventMatchKey, Song>,
        val requestedKeys: Int,
    )

    private const val TAG = "WavdropOverlayPlanner"
}
