package com.launchpoint.wavdrop.data.backup

import androidx.room.Room
import com.launchpoint.wavdrop.data.backup.RecoveryTestFixtures.backupEvent
import com.launchpoint.wavdrop.data.backup.RecoveryTestFixtures.backupPlaylistSong
import com.launchpoint.wavdrop.data.backup.RecoveryTestFixtures.backupSong
import com.launchpoint.wavdrop.data.backup.RecoveryTestFixtures.backupStats
import com.launchpoint.wavdrop.data.local.WavdropDatabase
import com.launchpoint.wavdrop.data.local.entity.ImportBaselineEntity
import com.launchpoint.wavdrop.data.local.entity.LyricsOverrideEntity
import com.launchpoint.wavdrop.data.local.entity.PendingBackupExtensionEntity
import com.launchpoint.wavdrop.data.local.entity.PendingTrackEntity
import com.launchpoint.wavdrop.data.local.entity.PlaylistEntity
import com.launchpoint.wavdrop.data.local.entity.PlaylistSongEntity
import com.launchpoint.wavdrop.data.local.entity.SongEntity
import com.launchpoint.wavdrop.data.local.entity.TrackIdentityEntity
import com.launchpoint.wavdrop.data.local.entity.TrackListenEventEntity
import com.launchpoint.wavdrop.data.local.entity.TrackStatsEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Recovery Restore on real Room/SQLite: authoritative semantics (vs. Merge's MAX), clear scope, unmatched preservation,
 * single-transaction rollback. The library (songs table) is the live MediaStore mirror and is never modified by Recovery.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecoveryRestoreRoomTest {

    private lateinit var db: WavdropDatabase
    private lateinit var recovery: RecoveryRestoreRepository
    private lateinit var merge: WavdropBackupImportRepository

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), WavdropDatabase::class.java)
            .allowMainThreadQueries().build()
        recovery = RecoveryRestoreRepository(
            db, db.songDao(), db.trackStatsDao(), db.lyricsOverrideDao(), db.importBaselineDao(), db.playlistDao(),
            db.trackListenEventDao(), db.pendingTrackDao(), db.pendingBackupExtensionDao(),
        )
        merge = WavdropBackupImportRepository(
            db, db.songDao(), db.trackStatsDao(), db.lyricsOverrideDao(), db.importBaselineDao(), db.playlistDao(),
            db.trackListenEventDao(), db.pendingTrackDao(), db.pendingBackupExtensionDao(),
        )
        seedLocalState()
    }

    @After fun tearDown() = db.close()

    private fun songEntity(id: Long) = backupSong(id).let {
        SongEntity(id, it.title, it.artist, it.album, it.albumId, it.duration, it.uri, it.dateAdded, it.trackNumber, it.year, it.folderPath, it.folderName)
    }

    private fun localEvent(id: String?, songId: Long, at: Long, source: String = TrackListenEventEntity.SOURCE_WAVDROP_PLAYBACK) =
        TrackListenEventEntity(
            songId = songId, eventType = TrackListenEventEntity.TYPE_PLAY, occurredAt = at, listenedMs = 60_000L,
            durationMs = 180_000L, source = source, eventId = id,
        )

    private fun seedLocalState() = runBlocking {
        db.songDao().upsertAll(listOf(songEntity(1), songEntity(2), songEntity(3)))
        db.trackIdentityDao().insert(TrackIdentityEntity("uuid-1", 1L, 1L, 1L))
        db.trackStatsDao().insertAllForRecovery(
            listOf(
                TrackStatsEntity(1, "content://media/1", playCount = 100, skipCount = 20, lastPlayedAt = RecoveryTestFixtures.NOW, lastListenedAt = RecoveryTestFixtures.NOW, totalListeningTimeMs = 9_000_000L, isFavorite = true),
                TrackStatsEntity(2, "content://media/2", playCount = 5, isFavorite = true),
            ),
        )
        db.trackListenEventDao().insertAll(
            listOf(
                localEvent("A", 1, 1_000L), localEvent("B", 1, 2_000L), localEvent("C", 1, 3_000L),
                localEvent(null, 1, 4_000L, source = TrackListenEventEntity.SOURCE_BLACKPLAYER_IMPORT),
            ),
        )
        val roadtrip = db.playlistDao().insertPlaylist(PlaylistEntity(name = "Roadtrip", createdAt = 1, updatedAt = 1))
        val gym = db.playlistDao().insertPlaylist(PlaylistEntity(name = "Gym", createdAt = 2, updatedAt = 2))
        db.playlistDao().insertSongs(listOf(PlaylistSongEntity(roadtrip, 2, 0), PlaylistSongEntity(roadtrip, 1, 1), PlaylistSongEntity(gym, 3, 0)))
        db.lyricsOverrideDao().upsert(LyricsOverrideEntity(2, "content://media/2", "local lyrics", 5_000L))
        db.importBaselineDao().upsertBaseline(ImportBaselineEntity(3, "blackplayer", "k", 4, 1, 9L))
        db.pendingTrackDao().insertTrack(
            PendingTrackEntity(originKey = "other|7", backupFingerprint = "other", backupSongId = "7", title = "Old", artist = "A", album = "B",
                duration = 1L, trackNumber = 1, year = 2000, sourceUri = null, sourceFolderPath = null, restoredAt = 1L),
        )
        db.pendingBackupExtensionDao().upsert(PendingBackupExtensionEntity(DESKTOP_OVERLAY_ROOT, "{\"old\":true}", 1L))
    }

    private fun backup() = RecoveryTestFixtures.v2Backup(
        songs = listOf(backupSong(1), backupSong(9, "Gone Song")),
        stats = listOf(backupStats(1, 40, 5, false), backupStats(9, 7, 0, true)),
        events = listOf(
            backupEvent(1, "A", 1_000L), backupEvent(1, "D", 5_000L), backupEvent(9, "E", 6_000L, "Gone Song"),
        ),
        playlists = listOf(
            BackupPlaylist(11, "Roadtrip", 10L, 20L, listOf(backupPlaylistSong(9, 0).copy(title = "Gone Song"), backupPlaylistSong(1, 1))),
            BackupPlaylist(12, "Sleep", 30L, 40L, listOf(backupPlaylistSong(1, 0))),
        ),
        lyrics = listOf(BackupLyricsOverride(1, "content://media/1", "backup lyrics", 7_000L)),
        baselines = listOf(BackupImportBaseline(1, "blackplayer", "kk", 3, 1, 8L)),
        overlayRawJson = "{\"schemaVersion\":1}",
    )

    private suspend fun statsOf(id: Long) = db.trackStatsDao().getStatsBySongId(id)

    // ── Authoritative semantics ───────────────────────────────────────────────

    @Test fun `recovery makes the backup authoritative where Merge keeps the higher local values`() = runBlocking {
        recovery.applyRecovery(backup())
        val s1 = statsOf(1)!!
        assertEquals(40, s1.playCount); assertEquals(5, s1.skipCount); assertFalse(s1.isFavorite)
        assertEquals(RecoveryTestFixtures.NOW - 900_000L, s1.lastListenedAt) // exact v2 lastListenedAt, not MAX
        assertEquals(RecoveryTestFixtures.NOW - 1_000_000L, s1.lastPlayedAt)
    }

    @Test fun `merge on the same starting state keeps 100-20-true (merge is unchanged)`() = runBlocking {
        merge.applyImport(backup())
        val s1 = statsOf(1)!!
        assertEquals(100, s1.playCount); assertEquals(20, s1.skipCount); assertTrue(s1.isFavorite)
    }

    @Test fun `a local favourite the backup does not mark is cleared in recovery only`() = runBlocking {
        val r = recovery.applyRecovery(backup())
        assertNull("song 2 is absent from the backup, so its local stats row (and favourite) is replaced", statsOf(2))
        assertEquals(2, r.recovery!!.favoritesCleared) // song1 (backup false) + song2 (not in backup)
    }

    @Test fun `events become the backup set - A and D, never B and C, and nothing is fabricated`() = runBlocking {
        recovery.applyRecovery(backup())
        val ids = db.trackListenEventDao().getAllSnapshot().mapNotNull { it.eventId }.toSet()
        assertEquals(setOf("A", "D"), ids)
        assertEquals("no eventId is duplicated", 2, db.trackListenEventDao().getAllSnapshot().count { it.eventId != null })
    }

    @Test fun `merge event semantics stay union - A B C D`() = runBlocking {
        merge.applyImport(backup())
        assertEquals(setOf("A", "B", "C", "D"), db.trackListenEventDao().getAllSnapshot().mapNotNull { it.eventId }.toSet())
    }

    @Test fun `events from sources the backup never exports survive recovery`() = runBlocking {
        recovery.applyRecovery(backup())
        assertEquals(1, db.trackListenEventDao().getAllSnapshot().count { it.source == TrackListenEventEntity.SOURCE_BLACKPLAYER_IMPORT })
    }

    @Test fun `playlists become backup-authoritative with backup order, and local-only playlists are removed`() = runBlocking {
        val r = recovery.applyRecovery(backup())
        val names = db.playlistDao().getAllPlaylistsSnapshot().map { it.name }.toSet()
        assertEquals(setOf("Roadtrip", "Sleep"), names)
        assertNull(db.playlistDao().findByName("Gym"))
        val roadtrip = db.playlistDao().findByName("Roadtrip")!!
        assertEquals(10L, roadtrip.createdAt); assertEquals(20L, roadtrip.updatedAt)
        // song 9 does not exist on this device: only the matched entry is restored, at a contiguous position; the other is preserved pending.
        assertEquals(listOf(1L), db.playlistDao().getSongsForPlaylistSnapshot(roadtrip.playlistId).map { it.songId })
        assertEquals(1, r.recovery!!.playlistEntriesPreserved)
        assertEquals(2, r.recovery!!.playlistsRestored)
    }

    @Test fun `playlist order and duplicate entries of one backup song are preserved`() = runBlocking {
        val b = RecoveryTestFixtures.v2Backup(
            songs = listOf(backupSong(1), backupSong(2), backupSong(3)),
            stats = listOf(backupStats(1, 1, 0, false)),
            playlists = listOf(BackupPlaylist(1, "Mix", 1, 1, listOf(
                backupPlaylistSong(3, 0), backupPlaylistSong(1, 1), backupPlaylistSong(3, 2), backupPlaylistSong(2, 3)))),
        )
        recovery.applyRecovery(b)
        val p = db.playlistDao().findByName("Mix")!!
        assertEquals(listOf(3L, 1L, 3L, 2L), db.playlistDao().getSongsForPlaylistSnapshot(p.playlistId).sortedBy { it.position }.map { it.songId })
    }

    @Test fun `lyrics baselines and the desktop overlay are replaced by the backup`() = runBlocking {
        recovery.applyRecovery(backup())
        val lyrics = db.lyricsOverrideDao().getAllSnapshot()
        assertEquals(listOf("backup lyrics"), lyrics.map { it.lyrics })
        assertEquals(listOf("kk"), db.importBaselineDao().getAllImportBaselinesSnapshot().map { it.sourceKey })
        assertEquals("{\"schemaVersion\":1}", db.pendingBackupExtensionDao().getByRootName(DESKTOP_OVERLAY_ROOT)!!.rawJson)
    }

    @Test fun `a backup with no overlay removes the stored one so state matches the backup`() = runBlocking {
        recovery.applyRecovery(RecoveryTestFixtures.v2Backup(overlayRawJson = null))
        assertNull(db.pendingBackupExtensionDao().getByRootName(DESKTOP_OVERLAY_ROOT))
    }

    // ── Preservation and scope ────────────────────────────────────────────────

    @Test fun `unmatched backup history is preserved in the quarantine and earlier pending rows are kept`() = runBlocking {
        val r = recovery.applyRecovery(backup())
        val keys = db.pendingTrackDao().getAllOriginKeys()
        assertTrue("other|7" in keys)
        assertTrue(keys.any { it.endsWith("|9") })
        assertEquals(1, r.recovery!!.unmatchedTracksPreserved)
        assertTrue(r.pendingEventsPreserved >= 1)
        assertEquals("unmatched stats must not become a live row for a song that is not on the device", null, statsOf(9))
    }

    @Test fun `recovery never touches the library, identities or audio-file rows`() = runBlocking {
        val songsBefore = db.songDao().getAllSongsSnapshot()
        val identitiesBefore = db.trackIdentityDao().getAllSnapshot()
        recovery.applyRecovery(backup())
        assertEquals(songsBefore, db.songDao().getAllSongsSnapshot())
        assertEquals(identitiesBefore, db.trackIdentityDao().getAllSnapshot())
    }

    @Test fun `recovery from the same backup twice is idempotent`() = runBlocking {
        recovery.applyRecovery(backup())
        val first = dump()
        recovery.applyRecovery(backup())
        assertEquals(first, dump())
    }

    @Test fun `recovery from an empty-state backup replaces local state with nothing, not a no-op`() = runBlocking {
        val r = recovery.applyRecovery(RecoveryTestFixtures.v2Backup(songs = emptyList(), stats = emptyList()))
        assertFalse(r.isNoOp)
        assertTrue(db.trackStatsDao().getAllStatsSnapshot().isEmpty())
        assertTrue(db.playlistDao().getAllPlaylistsSnapshot().isEmpty())
    }

    @Test fun `the impact preview reports what recovery would change without writing`() = runBlocking {
        val before = dump()
        val impact = recovery.previewImpact(backup())
        assertEquals(1, impact.matchedTracks)
        assertEquals(1, impact.localPlaylistsToRemove) // Gym
        assertEquals(2, impact.localFavoritesToClear)
        assertEquals(2, impact.localEventsNotInBackup) // B and C (A is represented)
        assertEquals(before, dump())
    }

    // ── Atomicity ─────────────────────────────────────────────────────────────

    private suspend fun dump(): String = buildString {
        // Auto-generated row ids are excluded so two applications of the same backup compare equal.
        append(db.trackStatsDao().getAllStatsSnapshot())
        append(db.trackListenEventDao().getAllSnapshot().map { listOf(it.songId, it.eventType, it.occurredAt, it.listenedMs, it.source, it.eventId) }.sortedBy { it.toString() })
        append(db.lyricsOverrideDao().getAllSnapshot()); append(db.importBaselineDao().getAllImportBaselinesSnapshot())
        db.playlistDao().getAllPlaylistsSnapshot().sortedBy { it.name }.forEach { p ->
            append(listOf(p.name, p.createdAt, p.updatedAt))
            append(db.playlistDao().getSongsForPlaylistSnapshot(p.playlistId).map { it.songId to it.position })
        }
        append(db.pendingTrackDao().getAllOriginKeys().sorted()); append(db.pendingBackupExtensionDao().getByRootName(DESKTOP_OVERLAY_ROOT)?.rawJson)
        append(db.songDao().getAllSongsSnapshot())
    }

    @Test fun `a failure at any stage rolls every destructive change back`() = runBlocking {
        val stages = listOf(
            "planned", "statsCleared", "eventsCleared", "lyricsAndBaselinesCleared", "playlistsCleared",
            "statsWritten", "eventsWritten", "lyricsAndBaselinesWritten", "playlistsWritten", "quarantineWritten",
        )
        val before = dump()
        for (stage in stages) {
            recovery.stageHook = { if (it == stage) throw IllegalStateException("injected at $stage") }
            try {
                recovery.applyRecovery(backup())
                fail("expected a failure at $stage")
            } catch (e: IllegalStateException) {
                assertTrue(e.message!!.contains(stage))
            }
            assertEquals("database must be unchanged after a failure at $stage", before, dump())
        }
        recovery.stageHook = null
        assertNotNull(recovery.applyRecovery(backup()))
        assertTrue("the same repository still works after the injected failures", statsOf(1)!!.playCount == 40)
    }
}
