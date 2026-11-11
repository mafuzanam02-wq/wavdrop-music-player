package com.launchpoint.wavdrop.data.backup

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import com.launchpoint.wavdrop.data.backup.RecoveryTestFixtures.backupEvent
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
 * P5-STATS-18A - audit of listening-event identity; updated by P5-STATS-18C.
 *
 * 18C fixed the QUARANTINE path: cases A, B (quarantine part), D, F and G now assert the corrected behaviour. The LIVE restore path
 * (ListenEventRestorePlanner) is deliberately unchanged, so the live part of case B still pins the remaining defect and is EXPECTED
 * to fail when a later slice fixes it.
 *
 * Mechanism under test: `QuarantinePlanner.eventFingerprint` = snapshotFp|songId|occurredAt|eventType|listenedMs and
 * `pending_listen_events.originFingerprint` is UNIQUE with INSERT OR IGNORE, while `durationMs`, `eventId` and any occurrence index are
 * not part of it. `ListenEventRestorePlanner.fingerprint` (live restore) has the same four fields.
 * Real Room (in-memory SQLite) and the production exporter/writer/reader/parser and both restore repositories. Synthetic data only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EventIdentityCollisionAuditTest {

    private enum class Fmt { JSON, WDBK }
    private enum class Mode { MERGE, RECOVERY }

    private val files = mutableListOf<File>()
    private val dbs = mutableListOf<WavdropDatabase>()
    @After fun tearDown() { dbs.forEach { runCatching { it.close() } }; files.forEach { it.delete() } }

    private inner class Node(extraLive: List<SongEntity> = emptyList()) {
        val db: WavdropDatabase = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), WavdropDatabase::class.java)
            .allowMainThreadQueries().build().also { dbs += it }
        private val file = File(RuntimeEnvironment.getApplication().cacheDir, "p18a-${UUID.randomUUID()}.preferences_pb").also { files += it }
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

        init { runBlocking { db.songDao().upsertAll(listOf(songEntity(5)) + extraLive) } }

        suspend fun restore(b: WavdropBackup, mode: Mode) = if (mode == Mode.MERGE) merge.applyImport(b) else recovery.applyRecovery(b)
        suspend fun export(f: Fmt): ByteArray = when (f) {
            Fmt.JSON -> exporter.buildBackupJson().toByteArray(Charsets.UTF_8)
            Fmt.WDBK -> ByteArrayOutputStream().also { WdbkWriter().write(exporter.buildWdbkExportSnapshot(), it) }.toByteArray()
        }
        suspend fun pendingEvents() = db.pendingTrackDao().getAllEventFingerprints().size
    }

    private fun songEntity(id: Long) = backupSong(id).let {
        SongEntity(id, it.title, it.artist, it.album, it.albumId, it.duration, it.uri, it.dateAdded, it.trackNumber, it.year, it.folderPath, it.folderName)
    }

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

    private fun ev(songId: Long, eventId: String?, durationMs: Long = 180_000L, at: Long = 5_000L, listenedMs: Long = 60_000L) =
        backupEvent(songId, eventId, at).copy(durationMs = durationMs, listenedMs = listenedMs)

    /** Song 1 is described but absent from the destination library; id 90 has no descriptor at all (orphan). */
    private fun backup(events: List<BackupListenEvent>, id: String = "00000000-0000-0000-0000-0000000000e1") =
        RecoveryTestFixtures.v2Backup(songs = listOf(backupSong(1)), stats = listOf(backupStats(1, 1, 0, false)), events = events, backupId = id)

    private fun both(songEvents: (Long) -> List<BackupListenEvent>) = songEvents(1L) + songEvents(90L)

    // ── Case A: different durations ───────────────────────────────────────────

    @Test fun `A (fixed in 18C) - events differing only in durationMs are all quarantined and the count equals the stored rows`() = runBlocking {
        for (f in Fmt.values()) for (mode in Mode.values()) {
            val b = parse(f, serialize(f, backup(both { id -> listOf(ev(id, null, durationMs = 100_000L), ev(id, null, durationMs = 200_000L)) })))
            assertEquals("the backup really holds 4 distinct records", 4, b.listenEvents.size)
            val n = Node()
            val r = n.restore(b, mode)
            // descriptor-bearing (1) AND descriptor-less (90) behave identically: all 4 distinct events are preserved.
            assertEquals("$f $mode: stored rows", 4, n.pendingEvents())
            assertEquals("$f $mode: the count reports persisted rows", 4, r.pendingEventsPreserved)
            val kept = n.pending.loadAll()!!.events.map { it.durationMs }.sorted()
            assertEquals(listOf(100_000L, 100_000L, 200_000L, 200_000L), kept)
        }
    }

    // ── Case B: different event ids ───────────────────────────────────────────

    @Test fun `B - distinct non-null event ids are both quarantined (fixed in 18C) but still collapse in live restore (CURRENT DEFECT)`() = runBlocking {
        val events = listOf(ev(1, "id-one"), ev(1, "id-two"))
        for (mode in Mode.values()) {
            val n = Node()
            n.restore(parse(Fmt.JSON, serialize(Fmt.JSON, backup(events))), mode)
            assertEquals("$mode: quarantine keeps both distinct event ids", 2, n.pendingEvents())
        }
        // LIVE restore (song 5 is in the library): the live planner uses the same four-field fingerprint, so it collapses too.
        val live = backup(listOf(ev(5, "id-one"), ev(5, "id-two"))).copy(songs = listOf(backupSong(5)), trackStats = listOf(backupStats(5, 2, 0, false)))
        for (mode in Mode.values()) {
            val n = Node()
            val r = n.restore(parse(Fmt.JSON, serialize(Fmt.JSON, live)), mode)
            assertEquals("$mode live: one of two distinct event ids is skipped as a duplicate", 1, n.db.trackListenEventDao().getAllSnapshot().size)
            assertEquals(1, r.eventsSkippedDuplicate)
        }
    }

    // ── Case C: exact duplicate ───────────────────────────────────────────────

    @Test fun `C - an exact duplicate (same event id and fields) is one event - intentional deduplication`() = runBlocking {
        val n = Node()
        val r = n.restore(parse(Fmt.JSON, serialize(Fmt.JSON, backup(listOf(ev(1, "same"), ev(1, "same"))))), Mode.MERGE)
        assertEquals(1, n.pendingEvents())
        assertEquals("the count now reports persisted rows, not attempted inserts", 1, r.pendingEventsPreserved)
    }

    // ── Case D: legacy events ─────────────────────────────────────────────────

    @Test fun `D (fixed in 18C) - identical legacy events (null id) keep their multiplicity`() = runBlocking {
        val n = Node()
        n.restore(parse(Fmt.JSON, serialize(Fmt.JSON, backup(listOf(ev(1, null), ev(1, null))))), Mode.MERGE)
        assertEquals("two byte-identical legacy records are two occurrences in the source: both are stored", 2, n.pendingEvents())
        // No event id is ever written to the pending table (there is no column), so nothing is fabricated.
        assertTrue(n.pending.loadAll()!!.events.all { it.originFingerprint.isNotBlank() })
    }

    // ── Case E: different snapshots ───────────────────────────────────────────

    @Test fun `E - the same apparent event in two snapshots is kept twice and never merged without lineage`() = runBlocking {
        val n = Node()
        n.restore(parse(Fmt.JSON, serialize(Fmt.JSON, backup(listOf(ev(1, null)), id = "00000000-0000-0000-0000-0000000000e1"))), Mode.MERGE)
        n.restore(parse(Fmt.JSON, serialize(Fmt.JSON, backup(listOf(ev(1, null)), id = "00000000-0000-0000-0000-0000000000e2"))), Mode.MERGE)
        assertEquals(2, n.pendingEvents())
        assertEquals(2, n.pending.loadAll()!!.core.tracks.size)
    }

    // ── Case F: repeated restore ──────────────────────────────────────────────

    @Test fun `F - repeating a restore never inflates and the preview agrees there is nothing to do`() = runBlocking {
        val b = parse(Fmt.JSON, serialize(Fmt.JSON, backup(listOf(ev(1, null, durationMs = 100_000L), ev(1, null, durationMs = 200_000L)))))
        val n = Node()
        n.restore(b, Mode.MERGE)
        assertEquals(2, n.pendingEvents())
        val again = n.restore(b, Mode.MERGE)
        assertTrue("second Merge is a no-op", again.isNoOp)
        assertEquals("idempotent: no inflation", 2, n.pendingEvents())
        assertTrue(!n.merge.previewMerge(b).hasMergeableData)
        assertEquals(2, n.pendingEvents())
    }

    // ── Case G: multi-generation ──────────────────────────────────────────────

    private fun chain(f: Fmt, mode: Mode) = runBlocking {
        val a = parse(f, serialize(f, backup(listOf(ev(1, null, durationMs = 100_000L), ev(1, null, durationMs = 200_000L)))))
        assertEquals("backup A holds both events (this file is the only place the second one still exists)", 2, a.listenEvents.size)
        val bNode = Node(); bNode.restore(a, mode)
        assertEquals(2, bNode.pendingEvents())
        val bBackup = parse(f, bNode.export(f))
        assertEquals("B transports both events", 2, bBackup.pendingHistory!!.events.size)
        val cNode = Node(); cNode.restore(bBackup, mode)
        assertEquals(2, cNode.pendingEvents())
        val cBackup = parse(f, cNode.export(f))
        assertEquals("both events survive every later generation", 2, cBackup.pendingHistory!!.events.size)
        assertEquals(listOf(100_000L, 200_000L), cBackup.pendingHistory!!.events.map { it.durationMs }.sorted())
    }

    @Test fun `G (fixed in 18C) - no loss from A to B to C - JSON Merge`() = chain(Fmt.JSON, Mode.MERGE)
    @Test fun `G (fixed in 18C) - no loss from A to B to C - JSON Recovery`() = chain(Fmt.JSON, Mode.RECOVERY)
    @Test fun `G (fixed in 18C) - no loss from A to B to C - WDBK Merge`() = chain(Fmt.WDBK, Mode.MERGE)
    @Test fun `G (fixed in 18C) - no loss from A to B to C - WDBK Recovery`() = chain(Fmt.WDBK, Mode.RECOVERY)

    // ── Case H: live versus quarantine ────────────────────────────────────────

    @Test fun `H - an orphan event whose tags uniquely match a live song is restored live AND archived, without touching live stats`() = runBlocking {
        val echo = songEntity(80).copy(title = "Echo")          // unique live tags (Echo / Artist / Album); a DIFFERENT numeric id from the orphan's
        val orphanEvent = backupEvent(77, "orph-1", 7_000L, "Echo")
        val n = Node(extraLive = listOf(echo))
        val b = RecoveryTestFixtures.v2Backup(songs = emptyList(), stats = emptyList(), events = listOf(orphanEvent))
        n.restore(parse(Fmt.JSON, serialize(Fmt.JSON, b)), Mode.MERGE)
        assertEquals("live: restored onto song 80 by its own tags", listOf(80L), n.db.trackListenEventDao().getAllSnapshot().map { it.songId })
        assertEquals("quarantine: the same event is also archived", 1, n.pendingEvents())
        assertNull("no live aggregate is created or changed", n.db.trackStatsDao().getStatsBySongId(80))
        // Not a count error today (pending rows are never summed into live figures) but a duplicate-attribution hazard for any future rematch.
    }

    // ── repairability facts ───────────────────────────────────────────────────

    @Test fun `pending rows carry no event id or source, so original event identity cannot be recovered from them`() = runBlocking {
        val n = Node()
        n.restore(parse(Fmt.JSON, serialize(Fmt.JSON, backup(listOf(ev(1, "keep-me"))))), Mode.MERGE)
        val row = n.db.pendingTrackDao().getEventByFingerprint(n.db.pendingTrackDao().getAllEventFingerprints().single())!!
        val columns = row.javaClass.declaredFields.map { it.name }.toSet()
        assertTrue("no eventId/source column exists on the pending event entity", columns.none { it.contains("eventId", true) || it == "source" })
    }
}
