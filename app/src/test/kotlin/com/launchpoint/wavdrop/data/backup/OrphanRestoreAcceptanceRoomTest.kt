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
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * P5-STATS-17D acceptance on a FILE-backed Room database (so it can be closed and reopened), through the production exporter, writer,
 * reader, parser and both restore repositories. JVM/Robolectric with a real SQLite engine: this is NOT a device test.
 *
 * Story: an original library (songs 1..7) with history is exported and verified; a destination library holds only songs 1..3.
 * Songs 4 (descriptor, unavailable), 5/6/7 (descriptor stripped from the backup: descriptor-less orphans) must be quarantined.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OrphanRestoreAcceptanceRoomTest {

    private enum class Fmt { JSON, WDBK }
    private enum class Mode { MERGE, RECOVERY }

    private val files = mutableListOf<File>()
    private val dbs = mutableListOf<WavdropDatabase>()

    @After fun tearDown() { dbs.forEach { runCatching { it.close() } }; files.forEach { it.delete() } }

    private fun app() = RuntimeEnvironment.getApplication()

    private inner class Env(val db: WavdropDatabase) {
        private val file = File(app().cacheDir, "p17d-${UUID.randomUUID()}.preferences_pb").also { files += it }
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
            app(), store, db.songDao(), db.trackStatsDao(), db.importBaselineDao(), db.lyricsOverrideDao(),
            db.playlistDao(), db.trackListenEventDao(), db.pendingBackupExtensionDao(), db.pendingTrackDao(),
            AppSettingsRepository(store), HomeLayoutSettingsRepository(store), LibraryScanSettingsRepository(store),
            ResumeBehaviorSettingsRepository(store),
        )
        val pending = PendingHistoryStore(db.pendingTrackDao())

        suspend fun restore(b: WavdropBackup, mode: Mode) = if (mode == Mode.MERGE) merge.applyImport(b) else recovery.applyRecovery(b)

        suspend fun export(f: Fmt): ByteArray = when (f) {
            Fmt.JSON -> exporter.buildBackupJson().toByteArray(Charsets.UTF_8)
            Fmt.WDBK -> ByteArrayOutputStream().also { WdbkWriter().write(exporter.buildWdbkExportSnapshot(), it) }.toByteArray()
        }

        suspend fun dump(): String = buildString {
            append(db.songDao().getAllSongsSnapshot()); append(db.trackStatsDao().getAllStatsSnapshot())
            append(db.trackListenEventDao().getAllSnapshot()); append(db.lyricsOverrideDao().getAllSnapshot())
            append(db.importBaselineDao().getAllImportBaselinesSnapshot())
            for (p in db.playlistDao().getAllPlaylistsSnapshot()) { append(p); append(db.playlistDao().getSongsForPlaylistSnapshot(p.playlistId)) }
            append(pending.loadAll())
        }
    }

    private fun songEntity(id: Long) = backupSong(id).let {
        SongEntity(id, it.title, it.artist, it.album, it.albumId, it.duration, it.uri, it.dateAdded, it.trackNumber, it.year, it.folderPath, it.folderName)
    }

    private fun memoryDb(library: List<Long>) = Room.inMemoryDatabaseBuilder(app(), WavdropDatabase::class.java)
        .allowMainThreadQueries().build().also { dbs += it; runBlocking { it.songDao().upsertAll(library.map(::songEntity)) } }

    private fun fileDb(name: String) = Room.databaseBuilder(app(), WavdropDatabase::class.java, name).allowMainThreadQueries().build().also { dbs += it }

    // ── fixtures ──────────────────────────────────────────────────────────────

    /** History for songs 1..7. Events: some carry stable ids ("ev-…"), some are legacy (null). */
    private fun seed(): WavdropBackup = RecoveryTestFixtures.v2Backup(
        songs = (1L..7L).map { backupSong(it) },
        stats = (1L..7L).map { backupStats(it, 10 + it.toInt(), it.toInt(), it % 2 == 0L) },
        events = (1L..7L).flatMap { id ->
            listOf(backupEvent(id, "ev-$id-a", 1_000L * id), backupEvent(id, null, 1_000L * id + 1))
        },
        playlists = listOf(BackupPlaylist(11, "Roadtrip", 10L, 20L, listOf(1L, 4L, 5L).mapIndexed { i, id -> backupPlaylistSong(id, i) })),
        lyrics = listOf(BackupLyricsOverride(4, "content://media/4", "lyrics four", 7_000L), BackupLyricsOverride(1, "content://media/1", "lyrics one", 7_001L)),
        baselines = listOf(BackupImportBaseline(5, "blackplayer", "k5", 3, 1, 8L)),
        backupId = "00000000-0000-0000-0000-0000000000d1",
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

    /**
     * Backup A as produced and verified by the real exporter of the ORIGINAL device, then with the descriptors of songs 5, 6, 7 removed
     * (what a legacy or damaged export looks like) and tags rewritten: 6's records disagree, 7's state nothing at all.
     */
    private fun backupFromOriginal(f: Fmt): WavdropBackup = runBlocking {
        val original = Env(memoryDb((1L..7L).toList()))
        original.merge.applyImport(seed())
        val a = parse(f, original.export(f))
        assertEquals("the original export carries every descriptor", 7, a.songs.size)
        val stripped = a.copy(
            songs = a.songs.filter { it.id !in setOf(5L, 6L, 7L) },
            listenEvents = a.listenEvents.map { e ->
                when (e.songId) {
                    6L -> e.copy(title = if (e.eventId != null) "Six A" else "Six B")
                    7L -> e.copy(title = "", artist = "", album = "", contentUri = "")
                    else -> e
                }
            },
            trackStats = a.trackStats.map { if (it.songId == 7L) it.copy(contentUri = "") else it },
        )
        parse(f, serialize(f, stripped))                      // the modified artefact is itself a verified backup
    }

    // ── the acceptance flow ───────────────────────────────────────────────────

    private fun flow(f: Fmt, mode: Mode) = runBlocking {
        val a = backupFromOriginal(f)
        val name = "p17d-${UUID.randomUUID()}.db"
        val dbFile = app().getDatabasePath(name)

        // Destination: only songs 1..3 exist. Restore.
        var db = fileDb(name)
        db.songDao().upsertAll((1L..3L).map(::songEntity))
        var r = Env(db)
        val t0 = System.nanoTime()
        val result = r.restore(a, mode)
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("P17D restore $f/$mode: ${ms}ms, songs=${a.songs.size} stats=${a.trackStats.size} events=${a.listenEvents.size}")
        assertFalse(result.isNoOp)

        // Live: only the available songs, by their own descriptors; reused numeric ids never match anything else.
        assertEquals((1L..3L).toList(), db.trackStatsDao().getAllStatsSnapshot().map { it.songId }.sorted())
        assertEquals(11, db.trackStatsDao().getStatsBySongId(1)!!.playCount)
        assertTrue(db.trackListenEventDao().getAllSnapshot().all { it.songId in 1L..3L })
        // Legacy events stay legacy: restoring never invents an event id.
        val live = db.trackListenEventDao().getAllSnapshot()
        assertEquals("one id-carrying and one legacy event per live song", 3, live.count { it.eventId != null })
        assertEquals(3, live.count { it.eventId == null })

        // Quarantine: 4 (descriptor, unavailable), 5, 6, 7 (descriptor-less).
        val h = r.pending.loadAll()!!
        assertEquals(listOf("4", "5", "6", "7"), h.core.tracks.map { it.backupSongId }.sorted())
        fun track(id: String) = h.core.tracks.single { it.backupSongId == id }
        assertEquals("descriptor kept", listOf("Song 4", 180_004L), listOf(track("4").title, track("4").duration))
        assertEquals("distinct orphan, own metadata", "Song 5", track("5").title)
        assertEquals("unknown duration is not invented", 0L, track("5").duration)
        assertEquals("conflicting tags stay unresolved", listOf("", "", ""), listOf(track("6").title, track("6").artist, track("6").album))
        assertEquals("a unanimous URI is still kept", "content://media/6", track("6").sourceUri)
        assertEquals("blank metadata stays unknown", listOf("", "", "", null), listOf(track("7").title, track("7").artist, track("7").album, track("7").sourceUri))
        assertEquals(2, h.events.count { it.trackOriginKey == track("5").originKey })
        assertEquals(2, h.events.count { it.trackOriginKey == track("6").originKey })
        assertEquals("lyrics of 4", "lyrics four", h.core.lyricsOverrides.single().lyrics)
        assertEquals(3, h.core.importBaselines.single().playCount)
        // Playlist provenance intact for both the descriptor and the descriptor-less entry.
        for (id in listOf("4", "5")) {
            val e = h.core.playlistEntries.single { it.trackOriginKey == track(id).originKey }
            val src = a.playlists.single()   // provenance = the playlist as the backup itself states it (the original device's local id/times)
            assertEquals(listOf(src.name, src.id.toString(), src.createdAt, src.updatedAt),
                listOf(e.playlistName, e.sourcePlaylistId, e.sourcePlaylistCreatedAt, e.sourcePlaylistUpdatedAt))
        }
        val afterRestore = r.dump()

        // Persistence: close and reopen the same database file.
        db.close()
        assertTrue("database file exists on disk", dbFile.exists())
        db = fileDb(name)
        r = Env(db)
        assertEquals("pending and live state survive close/reopen", afterRestore, r.dump())

        // Re-importing the same backup adds nothing.
        r.restore(a, mode)
        assertEquals("repeat import: no inflation", h, r.pending.loadAll())

        // Export a new backup from the reopened database; it carries the quarantine, unchanged, with no invented event ids.
        val bBytes = r.export(f)
        val b = parse(f, bBytes)
        fun byFingerprint(x: PendingHistoryBackup) = x.copy(events = x.events.sortedBy { e -> e.originFingerprint })
        assertEquals("the new backup carries exactly the stored quarantine", byFingerprint(h), byFingerprint(b.pendingHistory!!))
        val pendingText = when (f) {
            Fmt.JSON -> JSONObject(String(bBytes, Charsets.UTF_8)).getJSONObject("pendingHistory").toString()
            Fmt.WDBK -> WdbkTestSupport.unzip(bBytes).filterKeys { it.startsWith("extensions/pending-history") }.values.joinToString { String(it, Charsets.UTF_8) }
        }
        assertFalse("pending events never carry an event id", pendingText.contains("ev-"))

        // A fresh installation (songs 1..3) restores the NEW backup, then restores it again.
        val c = Env(memoryDb(listOf(1L, 2L, 3L)))
        c.restore(b, mode)
        assertEquals("quarantine survives a second generation unchanged", h, c.pending.loadAll())
        val afterC = c.pending.loadAll()
        c.restore(b, mode)
        assertEquals("repeat import of the new backup: no inflation", afterC, c.pending.loadAll())
        // ...and a third generation does not grow it.
        val d = Env(memoryDb(listOf(1L, 2L, 3L)))
        d.restore(parse(f, c.export(f)), mode)
        assertEquals(h, d.pending.loadAll())
    }

    @Test fun `acceptance - JSON - Merge`() = flow(Fmt.JSON, Mode.MERGE)
    @Test fun `acceptance - JSON - Recovery`() = flow(Fmt.JSON, Mode.RECOVERY)
    @Test fun `acceptance - WDBK - Merge`() = flow(Fmt.WDBK, Mode.MERGE)
    @Test fun `acceptance - WDBK - Recovery`() = flow(Fmt.WDBK, Mode.RECOVERY)

    // ── interrupted and invalid restores ──────────────────────────────────────

    @Test fun `a Recovery interrupted at any stage leaves live data and existing pending rows exactly as they were`() = runBlocking {
        val stages = listOf(
            "planned", "statsCleared", "eventsCleared", "lyricsAndBaselinesCleared", "playlistsCleared", "statsWritten", "eventsWritten",
            "lyricsAndBaselinesWritten", "playlistsWritten", "quarantineWritten", "pendingHistoryWritten",
        )
        val a = backupFromOriginal(Fmt.JSON)
        for (stage in stages) {
            val r = Env(memoryDb(listOf(1L, 2L, 3L)))
            // Pre-existing live and pending state that a clumsy rollback would damage.
            r.merge.applyImport(RecoveryTestFixtures.v2Backup(songs = listOf(backupSong(2)), stats = listOf(backupStats(2, 99, 9, true))))
            r.db.pendingTrackDao().insertTrack(
                com.launchpoint.wavdrop.data.local.entity.PendingTrackEntity(
                    originKey = "older|9", backupFingerprint = "older", backupSongId = "9", title = "Older", artist = "", album = "", duration = 0,
                    trackNumber = 0, year = 0, sourceUri = null, sourceFolderPath = null, restoredAt = 1,
                ),
            )
            val before = r.dump()
            r.recovery.stageHook = { if (it == stage) throw IllegalStateException("interrupted at $stage") }
            try { r.recovery.applyRecovery(a); fail("expected interruption at $stage") } catch (_: IllegalStateException) { }
            assertEquals("stage $stage: nothing partially applied", before, r.dump())
        }
    }

    @Test fun `damaged or tampered backups are rejected at verification, before any restore could start`() {
        val json = WavdropBackupExporterV2.toJson(seed())
        assertTrue("fixture contains the value being tampered", json.contains("\"playCount\": 11"))
        val r = WavdropBackupParser.parse(json.replaceFirst("\"playCount\": 11", "\"playCount\": 99"))
        assertTrue(r.error != null || r.integrityStatus != BackupIntegrityStatus.VERIFIED)
        val wdbk = WdbkTestSupport.write(seed()).first.copyOf()
        wdbk[wdbk.size / 2] = (wdbk[wdbk.size / 2].toInt() xor 0x5A).toByte()
        val w = runCatching { WdbkReader().read(ByteArrayInputStream(wdbk), RecoveryTestFixtures.NOW + 1_000_000L) }
        assertTrue("a flipped byte never reads back as a verified backup", w.isFailure || w.getOrNull()!!.error != null || w.getOrNull()!!.integrityStatus != BackupIntegrityStatus.VERIFIED)
    }

    // ── supplementary JVM timing (NOT device performance; printed, never asserted) ────────────────

    private fun scaleBackup(orphans: Int, eventsPerOrphan: Int) = RecoveryTestFixtures.v2Backup(
        songs = emptyList(),
        stats = List(orphans) { backupStats(10_000L + it, 3, 1, false) },
        events = List(orphans * eventsPerOrphan) { i -> backupEvent(10_000L + i / eventsPerOrphan, null, 1_000L + i, "T${i / eventsPerOrphan}") },
        backupId = "00000000-0000-0000-0000-0000000000d2",
    )

    @Test fun `JVM timing of a small and a large orphan restore is printed for the acceptance record`() = runBlocking {
        for ((orphans, per) in listOf(50 to 10, 2_000 to 10)) {
            val b = scaleBackup(orphans, per)
            val n = Env(memoryDb(emptyList()))
            System.gc()
            val memBefore = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 / 1024
            val t0 = System.nanoTime()
            n.merge.applyImport(b)
            val ms = (System.nanoTime() - t0) / 1_000_000
            val memAfter = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 / 1024
            println("P17D-JVM orphans=$orphans events=${orphans * per} restoreMs=$ms heapBeforeMB=$memBefore heapAfterMB=$memAfter")
            assertEquals(orphans, n.db.pendingTrackDao().getAllOriginKeys().size)
            assertEquals(orphans * per, n.db.pendingTrackDao().getAllEventFingerprints().size)
        }
    }
}
