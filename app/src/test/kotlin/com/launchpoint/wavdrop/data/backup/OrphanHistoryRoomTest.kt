package com.launchpoint.wavdrop.data.backup

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import com.launchpoint.wavdrop.data.backup.RecoveryTestFixtures.backupEvent
import com.launchpoint.wavdrop.data.backup.RecoveryTestFixtures.backupPlaylistSong
import com.launchpoint.wavdrop.data.backup.RecoveryTestFixtures.backupSong
import com.launchpoint.wavdrop.data.backup.RecoveryTestFixtures.backupStats
import com.launchpoint.wavdrop.data.backup.wdbk.WdbkReader
import com.launchpoint.wavdrop.data.backup.wdbk.WdbkTestSupport
import com.launchpoint.wavdrop.data.backup.wdbk.WdbkWriter
import com.launchpoint.wavdrop.data.local.WavdropDatabase
import com.launchpoint.wavdrop.data.local.entity.SongEntity
import com.launchpoint.wavdrop.data.settings.AppSettingsRepository
import com.launchpoint.wavdrop.data.settings.HomeLayoutSettingsRepository
import com.launchpoint.wavdrop.data.settings.LibraryScanSettingsRepository
import com.launchpoint.wavdrop.data.settings.ResumeBehaviorSettingsRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/**
 * P5-STATS-17C (F2b): history whose song id has NO descriptor in the backup's own `songs` list must be quarantined, not dropped.
 * Real Room/SQLite and the production exporter/writer/reader/parser and both restore repositories. Everything is synthetic.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OrphanHistoryRoomTest {

    private enum class Fmt { JSON, WDBK }
    private enum class Mode { MERGE, RECOVERY }

    private val files = mutableListOf<File>()
    private val dbs = mutableListOf<WavdropDatabase>()

    @After fun tearDown() { dbs.forEach { it.close() }; files.forEach { it.delete() } }

    private inner class Node(libraryIds: List<Long> = listOf(5L)) {
        val db: WavdropDatabase = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), WavdropDatabase::class.java)
            .allowMainThreadQueries().build().also { dbs += it }
        private val file = File(RuntimeEnvironment.getApplication().cacheDir, "p17c-${UUID.randomUUID()}.preferences_pb").also { files += it }
        private val store = PreferenceDataStoreFactory.create { file }
        val merge = WavdropBackupImportRepository(
            db, db.songDao(), db.trackStatsDao(), db.lyricsOverrideDao(), db.importBaselineDao(), db.playlistDao(),
            db.trackListenEventDao(), db.pendingTrackDao(), db.pendingBackupExtensionDao(),
        )
        val recovery = RecoveryRestoreRepository(
            db, db.songDao(), db.trackStatsDao(), db.lyricsOverrideDao(), db.importBaselineDao(), db.playlistDao(),
            db.trackListenEventDao(), db.pendingTrackDao(), db.pendingBackupExtensionDao(),
        )
        val exporter = WavdropBackupRepository(
            RuntimeEnvironment.getApplication(), store, db.songDao(), db.trackStatsDao(), db.importBaselineDao(), db.lyricsOverrideDao(),
            db.playlistDao(), db.trackListenEventDao(), db.pendingBackupExtensionDao(), db.pendingTrackDao(),
            AppSettingsRepository(store), HomeLayoutSettingsRepository(store), LibraryScanSettingsRepository(store),
            ResumeBehaviorSettingsRepository(store),
        )
        val pending = PendingHistoryStore(db.pendingTrackDao())

        init { runBlocking { db.songDao().upsertAll(libraryIds.map(::songEntity)) } }

        suspend fun restore(backup: WavdropBackup, mode: Mode): WavdropBackupImportApplyResult =
            if (mode == Mode.MERGE) merge.applyImport(backup) else recovery.applyRecovery(backup)

        suspend fun export(f: Fmt): ByteArray = when (f) {
            Fmt.JSON -> exporter.buildBackupJson().toByteArray(Charsets.UTF_8)
            Fmt.WDBK -> ByteArrayOutputStream().also { WdbkWriter().write(exporter.buildWdbkExportSnapshot(), it) }.toByteArray()
        }

        suspend fun dump(): String = buildString {
            append(db.songDao().getAllSongsSnapshot()); append(db.trackStatsDao().getAllStatsSnapshot())
            append(db.trackListenEventDao().getAllSnapshot()); append(db.lyricsOverrideDao().getAllSnapshot())
            append(db.importBaselineDao().getAllImportBaselinesSnapshot())
            for (p in db.playlistDao().getAllPlaylistsSnapshot()) {
                append(p); append(db.playlistDao().getSongsForPlaylistSnapshot(p.playlistId))
            }
            append(pending.loadAll())
        }
    }

    private fun songEntity(id: Long) = backupSong(id).let {
        SongEntity(id, it.title, it.artist, it.album, it.albumId, it.duration, it.uri, it.dateAdded, it.trackNumber, it.year, it.folderPath, it.folderName)
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    /** Orphan 90 has every record kind; 91 only events (one with an event id, one without); 92 only blank-URI stats. Song 1 has a descriptor. */
    private fun orphanBackup(id: String = "00000000-0000-0000-0000-0000000000a1") = RecoveryTestFixtures.v2Backup(
        songs = listOf(backupSong(1, "Gone Song")),                    // descriptor-bearing, absent from the destination library
        stats = listOf(backupStats(1, 40, 5, true), backupStats(90, 17, 3, true), backupStats(92, 4, 0, false).copy(contentUri = "")),
        events = listOf(
            backupEvent(90, "ev-90a", 1_000L), backupEvent(90, null, 2_000L), backupEvent(91, "ev-91a", 3_000L), backupEvent(91, null, 3_000L + 1),
        ),
        playlists = listOf(BackupPlaylist(11, "Roadtrip", 10L, 20L, listOf(backupPlaylistSong(90, 0), backupPlaylistSong(1, 1)))),
        lyrics = listOf(BackupLyricsOverride(90, "content://media/90", "orphan lyrics", 7_000L)),
        baselines = listOf(BackupImportBaseline(90, "blackplayer", "kk", 3, 1, 8L)),
        backupId = id,
    )

    private fun serialize(f: Fmt, b: WavdropBackup): ByteArray = when (f) {
        Fmt.JSON -> WavdropBackupExporterV2.toJson(b).toByteArray(Charsets.UTF_8)
        Fmt.WDBK -> WdbkTestSupport.write(b).first
    }

    private fun parse(f: Fmt, bytes: ByteArray): WavdropBackup {
        val r = when (f) {
            Fmt.JSON -> WavdropBackupParser.parse(String(bytes, Charsets.UTF_8))
            Fmt.WDBK -> WdbkReader().read(ByteArrayInputStream(bytes), RecoveryTestFixtures.NOW + 1_000_000L)
        }
        assertNull(r.error, r.error)
        assertEquals(BackupIntegrityStatus.VERIFIED, r.integrityStatus)
        return r.backup!!
    }

    private fun key(b: WavdropBackup, songId: Long) = QuarantinePlanner.originKey(QuarantinePlanner.backupFingerprint(b), songId)

    private suspend fun counts(n: Node) = listOf(
        n.db.pendingTrackDao().getAllOriginKeys().size, n.db.pendingTrackDao().getAllEventFingerprints().size,
        n.db.pendingTrackDao().getAllLyricsFingerprints().size, n.db.pendingTrackDao().getAllBaselineFingerprints().size,
        n.db.pendingTrackDao().getAllPlaylistEntryFingerprints().size,
    )

    /** tracks 3 (song 1, orphans 90, 91, 92 -> 4), events 4, lyrics 1, baselines 1, playlist entries 2. */
    private val expected = listOf(4, 4, 1, 1, 2)

    // ── the defect ────────────────────────────────────────────────────────────

    @Test fun `orphan history is quarantined in Merge and Recovery with every record kind and unknown metadata left unknown`() = runBlocking {
        for (f in Fmt.values()) for (mode in Mode.values()) {
            val b = parse(f, serialize(f, orphanBackup()))
            val n = Node()
            n.restore(b, mode)
            assertEquals("$f $mode", expected, counts(n))
            val h = n.pending.loadAll()!!

            val t90 = h.core.tracks.single { it.backupSongId == "90" }
            assertEquals("snapshot-scoped origin key, original backup song id", key(b, 90), t90.originKey)
            assertEquals("stated consistently by the orphan's own records", "Song 90", t90.title)
            assertEquals("content://media/90", t90.sourceUri)
            assertEquals("a descriptor-less orphan has no duration or folder: unknown, not invented", 0L, t90.duration)
            assertNull(t90.sourceFolderPath)
            val s90 = h.core.stats.single { it.trackOriginKey == t90.originKey }
            assertEquals(listOf(17, 3, true), listOf(s90.playCount, s90.skipCount, s90.isFavorite))
            assertEquals(2, h.events.count { it.trackOriginKey == t90.originKey })
            assertEquals("orphan lyrics", h.core.lyricsOverrides.single { it.trackOriginKey == t90.originKey }.lyrics)
            assertEquals(3, h.core.importBaselines.single { it.trackOriginKey == t90.originKey }.playCount)
            assertEquals(1, h.core.playlistEntries.count { it.trackOriginKey == t90.originKey })

            // 92: only a stats row with a blank URI -> every descriptive field unknown.
            val t92 = h.core.tracks.single { it.backupSongId == "92" }
            assertEquals(listOf("", "", "", 0L, null), listOf(t92.title, t92.artist, t92.album, t92.duration, t92.sourceUri))

            // 91: events only, no stats row at all (never fabricated).
            val t91 = h.core.tracks.single { it.backupSongId == "91" }
            assertTrue(h.core.stats.none { it.trackOriginKey == t91.originKey })
            assertEquals(2, h.events.count { it.trackOriginKey == t91.originKey })

            // The descriptor-bearing unresolved song is still quarantined with its descriptor.
            assertEquals("Gone Song", h.core.tracks.single { it.backupSongId == "1" }.title)
        }
    }

    @Test fun `an orphan id that equals an unrelated live song id never touches that song`() = runBlocking {
        for (mode in Mode.values()) {
            val n = Node(libraryIds = listOf(5L))
            // A different song that merely shares the numeric id (MediaStore ids are not identity): its tags match nothing in the backup.
            n.db.songDao().upsertAll(listOf(songEntity(90).copy(title = "Unrelated", artist = "Someone", album = "Else")))
            val before = n.db.songDao().getAllSongsSnapshot()
            n.restore(orphanBackup(), mode)
            assertNull("$mode: live song 90 got no statistics from orphan id 90", n.db.trackStatsDao().getStatsBySongId(90))
            assertTrue(n.db.trackListenEventDao().getAllSnapshot().none { it.songId == 90L })
            assertTrue(n.db.lyricsOverrideDao().getAllSnapshot().isEmpty())
            assertTrue(n.db.importBaselineDao().getAllImportBaselinesSnapshot().isEmpty())
            assertEquals(before, n.db.songDao().getAllSongsSnapshot())
            assertEquals(expected, counts(n))
        }
    }

    @Test fun `orphan-only backup is previewed as actionable, applied, then reported up to date`() = runBlocking {
        val x = RecoveryTestFixtures.v2Backup(songs = emptyList(), stats = listOf(backupStats(90, 17, 3, true)))
        val n = Node()
        assertTrue("preview offers an orphan-only backup", n.merge.previewMerge(x).hasMergeableData)
        assertFalse(n.merge.applyImport(x).isNoOp)
        assertEquals(1, n.db.pendingTrackDao().getAllOriginKeys().size)
        assertFalse(n.merge.previewMerge(x).hasMergeableData)
        assertTrue(n.merge.applyImport(x).isNoOp)
    }

    @Test fun `repeated Merge and repeated Recovery add nothing`() = runBlocking {
        val b = orphanBackup()
        val m = Node(); m.restore(b, Mode.MERGE)
        val afterM = m.dump()
        assertTrue(m.restore(b, Mode.MERGE).isNoOp)
        assertEquals(afterM, m.dump())

        val r = Node(); r.restore(b, Mode.RECOVERY)
        val afterR = r.pending.loadAll()
        r.restore(b, Mode.RECOVERY)
        assertEquals(afterR, r.pending.loadAll())
        assertEquals(expected, counts(r))
    }

    @Test fun `matched and unmatched descriptor behaviour is unchanged alongside orphans`() = runBlocking {
        val n = Node(libraryIds = listOf(5L))
        val b = RecoveryTestFixtures.v2Backup(
            songs = listOf(backupSong(5), backupSong(1, "Gone Song")),
            stats = listOf(backupStats(5, 33, 2, false), backupStats(1, 40, 5, true), backupStats(90, 17, 3, true)),
        )
        n.restore(b, Mode.MERGE)
        assertEquals("matched song restored live", 33, n.db.trackStatsDao().getStatsBySongId(5)!!.playCount)
        assertEquals(listOf(2, 0, 0, 0, 0), counts(n).let { listOf(it[0], it[1], it[2], it[3], it[4]) })
        val h = n.pending.loadAll()!!
        assertEquals(setOf("1", "90"), h.core.tracks.map { it.backupSongId }.toSet())
    }

    @Test fun `a conflict with a carried pending section fails the whole restore and rolls everything back`() = runBlocking {
        for (mode in Mode.values()) {
            val b = orphanBackup()
            val k90 = key(b, 90)
            val clashing = PendingHistoryBackup(
                PendingHistoryCore(
                    tracks = listOf(PendingHistoryTrack(k90, QuarantinePlanner.backupFingerprint(b), "90", "Different title", "x", "y", 1L, 0, 0, null, null, 5L, "PENDING")),
                    stats = emptyList(), lyricsOverrides = emptyList(), importBaselines = emptyList(), playlistEntries = emptyList(),
                ),
                emptyList(),
            )
            val n = Node(libraryIds = listOf(5L))
            val before = n.dump()
            try {
                n.restore(b.copy(pendingHistory = clashing), mode)
                fail("$mode: conflicting quarantine must not be ignored")
            } catch (_: PendingHistoryConflictException) { }
            assertEquals("$mode: orphan rows, live rows and everything else rolled back", before, n.dump())
            assertEquals(listOf(0, 0, 0, 0, 0), counts(n))
        }
    }

    @Test fun `a failure after the orphan rows were written rolls them back in Recovery`() = runBlocking {
        val n = Node()
        n.recovery.stageHook = { if (it == "quarantineWritten") throw IllegalStateException("boom") }
        val before = n.dump()
        try { n.recovery.applyRecovery(orphanBackup()); fail("expected failure") } catch (_: IllegalStateException) { }
        assertEquals(before, n.dump())
        assertEquals(listOf(0, 0, 0, 0, 0), counts(n))
    }

    // ── carried forward through later generations (17B section) ───────────────

    private fun chain(f: Fmt, mode: Mode) = runBlocking {
        val b = Node(); b.restore(parse(f, serialize(f, orphanBackup())), mode)
        assertEquals(expected, counts(b))
        val original = b.pending.loadAll()!!
        var previous = b
        var firstPending: Map<String, List<Byte>>? = null
        for (hop in 1..4) {
            val artifact = previous.export(f)
            val parsed = parse(f, artifact)
            assertNotNull(parsed.pendingHistory)
            if (f == Fmt.WDBK) {
                val entries = WdbkTestSupport.unzip(artifact).filterKeys { it.startsWith("extensions/pending-history") }.mapValues { it.value.toList() }
                if (firstPending == null) firstPending = entries
                assertEquals("hop $hop: no amplification", firstPending, entries)
            }
            val next = Node()
            next.restore(parsed, mode)
            assertEquals("hop $hop: no growth", expected, counts(next))
            assertEquals("hop $hop: rows survive unchanged", original, next.pending.loadAll())
            assertTrue("hop $hop: no live history invented", next.db.trackStatsDao().getAllStatsSnapshot().isEmpty())
            previous = next
        }
    }

    @Test fun `A to B to C to D to E - JSON - Merge`() = chain(Fmt.JSON, Mode.MERGE)
    @Test fun `A to B to C to D to E - JSON - Recovery`() = chain(Fmt.JSON, Mode.RECOVERY)
    @Test fun `A to B to C to D to E - WDBK - Merge`() = chain(Fmt.WDBK, Mode.MERGE)
    @Test fun `A to B to C to D to E - WDBK - Recovery`() = chain(Fmt.WDBK, Mode.RECOVERY)

    // ── legacy input, integrity, overlay ──────────────────────────────────────

    @Test fun `a legacy v1 backup with an orphan keeps missing optional fields absent`() = runBlocking {
        val v1 = WavdropBackup(
            exportedAt = "2026-06-11T10:00:00Z", songs = emptyList(),
            trackStats = listOf(backupStats(90, 17, 3, true).copy(lastListenedAt = null)), importBaselines = emptyList(),
        )
        val r = WavdropBackupParser.parse(WavdropBackupExporter.toJson(v1))
        assertNull(r.error, r.error)
        val n = Node()
        assertFalse(n.merge.applyImport(r.backup!!).isNoOp)
        val s = n.pending.loadAll()!!.core.stats.single()
        assertNull("a v1 stats row has no lastListenedAt: kept null, not guessed", s.lastListenedAt)
        assertEquals(17, s.playCount)
    }

    @Test fun `tampered or incomplete orphan records are rejected, not accepted as history`() {
        val json = WavdropBackupExporterV2.toJson(RecoveryTestFixtures.v2Backup(songs = emptyList(), stats = listOf(backupStats(90, 10, 1, false))))
        val tampered = WavdropBackupParser.parse(RecoveryTestFixtures.tampered(json))
        assertTrue("integrity mismatch is not a verified backup", tampered.error != null || tampered.integrityStatus != BackupIntegrityStatus.VERIFIED)
        val noId = WavdropBackupParser.parse(json.replaceFirst(Regex("\"songId\"\\s*:\\s*\"?90\"?\\s*,?"), ""))
        assertTrue("a record with no song id at all is rejected", noId.error != null || noId.backup == null)
    }

    @Test fun `Desktop overlay handling is unchanged when orphans are present`() = runBlocking {
        val n = Node()
        n.restore(orphanBackup().copy(desktopOverlay = RecoveryTestFixtures.v2Backup(overlayRawJson = WdbkTestSupport.OVERLAY_RAW).desktopOverlay), Mode.MERGE)
        assertNotNull(n.db.pendingBackupExtensionDao().getByRootName(DESKTOP_OVERLAY_ROOT))
        assertEquals(expected, counts(n))
    }
}
