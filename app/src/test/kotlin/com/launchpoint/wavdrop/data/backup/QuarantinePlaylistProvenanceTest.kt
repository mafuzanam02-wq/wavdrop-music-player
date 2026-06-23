package com.launchpoint.wavdrop.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2-A.2 — Playlist source provenance and fingerprint correctness.
 *
 * Verifies that:
 *  1. Playlist fingerprints use the stable playlist ID, not the display name.
 *  2. Re-importing the same snapshot produces no duplicate playlist entries.
 *  3. Two exports with the same songs but different exportedAt values produce
 *     separate pending rows — this is correct, expected, and temporary behavior
 *     until P2-B introduces cross-snapshot deduplication via durable TrackIdentity.
 *
 * No mock framework used — all inputs constructed inline.
 */
class QuarantinePlaylistProvenanceTest {

    // ── Fixture helpers ───────────────────────────────────────────────────────

    private fun song(id: Long) =
        BackupSong(id, "content://media/$id", "Track $id", "Artist", "Album", 0L, 180_000L, 0L, 1, 2024)

    private fun entry(songId: Long, pos: Int = 0) =
        BackupPlaylistSong(songId, "content://media/$songId", pos, "Track $songId", "Artist", "Album")

    private fun plan(backup: WavdropBackup, existingOriginKeys: Set<String> = emptySet()): QuarantinePlanner.Plan {
        val fp = QuarantinePlanner.backupFingerprint(backup)
        return QuarantinePlanner.plan(
            backup                = backup,
            backupFingerprint     = fp,
            resolvedBackupSongIds = emptySet(),
            existingOriginKeys    = existingOriginKeys,
        )
    }

    // ── Test 1: Same-name playlists with different IDs → distinct fingerprints ─

    /**
     * Two playlists named "Faves" (IDs 10 and 20) both containing the same track
     * at the same position must produce distinct fingerprints. Fingerprinting
     * on display name would silently collapse these two into one fingerprint,
     * allowing a playlist entry from ID=20 to be skipped as a duplicate of ID=10.
     */
    @Test
    fun `same name different id playlists produce distinct entry fingerprints`() {
        val backup = WavdropBackup(
            exportedAt      = "2026-06-23T10:00:00Z",
            songs           = listOf(song(1L)),
            trackStats      = emptyList(),
            importBaselines = emptyList(),
            playlists       = listOf(
                BackupPlaylist(id = 10L, name = "Faves", createdAt = 100L, updatedAt = 200L,
                    songs = listOf(entry(1L, 0))),
                BackupPlaylist(id = 20L, name = "Faves", createdAt = 150L, updatedAt = 250L,
                    songs = listOf(entry(1L, 0))),
            ),
        )

        val result = plan(backup)
        assertEquals(1, result.newTracksCount)
        val candidate = result.newCandidates.single()
        assertEquals(
            "Track in two same-name playlists with different IDs must yield two entries",
            2,
            candidate.playlistEntries.size,
        )

        val fp1 = candidate.playlistEntries[0].originFingerprint
        val fp2 = candidate.playlistEntries[1].originFingerprint
        assertNotEquals(
            "Fingerprints must differ because playlist IDs differ (10 vs 20)",
            fp1,
            fp2,
        )

        // Verify the IDs embedded in the fingerprints
        val ids = candidate.playlistEntries.map { it.sourcePlaylistId }.toSet()
        assertEquals(setOf("10", "20"), ids)
    }

    @Test
    fun `same id playlist with different display names - fingerprint is stable against rename`() {
        // This documents that a playlist renamed between two exports will be treated as the
        // same playlist by fingerprint (ID used), not as two separate playlists.
        val backup1 = WavdropBackup(
            exportedAt      = "2026-06-01T10:00:00Z",
            songs           = listOf(song(5L)),
            trackStats      = emptyList(),
            importBaselines = emptyList(),
            playlists       = listOf(
                BackupPlaylist(id = 99L, name = "Old Name", createdAt = 100L, updatedAt = 200L,
                    songs = listOf(entry(5L, 0))),
            ),
        )
        val backup2 = WavdropBackup(
            exportedAt      = "2026-06-01T10:00:00Z", // same exportedAt → same fingerprint
            songs           = listOf(song(5L)),
            trackStats      = emptyList(),
            importBaselines = emptyList(),
            playlists       = listOf(
                BackupPlaylist(id = 99L, name = "New Name", createdAt = 100L, updatedAt = 300L,
                    songs = listOf(entry(5L, 0))),
            ),
        )

        val fp1 = QuarantinePlanner.backupFingerprint(backup1)
        val fp2 = QuarantinePlanner.backupFingerprint(backup2)
        // Different updatedAt in playlist → payload changes → different backupFingerprints.
        // But the playlist *entry* fingerprint uses playlist ID (99), not name.
        val plan1 = plan(backup1)
        val plan2 = plan(backup2)

        val entryFp1 = plan1.newCandidates.single().playlistEntries.single().originFingerprint
        val entryFp2 = plan2.newCandidates.single().playlistEntries.single().originFingerprint

        // Entry fingerprints include the backup fingerprint (which differs due to different
        // updatedAt in the playlist payload), so they differ as expected — but both contain
        // the playlist ID "99", not the name.
        assertTrue("entry fp must embed playlist ID '99', not name", entryFp1.contains("|pl:99|0"))
        assertTrue("entry fp must embed playlist ID '99', not name", entryFp2.contains("|pl:99|0"))
        assertFalse("must not embed old display name in fingerprint", entryFp1.contains("Old Name"))
        assertFalse("must not embed new display name in fingerprint", entryFp2.contains("New Name"))
    }

    // ── Test 2: Re-importing the same snapshot → no duplicate playlist entries ─

