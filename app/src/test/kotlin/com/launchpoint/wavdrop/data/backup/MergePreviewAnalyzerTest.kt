package com.launchpoint.wavdrop.data.backup

import com.launchpoint.wavdrop.data.local.entity.ImportBaselineEntity
import com.launchpoint.wavdrop.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-B.1: Merge Result Accuracy and No-Op Protection.
 *
 * Covers all 8 required test cases for [WavdropMergePreviewAnalyzer]:
 *  1. Preferences-only backup → hasMergeableData=false, reason mentions "settings"
 *  2. Completely empty backup → hasMergeableData=false
 *  3. All events already present (duplicates) → hasMergeableData=false
 *  4. All playlists / entries already exist → hasMergeableData=false
 *  5. Unmatched tracks only (no local songs) → hasMergeableData=false
 *  6. One actually mergeable stat match → hasMergeableData=true
 *  7. Mixed: backup higher in some fields, local higher in others → both flags on StatsRestoreStrategy
 *  8. All backup values higher → anyRetainedLocal=false, anyIncreased=true
 */
class MergePreviewAnalyzerTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun song(id: Long, uri: String = "content://media/$id") = Song(
        id = id, title = "Track $id", artist = "Artist", album = "Album",
        albumId = 0L, duration = 180_000L, uri = uri, dateAdded = 0L,
        trackNumber = 0, year = 0,
    )

    private fun backupSong(id: Long, uri: String = "content://media/$id") =
        BackupSong(id, uri, "Track $id", "Artist", "Album", 0L, 180_000L, 0L, 0, 0)

    private fun backupStats(songId: Long, playCount: Int = 5) = BackupTrackStats(
        songId               = songId,
        contentUri           = "content://media/$songId",
        playCount            = playCount,
        skipCount            = 0,
        lastPlayedAt         = 1_000L,
        totalListeningTimeMs = 60_000L,
        isFavorite           = false,
    )

    private fun backupEvent(songId: Long, occurredAt: Long = 1_000_000L) = BackupListenEvent(
        songId     = songId,
        contentUri = "content://media/$songId",
        title      = "Track $songId",
        artist     = "Artist",
        album      = "Album",
        eventType  = "PLAY",
        occurredAt = occurredAt,
        listenedMs = 30_000L,
        durationMs = 180_000L,
        source     = "wavdrop_playback",
    )

    private fun backupPlaylist(name: String, songIds: List<Long>) = BackupPlaylist(
        id        = 1L,
        name      = name,
        createdAt = 0L,
        updatedAt = 0L,
        songs     = songIds.mapIndexed { i, id ->
            BackupPlaylistSong(
                songId = id, contentUri = "content://media/$id",
                position = i, title = "Track $id", artist = "Artist", album = "Album",
            )
        },
    )

    private fun emptyBackup(prefs: BackupPreferences? = null) = WavdropBackup(
        exportedAt      = "2026-06-23T00:00:00Z",
        songs           = emptyList(),
        trackStats      = emptyList(),
        importBaselines = emptyList(),
        preferences     = prefs,
    )

    private fun analyze(
        backup: WavdropBackup,
        currentSongs: List<Song> = emptyList(),
        existingStats: Map<Long, com.launchpoint.wavdrop.data.local.entity.TrackStatsEntity> = emptyMap(),
        existingEventFingerprints: Set<String> = emptySet(),
        existingEventIds: Set<String> = emptySet(),
        existingBaselines: List<ImportBaselineEntity> = emptyList(),
        existingLyrics: Map<WavdropMergePreviewAnalyzer.ExistingLyricsKey, Long> = emptyMap(),
        existingPlaylists: Map<String, Set<Long>> = emptyMap(),
        existingQuarantineOriginKeys: Set<String> = emptySet(),
    ) = WavdropMergePreviewAnalyzer.analyze(
        backup                       = backup,
        currentSongs                 = currentSongs,
        existingQuarantineOriginKeys = existingQuarantineOriginKeys,
        existingStats                = existingStats,
        existingEventFingerprints    = existingEventFingerprints,
        existingEventIds             = existingEventIds,
        existingBaselines            = existingBaselines,
        existingLyrics               = existingLyrics,
        existingPlaylists            = existingPlaylists,
    )

    // ── 1. Preferences-only → no-op ──────────────────────────────────────────

    @Test
    fun `preferences-only backup — hasMergeableData false, reason mentions settings`() {
        val backup = emptyBackup(
            prefs = BackupPreferences(
                startupDestination          = null,
                mostPlayedPeriod            = null,
                mostPlayedLimit             = null,
                homeVisibleSections         = null,
                scanMode                    = null,
                selectedFolderUris          = null,
                minimumTrackDurationSeconds = null,
            ),
        )
        val result = analyze(backup)
        assertFalse(result.hasMergeableData)
        assertNotNull(result.noOpReason)
        assertTrue(
            "No-op reason must mention settings",
            result.noOpReason!!.contains("settings", ignoreCase = true),
        )
    }

    // ── 2. Completely empty backup → no-op ───────────────────────────────────

    @Test
    fun `completely empty backup — hasMergeableData false`() {
        val backup = emptyBackup()
        val result = analyze(backup)
        assertFalse(result.hasMergeableData)
        assertNotNull(result.noOpReason)
    }

    // ── 3. All events already present (all duplicates) → no-op ───────────────

    @Test
    fun `all listen events are already present — hasMergeableData false`() {
        val songId = 1L
        val event  = backupEvent(songId, occurredAt = 1_000_000L)
        val backup = WavdropBackup(
            exportedAt      = "2026-06-23T00:00:00Z",
            songs           = listOf(backupSong(songId)),
            trackStats      = emptyList(),
            importBaselines = emptyList(),
            listenEvents    = listOf(event),
        )
        // Fingerprint matches the event exactly.
        val existingFingerprints = setOf("${songId}|1000000|PLAY|30000")

        val result = analyze(
            backup                    = backup,
            currentSongs              = listOf(song(songId)),
            existingEventFingerprints = existingFingerprints,
        )
        assertFalse(result.hasMergeableData)
        assertNotNull(result.noOpReason)
    }

    // ── 4. All playlists & entries already exist → no-op ────────────────────

    @Test
    fun `all playlist entries already present — hasMergeableData false`() {
        val songId = 1L
        val backup = WavdropBackup(
            exportedAt      = "2026-06-23T00:00:00Z",
            songs           = listOf(backupSong(songId)),
            trackStats      = emptyList(),
            importBaselines = emptyList(),
            playlists       = listOf(backupPlaylist("Favorites", listOf(songId))),
        )
        // Playlist already exists with this song in it.
        val existingPlaylists = mapOf("Favorites" to setOf(songId))

        val result = analyze(
            backup            = backup,
            currentSongs      = listOf(song(songId)),
            existingPlaylists = existingPlaylists,
        )
        assertFalse(result.hasMergeableData)
        assertNotNull(result.noOpReason)
    }

    // ── 5. Unmatched tracks only (no local library) → no-op ──────────────────

    @Test
    fun `backup has stats but no local songs to match against — quarantine path makes it mergeable`() {
        // P2-A: unmatched backup tracks with data are quarantined, not silently discarded.
        // The import is therefore mergeable (the Apply button should be enabled).
        val backup = WavdropBackup(
            exportedAt      = "2026-06-23T00:00:00Z",
            songs           = listOf(backupSong(1L)),
            trackStats      = listOf(backupStats(1L)),
            importBaselines = emptyList(),
        )
        val result = analyze(backup, currentSongs = emptyList())
        assertTrue("quarantine path → mergeable", result.hasMergeableData)
        assertNull(result.noOpReason)
        assertEquals(1, result.newPendingTrackCount)
    }

    // ── 6. One actually mergeable stat match → mergeable ─────────────────────

    @Test
    fun `backup has one matched track stat — hasMergeableData true`() {
        val songId = 1L
        val backup = WavdropBackup(
            exportedAt      = "2026-06-23T00:00:00Z",
            songs           = listOf(backupSong(songId)),
            trackStats      = listOf(backupStats(songId, playCount = 10)),
            importBaselines = emptyList(),
        )
        val result = analyze(backup, currentSongs = listOf(song(songId)))
        assertTrue(result.hasMergeableData)
        assertEquals(null, result.noOpReason)
    }

    // ── 7. Mixed fields: backup higher in some, local higher in others ────────

    @Test
    fun `mixed fields — anyRetainedLocal and anyIncreased both true`() {
        // local:  playCount=50, skipCount=5
        // backup: playCount=30, skipCount=20
        val effect = StatsRestoreStrategy.computeEffect(
            currentPlayCount       = 50,
            currentSkipCount       = 5,
            currentListeningTimeMs = 1_000L,
            currentLastPlayedAt    = 100L,
            backupPlayCount        = 30,
            backupSkipCount        = 20,
            backupListeningTimeMs  = 1_000L,
            backupLastPlayedAt     = 100L,
        )
        // backup skip (20) > local skip (5) → anyIncreased
        assertTrue("backup skip is higher → anyIncreased", effect.anyIncreased)
        // local play (50) > backup play (30) → anyRetainedLocal
        assertTrue("local play is higher → anyRetainedLocal", effect.anyRetainedLocal)
        // A track with anyRetainedLocal counts once in statsRetainedLocal.
        assertEquals(1, listOf(effect).count { it.anyRetainedLocal })
    }

    // ── 8. All backup values higher → anyRetainedLocal false ─────────────────

    @Test
    fun `all backup values higher than local — anyRetainedLocal false, anyIncreased true`() {
        val effect = StatsRestoreStrategy.computeEffect(
            currentPlayCount       = 5,
            currentSkipCount       = 1,
            currentListeningTimeMs = 500L,
            currentLastPlayedAt    = 100L,
            backupPlayCount        = 50,
            backupSkipCount        = 10,
            backupListeningTimeMs  = 900_000L,
            backupLastPlayedAt     = 5_000L,
        )
        assertTrue(effect.anyIncreased)
        assertFalse(effect.anyRetainedLocal)
    }

    // ── desktopOverlay: preview detection ────────────────────────────────────

    private fun overlayEvent(
        occurredAt: Long = 1_782_230_500_000L,
        listenedMs: Long = 30_000L,
        eventType: String = "PLAY",
    ) = BackupDesktopOverlayListenEvent(
        eventId        = "desktop-event-1",
        desktopTrackId = "desktop-track-1",
        title          = "Ghost Song",
        artist         = "Doors",
        album          = "Other Voices",
        durationMs     = 180_000L,
        occurredAt     = occurredAt,
        listenedMs     = listenedMs,
        eventType      = eventType,
        source         = "wavdrop_desktop_playback",
    )

    private fun overlayStat(
        skipCount: Int = 0,
        playCount: Int = 0,
        favorite: Boolean = false,
    ) = BackupDesktopOverlayTrackStats(
        desktopTrackId       = "desktop-track-1",
        title                = "Ghost Song",
        artist               = "Doors",
        album                = "Other Voices",
        durationMs           = 180_000L,
        playCount            = playCount,
        skipCount            = skipCount,
        totalListeningTimeMs = 0L,
        lastPlayedAt         = 0L,
        lastListenedAt       = 0L,
        favorite             = favorite,
    )

    private fun overlayBackup(
        stats: List<BackupDesktopOverlayTrackStats> = listOf(overlayStat()),
        events: List<BackupDesktopOverlayListenEvent> = emptyList(),
    ) = emptyBackup().copy(
        desktopOverlay = BackupDesktopOverlay(
            schemaVersion    = 1,
            producerPlatform = "desktop",
            trackStats       = stats,
            listenEvents     = events,
            rawJson          = "{}",
        ),
    )

    private fun overlayCurrentSong() = song(id = 44L, uri = "content://media/44").copy(
        title  = "Ghost Song",
        artist = "Doors",
        album  = "Other Voices",
    )

    @Test
    fun `v2 backup with overlay PLAY event — hasMergeableData true (test-1)`() {
        val backup = overlayBackup(events = listOf(overlayEvent(listenedMs = 30_000L)))
        val result = analyze(backup, currentSongs = listOf(overlayCurrentSong()))
        assertTrue("overlay PLAY event must make backup mergeable", result.hasMergeableData)
        assertNull(result.noOpReason)
    }

    @Test
    fun `v2 backup with overlay SKIP listenedMs=0 event — hasMergeableData true (test-2)`() {
        val backup = overlayBackup(events = listOf(overlayEvent(listenedMs = 0L, eventType = "SKIP")))
        val result = analyze(backup, currentSongs = listOf(overlayCurrentSong()))
        assertTrue("overlay zero-time SKIP must make backup mergeable", result.hasMergeableData)
        assertNull(result.noOpReason)
    }

    @Test
    fun `overlay skipCount higher than local — hasMergeableData true (test-6)`() {
        val localSong = overlayCurrentSong()
        val backup = overlayBackup(stats = listOf(overlayStat(skipCount = 100)))
        val localStats = mapOf(
            localSong.id to com.launchpoint.wavdrop.data.local.entity.TrackStatsEntity(
                songId    = localSong.id,
                contentUri = localSong.uri,
                skipCount  = 5,
            ),
        )
        val result = analyze(backup, currentSongs = listOf(localSong), existingStats = localStats)
        assertTrue("overlay skipCount > local must be mergeable", result.hasMergeableData)
    }

    @Test
    fun `overlay events all duplicate — hasMergeableData false when nothing else to merge (test-re-import)`() {
        val localSong = overlayCurrentSong()
        // Event has a non-null eventId; dedup must use eventId path.
        val evt = overlayEvent(listenedMs = 30_000L)
        val existingFp = setOf("${localSong.id}|${evt.occurredAt}|${evt.eventType}|${evt.listenedMs}")
        val existingIds = setOfNotNull(evt.eventId)   // "desktop-event-1"
        // Stat values equal to local — no increase.
        val localStats = mapOf(
            localSong.id to com.launchpoint.wavdrop.data.local.entity.TrackStatsEntity(
                songId    = localSong.id,
                contentUri = localSong.uri,
                skipCount  = 0,
                playCount  = 0,
            ),
        )
        val backup = overlayBackup(events = listOf(evt))
        val result = analyze(
            backup                    = backup,
            currentSongs              = listOf(localSong),
            existingStats             = localStats,
            existingEventFingerprints = existingFp,
            existingEventIds          = existingIds,
        )
        assertFalse("all overlay events duplicate + stats equal → not mergeable", result.hasMergeableData)
        assertNotNull(result.noOpReason)
    }

    @Test
    fun `overlay with only invalid events and empty library — preview completes not mergeable (test-6)`() {
        // All events are invalid (PLAY with listenedMs=0); no local library to match stats against.
        // Plan: hasWrites=false, hasPreservedOverlayRows=true (unmatched stat) → mergeable via quarantine path.
        // Key assertion: analyze() returns without hanging.
        val backup = overlayBackup(
            stats = listOf(overlayStat()),
            events = listOf(overlayEvent(listenedMs = 0L, eventType = "PLAY")), // invalid shape
        )
        val result = analyze(backup, currentSongs = emptyList())
        // Unmatched overlay stat → hasPreservedOverlayRows → mergeable (quarantine path).
        assertTrue("invalid-event overlay with unmatched stat must be mergeable", result.hasMergeableData)
    }

    @Test
    fun `overlay with only invalid events and no stats — preview completes as no-op (test-6b)`() {
        // Empty trackStats + only invalid events → hasWrites=false, hasPreservedOverlayRows=false.
        // Falls through to no-op.
        val backup = emptyBackup().copy(
            desktopOverlay = BackupDesktopOverlay(
                schemaVersion    = 1,
                producerPlatform = "desktop",
                trackStats       = emptyList(),
                listenEvents     = listOf(overlayEvent(listenedMs = 0L, eventType = "PLAY")),
                rawJson          = "{}",
            ),
        )
        val result = analyze(backup, currentSongs = emptyList())
        assertFalse("overlay with only invalid events and no stats is a no-op", result.hasMergeableData)
        assertNotNull(result.noOpReason)
    }

    @Test
    fun `android-only v2 backup is unaffected by overlay changes (test-8)`() {
        val songId = 99L
        val backup = WavdropBackup(
            exportedAt      = "2026-06-23T00:00:00Z",
            sourceVersion   = BackupFormatVersion.V2,
            songs           = listOf(backupSong(songId)),
            trackStats      = listOf(
                BackupTrackStats(
                    songId               = songId,
                    contentUri           = "content://media/$songId",
                    playCount            = 10,
                    skipCount            = 0,
                    lastPlayedAt         = 1_000L,
                    totalListeningTimeMs = 60_000L,
                    isFavorite           = false,
                    lastListenedAt       = 1_000L,
                ),
            ),
            importBaselines = emptyList(),
        )
        val result = analyze(backup, currentSongs = listOf(song(songId)))
        assertTrue("android-only v2 backup must still be mergeable", result.hasMergeableData)
        assertNull(result.noOpReason)
    }
}
