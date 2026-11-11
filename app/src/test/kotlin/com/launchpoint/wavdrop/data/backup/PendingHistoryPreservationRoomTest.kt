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
import com.launchpoint.wavdrop.data.local.entity.PendingBackupExtensionEntity
import com.launchpoint.wavdrop.data.local.entity.PendingTrackEntity
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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/**
 * P5-STATS-17B on real Room/SQLite, through the production exporter, writer, reader, parser and both restore repositories.
 *
 * The defect (F2): history quarantined by a restore was omitted from every later backup. Chain under test:
 * Backup A -> restore on B (its song is absent -> quarantine) -> Backup B -> restore on fresh C -> Backup C -> ... Everything here is
 * synthetic and in-memory; nothing is device-verified.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PendingHistoryPreservationRoomTest {

    private enum class Fmt { JSON, WDBK }
    private enum class Mode { MERGE, RECOVERY }

    private val files = mutableListOf<File>()
    private val dbs = mutableListOf<WavdropDatabase>()
    private val snapshotDirs = mutableListOf<File>()

    @After fun tearDown() { dbs.forEach { it.close() }; files.forEach { it.delete() }; snapshotDirs.forEach { it.deleteRecursively() } }

    /** One simulated install: its own database, settings store and the REAL repositories. */
    private inner class Node(libraryIds: List<Long> = listOf(5L), extra: List<SongEntity> = emptyList()) {
        val db: WavdropDatabase = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), WavdropDatabase::class.java)
            .allowMainThreadQueries().build().also { dbs += it }
        private val file = File(RuntimeEnvironment.getApplication().cacheDir, "p17b-${UUID.randomUUID()}.preferences_pb").also { files += it }
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

        init { runBlocking { db.songDao().upsertAll(libraryIds.map(::songEntity) + extra) } }

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
            append(db.pendingBackupExtensionDao().getByRootName(DESKTOP_OVERLAY_ROOT)?.rawJson)
        }
    }

    private fun songEntity(id: Long) = backupSong(id).let {
        SongEntity(id, it.title, it.artist, it.album, it.albumId, it.duration, it.uri, it.dateAdded, it.trackNumber, it.year, it.folderPath, it.folderName)
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    private fun backupA(id: String = "00000000-0000-0000-0000-0000000000a1", extraEventAt: Long? = null) = RecoveryTestFixtures.v2Backup(
        songs = listOf(backupSong(1, "Gone Song")),
        stats = listOf(backupStats(1, 40, 5, true)),
        events = listOf(backupEvent(1, "e1", 1_000L, "Gone Song"), backupEvent(1, "e2", 2_000L, "Gone Song"), backupEvent(1, null, 3_000L, "Gone Song")) +
            listOfNotNull(extraEventAt?.let { backupEvent(1, null, it, "Gone Song") }),
        playlists = listOf(BackupPlaylist(11, "Roadtrip", 10L, 20L, listOf(backupPlaylistSong(1, 0).copy(title = "Gone Song")))),
        lyrics = listOf(BackupLyricsOverride(1, "content://media/1", "lyrics of the gone song", 7_000L)),
        baselines = listOf(BackupImportBaseline(1, "blackplayer", "kk", 3, 1, 8L)),
        backupId = id,
    )

    private fun serialize(f: Fmt, b: WavdropBackup): ByteArray = when (f) {
        Fmt.JSON -> WavdropBackupExporterV2.toJson(b).toByteArray(Charsets.UTF_8)
        Fmt.WDBK -> WdbkTestSupport.write(b).first
    }

    /** The production parser/reader for the format; asserts the artifact verifies and returns its backup. */
    private fun parse(f: Fmt, bytes: ByteArray): WavdropBackup {
        val r = when (f) {
            Fmt.JSON -> WavdropBackupParser.parse(String(bytes, Charsets.UTF_8))
            Fmt.WDBK -> WdbkReader().read(ByteArrayInputStream(bytes), RecoveryTestFixtures.NOW + 1_000_000L)
        }
        assertNull(r.error, r.error)
        assertEquals(BackupIntegrityStatus.VERIFIED, r.integrityStatus)
        return r.backup!!
    }

    private suspend fun counts(n: Node) = listOf(
        n.db.pendingTrackDao().getAllOriginKeys().size, n.db.pendingTrackDao().getAllEventFingerprints().size,
        n.db.pendingTrackDao().getAllLyricsFingerprints().size, n.db.pendingTrackDao().getAllBaselineFingerprints().size,
        n.db.pendingTrackDao().getAllPlaylistEntryFingerprints().size,
    )

    private suspend fun assertNoLiveHistory(n: Node) {
        assertTrue("no live stats", n.db.trackStatsDao().getAllStatsSnapshot().isEmpty())
        assertTrue("no live events: counters were never turned into plays", n.db.trackListenEventDao().getAllSnapshot().isEmpty())
        assertTrue(n.db.lyricsOverrideDao().getAllSnapshot().isEmpty())
        assertTrue(n.db.importBaselineDao().getAllImportBaselinesSnapshot().isEmpty())
        for (p in n.db.playlistDao().getAllPlaylistsSnapshot()) assertTrue(n.db.playlistDao().getSongsForPlaylistSnapshot(p.playlistId).isEmpty())
    }

    private val sortedEvents = { h: PendingHistoryBackup -> h.copy(events = h.events.sortedBy { it.originFingerprint }) }

    /** restoredAt records WHICH device quarantined a row first, so two routes to the same set may legitimately differ in it. */
    private fun withoutRestoredAt(h: PendingHistoryBackup) = normalize(h).let { n -> n.copy(core = n.core.copy(tracks = n.core.tracks.map { it.copy(restoredAt = 0) })) }

    /** Canonical order for comparing a hand-written fixture with rows read back from the database. */
    private fun normalize(h: PendingHistoryBackup) = h.copy(
        core = h.core.copy(
            tracks = h.core.tracks.sortedBy { it.originKey }, stats = h.core.stats.sortedBy { it.trackOriginKey },
            lyricsOverrides = h.core.lyricsOverrides.sortedBy { it.originFingerprint }, importBaselines = h.core.importBaselines.sortedBy { it.originFingerprint },
            playlistEntries = h.core.playlistEntries.sortedBy { it.originFingerprint },
        ),
        events = h.events.sortedBy { it.originFingerprint },
    )

    /** Backup A -> B -> C -> D -> E. The pending set must be identical at every hop and must not grow. */
    private fun chain(f: Fmt, mode: Mode) = runBlocking {
        val b = Node()
        b.restore(parse(f, serialize(f, backupA())), mode)
        // 1 track, 1 stats row, 3 events (one without an eventId), 1 lyrics, 1 baseline, 1 playlist entry; nothing live.
        assertEquals(listOf(1, 3, 1, 1, 1), counts(b))
        val original = b.pending.loadAll()!!
        assertEquals(1, original.core.stats.size)
        assertNoLiveHistory(b)

        var previous = b
        var firstPendingBytes: Map<String, List<Byte>>? = null
        for (hop in 1..4) {
            val artifact = previous.export(f)
            val parsed = parse(f, artifact)
            assertEquals("hop $hop: the export carries exactly the current pending set", sortedEvents(original), parsed.pendingHistory?.let(sortedEvents))
            if (f == Fmt.WDBK) {
                val pendingEntries = WdbkTestSupport.unzip(artifact).filterKeys { it.startsWith("extensions/pending-history") }
                    .mapValues { it.value.toList() }
                if (firstPendingBytes == null) firstPendingBytes = pendingEntries
                assertEquals("hop $hop: byte-identical pending entries, no amplification", firstPendingBytes, pendingEntries)
            }
            val next = Node(libraryIds = listOf(5L))
            val result = next.restore(parsed, mode)
            assertEquals(listOf(1, 3, 1, 1, 1), counts(next))
            assertEquals("every hop restores the original rows, original keys and provenance", original, next.pending.loadAll())
            assertNoLiveHistory(next)
            if (mode == Mode.MERGE) {
                assertFalse("hop $hop: a backup whose news is only pending history is NOT a no-op", result.isNoOp)
                assertEquals(1, result.pendingTracksPreserved); assertEquals(3, result.pendingEventsPreserved)
                assertEquals(1, result.pendingPlaylistEntriesPreserved)
            }
            previous = next
        }
    }

    @Test fun `chain - JSON - Merge`() = chain(Fmt.JSON, Mode.MERGE)
    @Test fun `chain - JSON - Recovery`() = chain(Fmt.JSON, Mode.RECOVERY)
    @Test fun `chain - WDBK - Merge`() = chain(Fmt.WDBK, Mode.MERGE)
    @Test fun `chain - WDBK - Recovery`() = chain(Fmt.WDBK, Mode.RECOVERY)

    @Test fun `control - without the extension the history is lost after one generation (the defect)`() = runBlocking {
        for (f in Fmt.values()) {
            val b = Node(); b.restore(parse(f, serialize(f, backupA())), Mode.MERGE)
            val stripped = parse(f, b.export(f)).copy(pendingHistory = null)   // what every build before this slice produced
            val c = Node(); c.restore(stripped, Mode.MERGE)
            assertEquals("$f", listOf(0, 0, 0, 0, 0), counts(c))
        }
    }

    // ── what survives: every category, nullable fields, provenance ────────────

    @Test fun `every pending category survives with its original keys, timestamps and nullable fields`() = runBlocking {
        val seed = PendingHistoryTestData.history()
        for (f in Fmt.values()) for (mode in Mode.values()) {
            val x = Node()
            // Seed through the real restore path of a backup that carries the section (not by writing tables directly).
            x.restore(parse(f, serialize(f, RecoveryTestFixtures.v2Backup(songs = emptyList(), stats = emptyList()).copy(pendingHistory = seed))), mode)
            val y = Node()
            y.restore(parse(f, x.export(f)), mode)
            val got = y.pending.loadAll()!!
            assertEquals("$f $mode", normalize(seed), normalize(got))
            assertNull("a v1-sourced stats row stays null, not 0", got.core.stats.single { it.playCount == 40 }.lastListenedAt)
            assertNull("legacy playlist provenance stays unknown", got.core.playlistEntries.single { it.playlistName == "Old name based" }.sourcePlaylistId)
            assertEquals("provenance timestamp kept, not replaced by this restore's clock", seed.core.tracks.map { it.restoredAt }, got.core.tracks.map { it.restoredAt })
        }
    }

    // ── idempotence and overlap ───────────────────────────────────────────────

    @Test fun `repeated restore of the same backup adds nothing and Merge reports a no-op`() = runBlocking {
        for (f in Fmt.values()) {
            val b = Node(); b.restore(parse(f, serialize(f, backupA())), Mode.MERGE)
            val backupB = parse(f, b.export(f))
            val c = Node()
            assertFalse(c.restore(backupB, Mode.MERGE).isNoOp)
            val after = c.dump()
            val second = c.restore(backupB, Mode.MERGE)
            assertTrue("$f: second Merge of the same file is a no-op", second.isNoOp)
            assertEquals(after, c.dump())
            c.restore(backupB, Mode.RECOVERY); val afterRecovery = c.pending.loadAll()
            c.restore(backupB, Mode.RECOVERY)
            assertEquals("$f: repeated Recovery is idempotent for pending history", afterRecovery, c.pending.loadAll())
            assertEquals(listOf(1, 3, 1, 1, 1), counts(c))
        }
    }

    @Test fun `the same snapshot restored directly and then carried forward is not duplicated`() = runBlocking {
        for (f in Fmt.values()) {
            val b = Node(); b.restore(parse(f, serialize(f, backupA())), Mode.MERGE)
            val c = Node()
            c.restore(parse(f, serialize(f, backupA())), Mode.MERGE)       // quarantined here directly (restoredAt = now)
            val before = counts(c)
            val r = c.restore(parse(f, b.export(f)), Mode.MERGE)           // and again, carried by Backup B (older restoredAt)
            assertEquals(before, counts(c))
            assertTrue("$f: nothing new anywhere, so the Merge is a no-op", r.isNoOp)
        }
    }

    @Test fun `overlapping backups sharing origin keys union without duplicates, in either order`() = runBlocking {
        for (f in Fmt.values()) {
            val a2 = backupA(id = "00000000-0000-0000-0000-0000000000a2", extraEventAt = 9_000L)
            val x = Node()   // quarantined BOTH snapshots
            x.restore(parse(f, serialize(f, backupA())), Mode.MERGE); x.restore(parse(f, serialize(f, a2)), Mode.MERGE)
            val y = Node()   // quarantined only the first
            y.restore(parse(f, serialize(f, backupA())), Mode.MERGE)
            val fromX = parse(f, x.export(f)); val fromY = parse(f, y.export(f))
            assertEquals(2, fromX.pendingHistory!!.core.tracks.size); assertEquals(1, fromY.pendingHistory!!.core.tracks.size)
            for (order in listOf(listOf(fromY, fromX), listOf(fromX, fromY))) {
                val c = Node()
                order.forEach { c.restore(it, Mode.MERGE) }
                assertEquals("$f: union, no duplicates", withoutRestoredAt(x.pending.loadAll()!!), withoutRestoredAt(c.pending.loadAll()!!))
                assertEquals(listOf(2, 3 + 4, 2, 2, 2), counts(c))
            }
        }
    }

    // ── conflicts and atomicity ───────────────────────────────────────────────

    private fun withHistory(h: PendingHistoryBackup, songs: List<BackupSong> = emptyList(), stats: List<BackupTrackStats> = emptyList()) =
        RecoveryTestFixtures.v2Backup(songs = songs, stats = stats).copy(pendingHistory = h)

    private fun conflicts(h: PendingHistoryBackup): List<Pair<String, PendingHistoryBackup>> = listOf(
        "track title" to h.copy(core = h.core.copy(tracks = h.core.tracks.map { if (it.backupSongId == "1") it.copy(title = "Different") else it })),
        "track duration" to h.copy(core = h.core.copy(tracks = h.core.tracks.map { it.copy(duration = it.duration + 1) })),
        "stats" to h.copy(core = h.core.copy(stats = h.core.stats.map { it.copy(playCount = it.playCount + 1) })),
        "lyrics" to h.copy(core = h.core.copy(lyricsOverrides = h.core.lyricsOverrides.map { it.copy(lyrics = "other") })),
        "baseline" to h.copy(core = h.core.copy(importBaselines = h.core.importBaselines.map { it.copy(playCount = 99) })),
        "playlist entry" to h.copy(core = h.core.copy(playlistEntries = h.core.playlistEntries.map { it.copy(position = it.position + 1) })),
        "event" to h.copy(events = h.events.map { it.copy(listenedMs = it.listenedMs + 1) }),
    )

    @Test fun `contradictory data under an existing key is never silently dropped - the whole restore fails and nothing changes`() = runBlocking {
        val h = PendingHistoryTestData.history()
        for (mode in Mode.values()) for ((label, bad) in conflicts(h)) {
            val n = Node(libraryIds = listOf(5L, 1L))
            n.restore(withHistory(h), mode)                       // establishes the rows under their keys
            val before = n.dump()
            // The conflicting backup ALSO carries a live change that would otherwise be applied (song 1 is in the library).
            val live = withHistory(bad, songs = listOf(backupSong(1)), stats = listOf(backupStats(1, 77, 1, false)))
            try {
                n.restore(live, mode)
                fail("$mode / $label: a conflict must not be ignored")
            } catch (e: PendingHistoryConflictException) {
                assertTrue(e.message!!.contains("conflicts"))
            }
            assertEquals("$mode / $label: rolled back, live and pending untouched", before, n.dump())
        }
    }

    @Test fun `a failure midway through insertion rolls back every earlier insert and every live write`() = runBlocking {
        val h = PendingHistoryTestData.history()
        for (mode in Mode.values()) {
            val n = Node(libraryIds = listOf(5L, 1L))
            // Pre-store ONE event of the section under the same key with different content, so the failure happens late: after the
            // new tracks, stats, lyrics, baselines and playlist entries of this backup were inserted in the same transaction.
            val victim = h.events.last()
            n.db.pendingTrackDao().insertTrack(
                PendingTrackEntity(originKey = "zzz|0", backupFingerprint = "zzz", backupSongId = "0", title = "t", artist = "a", album = "b",
                    duration = 1, trackNumber = 0, year = 0, sourceUri = null, sourceFolderPath = null, restoredAt = 1),
            )
            val owner = n.db.pendingTrackDao().getPendingId("zzz|0")!!
            n.db.pendingTrackDao().insertEvent(
                com.launchpoint.wavdrop.data.local.entity.PendingListenEventEntity(
                    pendingId = owner, eventType = victim.eventType, occurredAt = victim.occurredAt, listenedMs = victim.listenedMs + 5,
                    durationMs = victim.durationMs, originFingerprint = victim.originFingerprint,
                ),
            )
            val before = n.dump()
            try {
                n.restore(withHistory(h, songs = listOf(backupSong(1)), stats = listOf(backupStats(1, 77, 1, false))), mode)
                fail("$mode: expected the late conflict")
            } catch (_: PendingHistoryConflictException) { }
            assertEquals("$mode: no partial pending rows, no live stats", before, n.dump())
            assertNull(n.db.trackStatsDao().getStatsBySongId(1))
            assertEquals(listOf(1, 1, 0, 0, 0), counts(n))
        }
    }

    // ── quarantine stays quarantine ───────────────────────────────────────────

    @Test fun `pending history stays quarantined even when a same-named song is in the library`() = runBlocking {
        val lookalike = SongEntity(77, "Gone Song", "Artist", "Album", 1L, 180_001L, "content://media/77", 1_000L, 1, 2020, "Music/", "Music")
        // Control: the ORIGINAL backup would match that song and restore live statistics for it.
        val control = Node(extra = listOf(lookalike))
        control.restore(parse(Fmt.JSON, serialize(Fmt.JSON, backupA())), Mode.MERGE)
        assertEquals("control: the song really would have matched", 40, control.db.trackStatsDao().getStatsBySongId(77)?.playCount)

        for (f in Fmt.values()) for (mode in Mode.values()) {
            val b = Node(); b.restore(parse(f, serialize(f, backupA())), Mode.MERGE)
            val c = Node(extra = listOf(lookalike))
            c.restore(parse(f, b.export(f)), mode)
            assertNull("$f $mode: not attached to the lookalike", c.db.trackStatsDao().getStatsBySongId(77))
            assertTrue(c.db.trackListenEventDao().getAllSnapshot().isEmpty())
            assertTrue(c.db.lyricsOverrideDao().getAllSnapshot().isEmpty())
            assertEquals(listOf(1, 3, 1, 1, 1), counts(c))
        }
    }

    @Test fun `a backup whose only news is pending history is previewed and applied, then reported as a no-op`() = runBlocking {
        val x = RecoveryTestFixtures.v2Backup(songs = emptyList(), stats = emptyList()).copy(pendingHistory = PendingHistoryTestData.history())
        val n = Node()
        assertTrue("preview offers it", n.merge.previewMerge(x).hasMergeableData)
        assertFalse(n.merge.applyImport(x).isNoOp)
        val again = n.merge.previewMerge(x)
        assertFalse("preview agrees it is already up to date", again.hasMergeableData)
        assertNotNull(again.noOpReason)
        assertTrue(n.merge.applyImport(x).isNoOp)
    }

    // ── stats-only news (P5-STATS-17B1) ───────────────────────────────────────

    private fun noStats(h: PendingHistoryBackup) = h.copy(core = h.core.copy(stats = emptyList()))

    @Test fun `stats missing for an already-quarantined track are previewed as new, applied once, then idempotent`() = runBlocking {
        val full = PendingHistoryTestData.history()
        for (mode in Mode.values()) {
            val n = Node()
            n.restore(withHistory(noStats(full)), mode)                       // every other row stored; no stats row
            assertTrue(n.pending.loadAll()!!.core.stats.isEmpty())
            val others = n.pending.loadAll()!!

            val incoming = withHistory(full)
            assertTrue("$mode: stats-only news is actionable", n.merge.previewMerge(incoming).hasMergeableData)
            assertTrue(n.pending.hasNewData(full))

            val r = n.restore(incoming, mode)
            if (mode == Mode.MERGE) assertFalse("stats-only news is applied, not reported as a no-op", r.isNoOp)
            assertEquals("$mode: the stats rows are persisted", normalize(full).core.stats, normalize(n.pending.loadAll()!!).core.stats)
            assertEquals("$mode: nothing else changed", others, n.pending.loadAll()!!.let { it.copy(core = it.core.copy(stats = emptyList())) })
            assertNoLiveHistory(n)

            assertFalse("$mode: now up to date", n.pending.hasNewData(full))
            if (mode == Mode.MERGE) assertFalse(n.merge.previewMerge(incoming).hasMergeableData)
            val after = n.dump()
            val again = n.restore(incoming, mode)
            assertEquals("$mode: repeat import changes nothing", after, n.dump())
            if (mode == Mode.MERGE) assertTrue(again.isNoOp)
        }
    }

    @Test fun `identical stats are a no-op and differing stats are actionable but fail safely without overwriting`() = runBlocking {
        val full = PendingHistoryTestData.history()
        for (mode in Mode.values()) {
            val n = Node(libraryIds = listOf(5L, 1L))
            n.restore(withHistory(full), mode)
            assertFalse("$mode: identical stats are not news", n.pending.hasNewData(full))
            assertTrue(n.restore(withHistory(full), mode).let { mode == Mode.RECOVERY || it.isNoOp })

            val bad = full.copy(core = full.core.copy(stats = full.core.stats.map { it.copy(playCount = it.playCount + 1) }))
            assertTrue("$mode: conflicting stats are never reported as an equivalent no-op", n.pending.hasNewData(bad))
            val before = n.dump()
            try {
                n.restore(withHistory(bad, songs = listOf(backupSong(1)), stats = listOf(backupStats(1, 77, 1, false))), mode)
                fail("$mode: conflict must be rejected")
            } catch (_: PendingHistoryConflictException) { }
            assertEquals("$mode: stored stats not overwritten, live write rolled back", before, n.dump())
        }
    }

    // ── recovery safety and no unintended deletion ────────────────────────────

    @Test fun `Recovery never deletes pending rows and the safety snapshot carries them`() = runBlocking {
        val n = Node(libraryIds = listOf(5L, 1L))
        n.restore(parse(Fmt.JSON, serialize(Fmt.JSON, backupA())), Mode.MERGE)
        n.db.pendingTrackDao().insertTrack(
            PendingTrackEntity(originKey = "other|7", backupFingerprint = "other", backupSongId = "7", title = "Old", artist = "A", album = "B",
                duration = 1, trackNumber = 1, year = 2000, sourceUri = null, sourceFolderPath = null, restoredAt = 1),
        )
        val pendingBefore = n.pending.loadAll()!!
        assertEquals(2, pendingBefore.core.tracks.size)

        val dir = File(System.getProperty("java.io.tmpdir"), "p17b-snap-${System.nanoTime()}").also { snapshotDirs += it }
        val creator = RecoverySafetySnapshotCreator(dir, BackupExecutionSerializer(), { n.exporter.buildBackupJson() }, SafetySnapshotIo.Default)
        val snap = creator.createVerifiedSnapshot() as SafetySnapshotResult.Verified
        assertEquals(BackupIntegrityStatus.VERIFIED, snap.snapshot.verification)
        val inSnapshot = WavdropBackupParser.parse(creator.latestFile.readText()).backup!!.pendingHistory!!
        assertEquals(sortedEvents(pendingBefore), sortedEvents(inSnapshot))

        // A Recovery from a backup that carries NO pending section: live state is replaced, pending is untouched.
        n.recovery.applyRecovery(RecoveryTestFixtures.v2Backup(songs = listOf(backupSong(1)), stats = listOf(backupStats(1, 3, 0, false))))
        assertEquals(3, n.db.trackStatsDao().getStatsBySongId(1)!!.playCount)
        assertEquals(pendingBefore, n.pending.loadAll())
    }

    // ── no regression to the primary export / other extensions ────────────────

    @Test fun `live statistics export and the Desktop overlay are unchanged, with or without pending history`() = runBlocking {
        for (f in Fmt.values()) {
            val live = Node(libraryIds = listOf(1L, 2L))
            live.restore(parse(f, serialize(f, RecoveryTestFixtures.v2Backup(
                songs = listOf(backupSong(1), backupSong(2)), stats = listOf(backupStats(1, 12, 2, true), backupStats(2, 3, 0, false)),
                events = listOf(backupEvent(1, "x1", 11_000L), backupEvent(2, "x2", 12_000L)),
            ))), Mode.MERGE)
            live.db.pendingBackupExtensionDao().upsert(PendingBackupExtensionEntity(DESKTOP_OVERLAY_ROOT, WdbkTestSupport.OVERLAY_RAW, 1L))

            val without = parse(f, live.export(f))
            assertNull("nothing pending: no section at all", without.pendingHistory)
            if (f == Fmt.JSON) assertFalse(JSONObject(String(live.export(f), Charsets.UTF_8)).has("pendingHistory"))
            else assertTrue(WdbkTestSupport.unzip(live.export(f)).keys.none { it.startsWith("extensions/pending-history") })

            // Now there IS quarantine (carried by a backup, so it can never match the library songs 1 and 2 above).
            live.restore(parse(f, serialize(f, withHistory(PendingHistoryTestData.history()))), Mode.MERGE)
            val with = parse(f, live.export(f))
            assertNotNull(with.pendingHistory)
            assertEquals(without.trackStats, with.trackStats)
            assertEquals(without.listenEvents, with.listenEvents)
            assertEquals(2, with.trackStats.size); assertEquals(2, with.listenEvents.size)
            assertEquals("the primary fingerprint covers the same live content either way",
                WavdropBackupIntegrityV2.fingerprint(without.copy(backupId = "same", exportedAtMs = 1)),
                WavdropBackupIntegrityV2.fingerprint(with.copy(backupId = "same", exportedAtMs = 1)))
            assertEquals(JSONObject(WdbkTestSupport.OVERLAY_RAW).toString(), JSONObject(with.desktopOverlay!!.rawJson).toString())
        }
    }

    @Test fun `a v1 backup and a v2 backup with no pending section restore exactly as before`() = runBlocking {
        val n = Node(libraryIds = listOf(1L))
        val r = n.restore(parse(Fmt.JSON, serialize(Fmt.JSON, RecoveryTestFixtures.v2Backup())), Mode.MERGE)
        assertEquals(0, r.pendingTracksPreserved)
        assertEquals(10, n.db.trackStatsDao().getStatsBySongId(1)!!.playCount)
        val v1 = WavdropBackupParser.parse(RecoveryTestFixtures.v1Json())
        assertNull(v1.error); assertNull(v1.backup!!.pendingHistory)
    }
}
