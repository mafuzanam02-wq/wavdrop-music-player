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
 * P5-STATS-18C - collision-safe quarantine event preservation. Real in-memory Room, the production exporter/writer/reader/parser and
 * both restore repositories. Synthetic data only. The "legacy survivor" states are produced by editing rows with SQL exactly the way
 * the pre-18C code left them (one row at the base fingerprint, payload = whichever event came first in the source file).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class QuarantineEventOccurrenceRoomTest {

    private enum class Fmt { JSON, WDBK }
    private enum class Mode { MERGE, RECOVERY }

    private val files = mutableListOf<File>()
    private val dbs = mutableListOf<WavdropDatabase>()
    @After fun tearDown() { dbs.forEach { runCatching { it.close() } }; files.forEach { it.delete() } }

    private inner class Node {
        val db: WavdropDatabase = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), WavdropDatabase::class.java)
            .allowMainThreadQueries().build().also { dbs += it }
        private val file = File(RuntimeEnvironment.getApplication().cacheDir, "p18c-${UUID.randomUUID()}.preferences_pb").also { files += it }
        private val store = PreferenceDataStoreFactory.create { file }
        val merge = WavdropBackupImportRepository(
            db, db.songDao(), db.trackStatsDao(), db.lyricsOverrideDao(), db.importBaselineDao(), db.playlistDao(),
            db.trackListenEventDao(), db.pendingTrackDao(), db.pendingBackupExtensionDao(),
        )
        val recovery = RecoveryRestoreRepository(
            db, db.songDao(), db.trackStatsDao(), db.lyricsOverrideDao(), db.importBaselineDao(), db.playlistDao(),
            db.trackListenEventDao(), db.pendingTrackDao(), db.pendingBackupExtensionDao(),
        )
        private val exporter = WavdropBackupRepository(
            RuntimeEnvironment.getApplication(), store, db.songDao(), db.trackStatsDao(), db.importBaselineDao(), db.lyricsOverrideDao(),
            db.playlistDao(), db.trackListenEventDao(), db.pendingBackupExtensionDao(), db.pendingTrackDao(),
            AppSettingsRepository(store), HomeLayoutSettingsRepository(store), LibraryScanSettingsRepository(store),
            ResumeBehaviorSettingsRepository(store),
        )
        val pending = PendingHistoryStore(db.pendingTrackDao())

        init { runBlocking { db.songDao().upsertAll(listOf(songEntity(5))) } }

        suspend fun restore(b: WavdropBackup, mode: Mode) = if (mode == Mode.MERGE) merge.applyImport(b) else recovery.applyRecovery(b)
        suspend fun export(f: Fmt): ByteArray = when (f) {
            Fmt.JSON -> exporter.buildBackupJson().toByteArray(Charsets.UTF_8)
            Fmt.WDBK -> ByteArrayOutputStream().also { WdbkWriter().write(exporter.buildWdbkExportSnapshot(), it) }.toByteArray()
        }
        /** (songId-free) sorted multiset of "occurredAt:listenedMs:durationMs" per stored event row. */
        suspend fun rows(): List<String> = pending.loadAll()?.events.orEmpty().map { "${it.occurredAt}:${it.listenedMs}:${it.durationMs}" }.sorted()
        suspend fun fingerprints(): List<String> = db.pendingTrackDao().getAllEventFingerprints().sorted()
        fun sql(statement: String) { db.openHelper.writableDatabase.execSQL(statement) }
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

    private fun load(f: Fmt, b: WavdropBackup) = parse(f, serialize(f, b))

    private fun ev(songId: Long, eventId: String?, durationMs: Long = 180_000L, at: Long = 5_000L, listenedMs: Long = 60_000L) =
        backupEvent(songId, eventId, at).copy(durationMs = durationMs, listenedMs = listenedMs)

    private fun backup(events: List<BackupListenEvent>, id: String = "00000000-0000-0000-0000-0000000000c1") =
        RecoveryTestFixtures.v2Backup(songs = listOf(backupSong(1)), stats = listOf(backupStats(1, 1, 0, false)), events = events, backupId = id)

    private fun d(ms: Long) = "5000:60000:$ms"

    /** Reproduces the pre-18C state: only slot 0 stays, holding [survivorDuration]. */
    private fun Node.collapseToSurvivor(survivorDuration: Long) {
        sql("DELETE FROM pending_listen_events WHERE originFingerprint LIKE '%|n=%'")
        sql("UPDATE pending_listen_events SET durationMs = $survivorDuration")
    }

    // 1. different ids and different durations are kept
    @Test fun `distinct ids and distinct durations are all kept in both modes and formats`() = runBlocking {
        for (f in Fmt.values()) for (mode in Mode.values()) {
            val n = Node()
            val r = n.restore(load(f, backup(listOf(ev(1, "a", 100_000L), ev(1, "b", 200_000L), ev(1, "c", 200_000L), ev(1, "d", 300_000L)))), mode)
            assertEquals("$f $mode", listOf(d(100_000), d(200_000), d(200_000), d(300_000)), n.rows())
            assertEquals("count equals persisted rows", 4, r.pendingEventsPreserved)
        }
    }

    // 2. same id + identical payload = one event
    @Test fun `the same event id with an identical payload is one event`() = runBlocking {
        for (mode in Mode.values()) {
            val n = Node()
            val r = n.restore(load(Fmt.JSON, backup(listOf(ev(1, "same"), ev(1, "same"), ev(1, "same")))), mode)
            assertEquals(listOf(d(180_000)), n.rows())
            assertEquals(1, r.pendingEventsPreserved)
        }
    }

    // 3. same id, conflicting payload: both preserved, never overwritten
    @Test fun `the same event id with a conflicting duration keeps both payloads and overwrites nothing`() = runBlocking {
        for (mode in Mode.values()) {
            val n = Node()
            n.restore(load(Fmt.JSON, backup(listOf(ev(1, "x", 100_000L), ev(1, "x", 200_000L)))), mode)
            assertEquals(listOf(d(100_000), d(200_000)), n.rows())
        }
    }

    // 4. null id multiplicity
    @Test fun `identical null-id records keep their multiplicity and invent no ids`() = runBlocking {
        for (mode in Mode.values()) {
            val n = Node()
            val r = n.restore(load(Fmt.JSON, backup(listOf(ev(1, null), ev(1, null), ev(1, null)))), mode)
            assertEquals(listOf(d(180_000), d(180_000), d(180_000)), n.rows())
            assertEquals(3, r.pendingEventsPreserved)
            assertEquals(3, n.fingerprints().toSet().size)
        }
    }

    // 5. idempotence
    @Test fun `repeating an import inserts zero and changes nothing`() = runBlocking {
        for (f in Fmt.values()) for (mode in Mode.values()) {
            val b = load(f, backup(listOf(ev(1, null, 100_000L), ev(1, null, 200_000L), ev(1, null, 200_000L))))
            val n = Node()
            n.restore(b, mode)
            val before = n.fingerprints()
            val again = n.restore(b, mode)
            assertEquals("$f $mode", 0, again.pendingEventsPreserved)
            assertEquals(before, n.fingerprints())
            assertFalse(n.merge.previewMerge(b).hasMergeableData)
        }
    }

    // 6. canonical-first survivor: repair from the original backup
    @Test fun `a canonical-first legacy survivor is repaired from the original backup without touching its row`() = runBlocking {
        for (f in Fmt.values()) for (mode in Mode.values()) {
            val a = load(f, backup(listOf(ev(1, null, 100_000L), ev(1, null, 200_000L))))
            val n = Node()
            n.restore(a, mode)
            n.collapseToSurvivor(100_000L)
            assertEquals(listOf(d(100_000)), n.rows())
            val survivorRowId = n.db.openHelper.readableDatabase.query("SELECT id FROM pending_listen_events").use { it.moveToFirst(); it.getLong(0) }
            if (mode == Mode.MERGE) assertTrue("preview sees the missing occurrence", n.merge.previewMerge(a).hasMergeableData)
            val r = n.restore(a, mode)
            assertEquals("$f $mode: only the missing one is inserted and reported", 1, r.pendingEventsPreserved)
            assertEquals(listOf(d(100_000), d(200_000)), n.rows())
            val still = n.db.openHelper.readableDatabase.query("SELECT id FROM pending_listen_events WHERE durationMs = 100000").use { it.moveToFirst(); it.getLong(0) }
            assertEquals("the survivor row is not replaced", survivorRowId, still)
            assertEquals("no inflation on a further import", 0, n.restore(a, mode).pendingEventsPreserved)
        }
    }

    // 7. non-canonical-first survivor
    @Test fun `a non-canonical legacy survivor is accounted for and the missing occurrence is added`() = runBlocking {
        for (f in Fmt.values()) for (mode in Mode.values()) {
            val a = load(f, backup(listOf(ev(1, null, 100_000L), ev(1, null, 200_000L))))
            val n = Node()
            n.restore(a, mode)
            n.collapseToSurvivor(200_000L)                 // the survivor was the LATER-sorting payload
            val r = n.restore(a, mode)
            assertEquals("$f $mode", 1, r.pendingEventsPreserved)
            assertEquals(listOf(d(100_000), d(200_000)), n.rows())
            assertEquals(0, n.restore(a, mode).pendingEventsPreserved)
        }
    }

    // 8. irreconcilable conflict: a stored row that no occurrence in the backup explains
    @Test fun `a stored row that no backup occurrence accounts for is a conflict - nothing is applied`() = runBlocking {
        for (mode in Mode.values()) {
            val a = load(Fmt.JSON, backup(listOf(ev(1, null, 100_000L), ev(1, null, 200_000L), ev(90, null, 300_000L))))
            val n = Node()
            n.restore(a, mode)
            n.collapseToSurvivor(999_999L)
            // Track 90 is removed so the re-import would insert it NEW (earlier in the transaction than the conflicting repair of track 1).
            n.sql("DELETE FROM pending_tracks WHERE backupSongId = '90'")
            val before = Triple(n.fingerprints(), n.rows(), n.db.pendingTrackDao().getAllOriginKeys().sorted())
            try {
                n.restore(a, mode)
                fail("$mode: a contradictory stored row must not be guessed around")
            } catch (_: PendingHistoryConflictException) { }
            assertEquals("$mode: rolled back, no partial insert", before, Triple(n.fingerprints(), n.rows(), n.db.pendingTrackDao().getAllOriginKeys().sorted()))
        }
    }

    // 9. Backup B (survivor only) cannot reconstruct the lost occurrence
    @Test fun `a later backup that carries only the survivor cannot reconstruct anything`() = runBlocking {
        for (f in Fmt.values()) {
            val a = load(f, backup(listOf(ev(1, null, 100_000L), ev(1, null, 200_000L))))
            val b = Node()
            b.restore(a, Mode.MERGE)
            b.collapseToSurvivor(100_000L)
            val bBackup = load(f, parse(f, b.export(f)))
            assertEquals(1, bBackup.pendingHistory!!.events.size)
            val c = Node()
            c.restore(bBackup, Mode.MERGE)
            assertEquals("$f: nothing is fabricated", listOf(d(100_000)), c.rows())
        }
    }

    // 10 + 17. pending-history export/import retains the repaired set; A -> B -> C -> D without inflation
    @Test fun `A to B to C to D keeps exactly the repaired set in every mode and format`() = runBlocking {
        for (f in Fmt.values()) for (mode in Mode.values()) {
            val a = load(f, backup(listOf(ev(1, null, 100_000L), ev(1, null, 200_000L), ev(1, null, 200_000L))))
            var node = Node()
            node.restore(a, mode)
            node.collapseToSurvivor(200_000L)
            node.restore(a, mode)                          // repaired from A
            val expected = listOf(d(100_000), d(200_000), d(200_000))
            assertEquals(expected, node.rows())
            repeat(3) {
                val next = Node()
                next.restore(load(f, parse(f, node.export(f))), mode)
                assertEquals("$f $mode hop ${it + 1}", expected, next.rows())
                node = next
            }
        }
    }

    // 11 + 12. Merge/Recovery parity, descriptor (song 1) and orphan (song 90) parity
    @Test fun `Merge and Recovery store identical event rows for descriptor and orphan tracks`() = runBlocking {
        val events = listOf(1L, 90L).flatMap { id -> listOf(ev(id, null, 100_000L), ev(id, "q", 200_000L), ev(id, "r", 200_000L)) }
        val b = load(Fmt.JSON, backup(events))
        val m = Node().also { it.restore(b, Mode.MERGE) }
        val r = Node().also { it.restore(b, Mode.RECOVERY) }
        assertEquals(6, m.rows().size)
        assertEquals(m.fingerprints(), r.fingerprints())
        assertEquals(m.rows(), r.rows())
    }

    // 13. preview reports missing occurrences, then reports nothing to do
    @Test fun `preview flags a collapsed track as mergeable and a repaired one as a no-op`() = runBlocking {
        val a = load(Fmt.JSON, backup(listOf(ev(1, null, 100_000L), ev(1, null, 200_000L))))
        val n = Node()
        n.restore(a, Mode.MERGE)
        assertFalse(n.merge.previewMerge(a).hasMergeableData)
        n.collapseToSurvivor(100_000L)
        assertTrue(n.merge.previewMerge(a).hasMergeableData)
        n.restore(a, Mode.MERGE)
        assertFalse(n.merge.previewMerge(a).hasMergeableData)
    }

    // 19. a stale preview does not matter: restore recomputes against the database
    @Test fun `restore stays authoritative when the preview was computed before the state changed`() = runBlocking {
        val a = load(Fmt.JSON, backup(listOf(ev(1, null, 100_000L), ev(1, null, 200_000L))))
        val n = Node()
        n.restore(a, Mode.MERGE)
        n.collapseToSurvivor(100_000L)
        val stale = n.merge.previewMerge(a)
        assertTrue(stale.hasMergeableData)
        n.restore(a, Mode.MERGE)                           // repair happens between preview and apply
        assertEquals(0, n.restore(a, Mode.MERGE).pendingEventsPreserved)
        assertEquals(listOf(d(100_000), d(200_000)), n.rows())
    }

    // repair touches only events: stats, lyrics, live data, metadata of the existing pending track
    @Test fun `repairing events leaves the pending track and the live library untouched`() = runBlocking {
        val a = load(Fmt.JSON, backup(listOf(ev(1, null, 100_000L), ev(1, null, 200_000L))))
        val n = Node()
        n.restore(a, Mode.MERGE)
        n.collapseToSurvivor(100_000L)
        val tracksBefore = n.db.pendingTrackDao().getAllTracksForExport()
        val statsBefore = n.db.pendingTrackDao().getAllStatsForExport()
        val liveBefore = n.db.trackListenEventDao().getAllSnapshot()
        n.restore(a, Mode.MERGE)
        assertEquals(tracksBefore, n.db.pendingTrackDao().getAllTracksForExport())
        assertEquals(statsBefore, n.db.pendingTrackDao().getAllStatsForExport())
        assertEquals(liveBefore, n.db.trackListenEventDao().getAllSnapshot())
    }

    // ── P5-STATS-18C1 ─────────────────────────────────────────────────────────

    /** fingerprint -> duration of every stored pending event. */
    private suspend fun Node.byFingerprint(): Map<String, Long> =
        pending.loadAll()?.events.orEmpty().associate { it.originFingerprint to it.durationMs }

    /** A valid NON-canonical repaired group: base -> 200000 and base|n=1 -> 100000. */
    private suspend fun nonCanonicalNode(a: WavdropBackup, mode: Mode = Mode.MERGE): Node {
        val n = Node()
        n.restore(a, mode)
        n.sql("UPDATE pending_listen_events SET durationMs = CASE WHEN originFingerprint LIKE '%|n=1' THEN 100000 ELSE 200000 END")
        return n
    }

    private fun groupBackup() = backup(listOf(ev(1, null, 100_000L), ev(1, null, 200_000L)))

    @Test fun `carried non-canonical fingerprints keep their payload mapping across a pending-history round trip`() = runBlocking {
        for (f in Fmt.values()) for (mode in Mode.values()) {
            val source = nonCanonicalNode(load(f, groupBackup()))
            val expected = source.byFingerprint()
            assertEquals(2, expected.size)
            assertTrue("fixture really is non-canonical", expected.entries.single { !it.key.contains("|n=") }.value == 200_000L)
            val carried = load(f, parse(f, source.export(f)))
            val dest = Node()
            val r = dest.restore(carried, mode)
            assertEquals("$f $mode: verbatim", expected, dest.byFingerprint())
            assertEquals(2, r.pendingEventsPreserved)
            // repeated restore and an overlapping restore (same section again) add nothing and relabel nothing
            assertEquals(0, dest.restore(carried, mode).pendingEventsPreserved)
            assertEquals(expected, dest.byFingerprint())
        }
    }

    @Test fun `an overlapping carried section preserves established fingerprints and adds only the absent ones`() = runBlocking {
        val a = groupBackup()
        val source = nonCanonicalNode(load(Fmt.JSON, a))
        val sectionOne = load(Fmt.JSON, parse(Fmt.JSON, source.export(Fmt.JSON)))
        val dest = Node()
        dest.restore(sectionOne, Mode.MERGE)
        // the source later gains one more event for the same snapshot track (a bigger group); the overlapping section carries all three
        source.sql("INSERT INTO pending_listen_events (pendingId, eventType, occurredAt, listenedMs, durationMs, originFingerprint) " +
            "SELECT pendingId, eventType, occurredAt, listenedMs, 300000, originFingerprint || '|n=2' FROM pending_listen_events WHERE originFingerprint NOT LIKE '%|n=%'")
        val expected = source.byFingerprint()
        assertEquals(3, expected.size)
        val r = dest.restore(load(Fmt.JSON, parse(Fmt.JSON, source.export(Fmt.JSON))), Mode.MERGE)
        assertEquals("only the absent fingerprint is inserted", 1, r.pendingEventsPreserved)
        assertEquals(expected, dest.byFingerprint())
    }

    @Test fun `a carried fingerprint whose payload contradicts the stored row fails atomically`() = runBlocking {
        for (mode in Mode.values()) {
            val a = groupBackup()
            val source = nonCanonicalNode(load(Fmt.JSON, a))               // base:200000, n=1:100000
            val carried = load(Fmt.JSON, parse(Fmt.JSON, source.export(Fmt.JSON)))
            val dest = Node()
            dest.restore(a, mode)                                           // canonical labelling: base:100000, n=1:200000
            dest.sql("DELETE FROM pending_tracks WHERE backupSongId = '90'")
            val before = dest.byFingerprint()
            try {
                dest.restore(carried, mode)
                fail("$mode: the same fingerprint with another payload must not be relabelled or ignored")
            } catch (_: PendingHistoryConflictException) { }
            assertEquals("$mode: nothing changed", before, dest.byFingerprint())
        }
    }

    @Test fun `a carried event with a malformed fingerprint suffix is rejected, not normalised`() {
        val source = runBlocking { Node().also { it.restore(load(Fmt.JSON, groupBackup()), Mode.MERGE) } }
        val section = runBlocking { source.pending.loadAll()!! }
        for (bad in listOf("|n=0", "|n=01", "|n=", "|n=x", "|n=1|n=2")) {
            val broken = section.copy(events = section.events.map { it.copy(originFingerprint = it.originFingerprint + bad) })
            try {
                PendingHistoryCodec.validate(broken)
                fail("suffix $bad must be rejected")
            } catch (_: BackupParseException) { }
        }
        PendingHistoryCodec.validate(section)                                // the real section is accepted
    }

    // Finding B: other tracks of the same snapshot must not mask a missing occurrence
    @Test fun `a missing occurrence on one track is detected although other tracks hold many events`() = runBlocking {
        val a = groupBackup()
        val n = Node()
        n.restore(a, Mode.MERGE)
        val snapshotFp = n.pending.loadAll()!!.core.tracks.single().backupFingerprint
        // Unrelated track of the SAME snapshot with 5 stored events (more than the 2 expected for song 1).
        val dao = n.db.pendingTrackDao()
        dao.insertTrack(com.launchpoint.wavdrop.data.local.entity.PendingTrackEntity(
            originKey = "$snapshotFp|777", backupFingerprint = snapshotFp, backupSongId = "777", title = "t", artist = "a", album = "b",
            duration = 1, trackNumber = 0, year = 0, sourceUri = null, sourceFolderPath = null, restoredAt = 1))
        val other = dao.getPendingId("$snapshotFp|777")!!
        for (i in 1..5) dao.insertEvent(com.launchpoint.wavdrop.data.local.entity.PendingListenEventEntity(
            pendingId = other, eventType = "PLAY", occurredAt = 9_000L + i, listenedMs = 1, durationMs = 1, originFingerprint = "$snapshotFp|777|${9_000L + i}|PLAY|1"))
        assertFalse("complete: nothing to do", n.merge.previewMerge(a).hasMergeableData)
        n.collapseToSurvivor(100_000L)                      // collapses song 1's group (and only it: track 777 keeps rows)
        assertTrue("stored snapshot total (6) >= expected (2), yet the occurrence is missing", n.merge.previewMerge(a).hasMergeableData)
        n.restore(a, Mode.MERGE)
        assertFalse("fully repaired: no-op", n.merge.previewMerge(a).hasMergeableData)
        assertEquals("track 777 untouched", 5, dao.getEventsInFingerprintRange("$snapshotFp|777|", "$snapshotFp|777}").size)
    }

    @Test fun `equal row counts cannot hide a contradictory stored row - preview flags it and apply refuses`() = runBlocking {
        val a = groupBackup()
        val n = Node()
        n.restore(a, Mode.MERGE)
        n.sql("UPDATE pending_listen_events SET durationMs = 999999 WHERE originFingerprint LIKE '%|n=1'")   // 2 rows stored, 2 expected
        assertTrue(n.merge.previewMerge(a).hasMergeableData)
        try { n.restore(a, Mode.MERGE); fail("conflict expected") } catch (_: PendingHistoryConflictException) { }
        assertEquals(2, n.rows().size)
    }

    // 18. large group: deterministic (input-order independent) and bounded
    @Test fun `a large collision group is deterministic regardless of input order and idempotent`() = runBlocking {
        val events = (0 until 3000).map { i -> ev(1, null, durationMs = 1_000L + (i % 50)) }
        val sortedBackup = load(Fmt.JSON, backup(events))
        val shuffledBackup = load(Fmt.JSON, backup(events.shuffled(java.util.Random(7))))
        val one = Node().also { it.restore(sortedBackup, Mode.MERGE) }
        val two = Node().also { it.restore(shuffledBackup, Mode.RECOVERY) }
        assertEquals(3000, one.rows().size)
        assertEquals(one.fingerprints(), two.fingerprints())
        assertEquals(one.rows(), two.rows())
        assertEquals(0, one.restore(sortedBackup, Mode.MERGE).pendingEventsPreserved)
        one.collapseToSurvivor(1_000L)
        assertEquals(2999, one.restore(sortedBackup, Mode.MERGE).pendingEventsPreserved)
        assertEquals(two.rows(), one.rows())
    }
}