    /**
     * The originKey deduplication guard means that re-importing the same backup
     * snapshot must skip all candidates (including their playlist entries) without
     * inserting duplicates. This test verifies that at the planner level:
     * when all track originKeys are present in existingOriginKeys, newCandidates
     * is empty and newPlaylistEntriesCount is zero.
     */
    @Test
    fun `re-importing same snapshot produces no new playlist entries`() {
        val backup = WavdropBackup(
            exportedAt      = "2026-06-23T10:00:00Z",
            songs           = listOf(song(1L), song(2L)),
            trackStats      = emptyList(),
            importBaselines = emptyList(),
            playlists       = listOf(
                BackupPlaylist(id = 7L, name = "Mix", createdAt = 0L, updatedAt = 0L,
                    songs = listOf(entry(1L, 0), entry(2L, 1))),
            ),
        )

        // First import: record the origin keys
        val fp = QuarantinePlanner.backupFingerprint(backup)
        val existingKeys = setOf(
            QuarantinePlanner.originKey(fp, 1L),
            QuarantinePlanner.originKey(fp, 2L),
        )

        // Second import with same snapshot
        val result = plan(backup, existingOriginKeys = existingKeys)

        assertFalse("no new data on re-import", result.hasNewData)
        assertEquals("no new track candidates", 0, result.newTracksCount)
        assertEquals("no new playlist entries", 0, result.newPlaylistEntriesCount)
        assertEquals("both tracks reported as already present", 2, result.alreadyPresentCount)
    }

    // ── Test 3: Two exports differing only in exportedAt → separate pending rows ─

    /**
     * Two backups of the same library, exported at different times, produce different
     * payloadFingerprints (exportedAt is the first canonical field). This means the
     * same unresolved track imported from each export creates two separate pending rows.
     *
     * This is CORRECT AND EXPECTED behavior in P2-A. The pending table is snapshot-scoped.
     * P2-B must not auto-merge these rows — they need durable TrackIdentity + stable
     * event lineage for safe cross-snapshot deduplication.
     *
     * This test documents the behavior explicitly so it cannot regress silently.
     */
    @Test
    fun `two exports differing only in exportedAt create separate pending rows - expected snapshot isolation`() {
        val songs = listOf(song(10L))
        val playlists = listOf(
            BackupPlaylist(id = 5L, name = "Evening", createdAt = 0L, updatedAt = 0L,
                songs = listOf(entry(10L, 0))),
        )

        val export1 = WavdropBackup(
            exportedAt      = "2026-06-20T08:00:00Z",
            songs           = songs,
            trackStats      = emptyList(),
            importBaselines = emptyList(),
            playlists       = playlists,
        )
        val export2 = WavdropBackup(
            exportedAt      = "2026-06-23T08:00:00Z", // only this field differs
            songs           = songs,
            trackStats      = emptyList(),
            importBaselines = emptyList(),
            playlists       = playlists,
        )

        val fp1 = QuarantinePlanner.backupFingerprint(export1)
        val fp2 = QuarantinePlanner.backupFingerprint(export2)
        assertNotEquals("different exportedAt must produce different fingerprints", fp1, fp2)

        val key1 = QuarantinePlanner.originKey(fp1, 10L)
        val key2 = QuarantinePlanner.originKey(fp2, 10L)
        assertNotEquals("origin keys must be snapshot-scoped (distinct per export)", key1, key2)

        // Simulate: export1 imported first; export2 is now imported
        val resultForExport2 = QuarantinePlanner.plan(
            backup                = export2,
            backupFingerprint     = fp2,
            resolvedBackupSongIds = emptySet(),
            existingOriginKeys    = setOf(key1), // key from export1 already present
        )

        // export2's key differs, so it is NOT treated as a duplicate — it enters quarantine
        assertTrue(
            "Track from second export must be treated as new quarantine row (snapshot isolation)",
            resultForExport2.hasNewData,
        )
        assertEquals(1, resultForExport2.newTracksCount)
        assertEquals(1, resultForExport2.newPlaylistEntriesCount)

        // The playlist entry fingerprint for export2 must also differ from export1's
        val entryFp1 = QuarantinePlanner.plan(
            backup                = export1,
            backupFingerprint     = fp1,
            resolvedBackupSongIds = emptySet(),
            existingOriginKeys    = emptySet(),
        ).newCandidates.single().playlistEntries.single().originFingerprint

        val entryFp2 = resultForExport2.newCandidates.single().playlistEntries.single().originFingerprint
        assertNotEquals(
            "Playlist entry fingerprints must differ across snapshot (include backupFingerprint)",
            entryFp1,
            entryFp2,
        )
    }

    // ── Test 4: Provenance fields on PlaylistEntryCandidate ──────────────────

    @Test
    fun `PlaylistEntryCandidate carries all three provenance fields from source playlist`() {
        val backup = WavdropBackup(
            exportedAt      = "2026-06-23T10:00:00Z",
            songs           = listOf(song(3L)),
            trackStats      = emptyList(),
            importBaselines = emptyList(),
            playlists       = listOf(
                BackupPlaylist(id = 42L, name = "Night Drive", createdAt = 1000L, updatedAt = 2000L,
                    songs = listOf(entry(3L, 5))),
            ),
        )

        val result = plan(backup)
        val entry = result.newCandidates.single().playlistEntries.single()

        assertEquals("Night Drive", entry.playlistName)
        assertEquals(5, entry.position)
        assertEquals("42", entry.sourcePlaylistId)
        assertEquals(1000L, entry.sourcePlaylistCreatedAt)
        assertEquals(2000L, entry.sourcePlaylistUpdatedAt)
        assertTrue("fingerprint embeds playlist ID", entry.originFingerprint.contains("|pl:42|5"))
    }
}
