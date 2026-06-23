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
}
