package com.launchpoint.wavdrop.data.backup

import androidx.room.Room
import com.launchpoint.wavdrop.data.backup.RecoveryTestFixtures.backupEvent
import com.launchpoint.wavdrop.data.backup.RecoveryTestFixtures.backupSong
import com.launchpoint.wavdrop.data.backup.RecoveryTestFixtures.backupStats
import com.launchpoint.wavdrop.data.backup.wdbk.WdbkReader
import com.launchpoint.wavdrop.data.backup.wdbk.WdbkTestSupport
import com.launchpoint.wavdrop.data.local.WavdropDatabase
import com.launchpoint.wavdrop.data.local.entity.SongEntity
import com.launchpoint.wavdrop.data.local.entity.TrackListenEventEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream

/**
 * P5-STATS-18D - live listening-event identity on the real Merge and Recovery paths (real in-memory Room, real parser / `.wdbk`
 * reader). Song 5 (and 6) exist in the destination library, so their events are LIVE events. Synthetic data only.
 *
 * Contract under test: a non-null eventId is the identity (same id + same payload = one event, same id + different payload = reported
 * conflict, different ids = different events); null-id (legacy) records keep SOURCE multiplicity with a count comparison, include
 * durationMs, and never suppress or are suppressed by ID-bearing rows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LiveEventIdentityRoomTest {

    private enum class Fmt { JSON, WDBK }
    private enum class Mode { MERGE, RECOVERY }

    private val dbs = mutableListOf<WavdropDatabase>()
    @After fun tearDown() { dbs.forEach { runCatching { it.close() } } }

    private inner class Node {
        val db: WavdropDatabase = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), WavdropDatabase::class.java)
            .allowMainThreadQueries().build().also { dbs += it }
        val merge = WavdropBackupImportRepository(
            db, db.songDao(), db.trackStatsDao(), db.lyricsOverrideDao(), db.importBaselineDao(), db.playlistDao(),
            db.trackListenEventDao(), db.pendingTrackDao(), db.pendingBackupExtensionDao(),
        )
        val recovery = RecoveryRestoreRepository(
            db, db.songDao(), db.trackStatsDao(), db.lyricsOverrideDao(), db.importBaselineDao(), db.playlistDao(),
            db.trackListenEventDao(), db.pendingTrackDao(), db.pendingBackupExtensionDao(),
        )
        val events get() = db.trackListenEventDao()

        init { runBlocking { db.songDao().upsertAll(listOf(songEntity(5), songEntity(6))) } }

        suspend fun restore(b: WavdropBackup, mode: Mode) = if (mode == Mode.MERGE) merge.applyImport(b) else recovery.applyRecovery(b)
        suspend fun live(): List<TrackListenEventEntity> = events.getAllSnapshot()
        /** Order-independent description of every stored event row: song|at|type|listened|duration|eventId. */
        suspend fun rows(): List<String> = live().map { "${it.songId}|${it.occurredAt}|${it.eventType}|${it.listenedMs}|${it.durationMs}|${it.eventId}" }.sorted()
        suspend fun insertLocal(songId: Long, at: Long, eventId: String?, durationMs: Long = 180_000L, source: String = TrackListenEventEntity.SOURCE_WAVDROP_PLAYBACK) =
            events.insert(TrackListenEventEntity(songId = songId, eventType = "PLAY", occurredAt = at, listenedMs = 60_000L, durationMs = durationMs, source = source, eventId = eventId))
    }

    private fun songEntity(id: Long) = backupSong(id).let {
        SongEntity(id, it.title, it.artist, it.album, it.albumId, it.duration, it.uri, it.dateAdded, it.trackNumber, it.year, it.folderPath, it.folderName)
    }

    private fun ev(songId: Long, eventId: String?, at: Long = 5_000L, durationMs: Long = 180_000L) =
        backupEvent(songId, eventId, at).copy(durationMs = durationMs)

    private fun backup(events: List<BackupListenEvent>, id: String = "00000000-0000-0000-0000-0000000000d1") =
        RecoveryTestFixtures.v2Backup(
            songs = listOf(backupSong(5), backupSong(6)), stats = listOf(backupStats(5, 1, 0, false), backupStats(6, 1, 0, false)),
            events = events, backupId = id,
        )

    private fun load(f: Fmt, b: WavdropBackup): WavdropBackup {
        val r = when (f) {
            Fmt.JSON -> WavdropBackupParser.parse(WavdropBackupExporterV2.toJson(b))
            Fmt.WDBK -> WdbkReader().read(ByteArrayInputStream(WdbkTestSupport.write(b).first), RecoveryTestFixtures.NOW + 1_000_000L)
        }
        assertNull(r.error, r.error)
        assertEquals(BackupIntegrityStatus.VERIFIED, r.integrityStatus)
        return r.backup!!
    }

    private fun id(songId: Long, eventId: String?, at: Long = 5_000L, durationMs: Long = 180_000L) =
        "$songId|$at|PLAY|60000|$durationMs|$eventId"

    // 1 + 13 + 14. different ids, identical metadata: both survive; JSON/.wdbk and Merge/Recovery agree
    @Test fun `two events with different ids and identical metadata both survive in every format and mode`() = runBlocking {
        val expected = listOf(id(5, "id-one"), id(5, "id-two")).sorted()
        for (f in Fmt.values()) for (mode in Mode.values()) {
            val n = Node()
            val r = n.restore(load(f, backup(listOf(ev(5, "id-one"), ev(5, "id-two")))), mode)
            assertEquals("$f $mode", expected, n.rows())
            assertEquals(2, r.eventsRestored)
            assertEquals(0, r.eventsSkippedDuplicate)
        }
    }

    // 2 + 12. same id + payload: one event, and repeated/overlapping snapshots never inflate
    @Test fun `the same id and payload is one event and repeated or overlapping snapshots do not inflate`() = runBlocking {
        for (mode in Mode.values()) {
            val n = Node()
            val first = n.restore(load(Fmt.JSON, backup(listOf(ev(5, "same"), ev(5, "same")))), mode)
            assertEquals(listOf(id(5, "same")), n.rows())
            assertEquals(1, first.eventsRestored); assertEquals(1, first.eventsSkippedDuplicate)
            if (mode == Mode.MERGE) {
                // a DIFFERENT snapshot (other backupId, extra unrelated event) that overlaps: only the new event is added
                val second = n.restore(load(Fmt.JSON, backup(listOf(ev(5, "same"), ev(5, "later", at = 9_000L)), id = "00000000-0000-0000-0000-0000000000d2")), Mode.MERGE)
                assertEquals(1, second.eventsRestored)
                assertEquals(listOf(id(5, "same"), id(5, "later", at = 9_000L)).sorted(), n.rows())
                assertTrue(n.restore(load(Fmt.JSON, backup(listOf(ev(5, "same"), ev(5, "later", at = 9_000L)), id = "00000000-0000-0000-0000-0000000000d3")), Mode.MERGE).isNoOp)
            }
        }
    }

    // 3 + 10. same id, conflicting payload (different duration / timestamp / song): reported, first wins, nothing overwritten
    @Test fun `the same id with a conflicting payload in one backup is reported and never overwrites`() = runBlocking {
        val conflicts = mapOf(
            "duration" to listOf(ev(5, "x", durationMs = 100_000L), ev(5, "x", durationMs = 200_000L)),
            "timestamp" to listOf(ev(5, "x", at = 5_000L), ev(5, "x", at = 6_000L)),
            "song mapping" to listOf(ev(5, "x"), ev(6, "x")),
        )
        for ((label, events) in conflicts) for (mode in Mode.values()) {
            val n = Node()
            val r = n.restore(load(Fmt.JSON, backup(events)), mode)
            assertEquals("$label $mode: only the first occurrence is stored", 1, n.live().size)
            assertEquals("$label $mode", 1, r.eventsSkippedConflict)
            assertEquals("$label $mode: a conflict is not a duplicate", 0, r.eventsSkippedDuplicate)
            assertEquals(events.first().occurredAt, n.live().single().occurredAt)
        }
    }

    @Test fun `an incoming id that matches a stored row with a different payload is a conflict and the stored row is untouched`() = runBlocking {
        val n = Node()
        n.insertLocal(5, at = 5_000L, eventId = "x", durationMs = 111_000L, source = TrackListenEventEntity.SOURCE_WAVDROP_PLAYBACK)
        val before = n.rows()
        // events only (no stats), so the conflict is the ONLY thing in the backup: it is not an actionable write
        val onlyEvents = load(Fmt.JSON, RecoveryTestFixtures.v2Backup(
            songs = listOf(backupSong(5)), stats = emptyList(), events = listOf(ev(5, "x", durationMs = 180_000L)),
            backupId = "00000000-0000-0000-0000-0000000000d9"))
        assertFalse("a conflict alone is not an actionable write", n.merge.previewMerge(onlyEvents).hasMergeableData)
        assertTrue(n.restore(onlyEvents, Mode.MERGE).isNoOp)
        // with stats the import proceeds, and the conflict is counted explicitly
        val r = n.restore(load(Fmt.JSON, backup(listOf(ev(5, "x", durationMs = 180_000L)))), Mode.MERGE)
        assertEquals(1, r.eventsSkippedConflict)
        assertEquals(0, r.eventsRestored)
        assertEquals("no overwrite", before, n.rows())
    }

    @Test fun `an existing local row with the same id and payload but another source is a safe duplicate`() = runBlocking {
        val n = Node()
        n.insertLocal(5, at = 5_000L, eventId = "x", source = TrackListenEventEntity.SOURCE_DESKTOP_PLAYBACK)
        val r = n.restore(load(Fmt.JSON, backup(listOf(ev(5, "x")))), Mode.MERGE)
        assertEquals(1, r.eventsSkippedDuplicate)
        assertEquals(0, r.eventsSkippedConflict)
        assertEquals(1, n.live().size)
        assertEquals(TrackListenEventEntity.SOURCE_DESKTOP_PLAYBACK, n.live().single().source)
    }

    // 4 + 5 + 6. legacy twin occurrences
    @Test fun `two identical null-id records survive Recovery and an empty-destination Merge and a repeat adds nothing`() = runBlocking {
        for (f in Fmt.values()) for (mode in Mode.values()) {
            val b = load(f, backup(listOf(ev(5, null), ev(5, null))))
            val n = Node()
            val r = n.restore(b, mode)
            assertEquals("$f $mode", listOf(id(5, null), id(5, null)), n.rows())
            assertEquals(2, r.eventsRestored)
            if (mode == Mode.MERGE) {
                assertFalse(n.merge.previewMerge(b).hasMergeableData)
                assertEquals("repeat Merge", 0, n.restore(b, Mode.MERGE).eventsRestored)
                assertEquals(2, n.live().size)
            }
        }
    }

    // 7. destination holds one of two: only the missing one is added, the local row is preserved
    @Test fun `a destination with one of two legacy occurrences gains only the missing one`() = runBlocking {
        val n = Node()
        n.insertLocal(5, at = 5_000L, eventId = null)
        val localRowId = n.live().single().id
        val b = load(Fmt.JSON, backup(listOf(ev(5, null), ev(5, null))))
        assertTrue(n.merge.previewMerge(b).hasMergeableData)
        val r = n.restore(b, Mode.MERGE)
        assertEquals(1, r.eventsRestored)
        assertEquals(1, r.eventsSkippedDuplicate)
        assertEquals(2, n.live().size)
        assertTrue("the existing local row is not rewritten", n.live().any { it.id == localRowId && it.source == TrackListenEventEntity.SOURCE_WAVDROP_PLAYBACK })
    }

    // 8. the two populations never suppress each other
    @Test fun `null-id and id-bearing rows with identical metadata do not suppress each other`() = runBlocking {
        // stored legacy row, incoming ID-bearing event
        val a = Node()
        a.insertLocal(5, at = 5_000L, eventId = null)
        assertEquals(1, a.restore(load(Fmt.JSON, backup(listOf(ev(5, "modern")))), Mode.MERGE).eventsRestored)
        assertEquals(listOf(id(5, null), id(5, "modern")).sorted(), a.rows())
        // stored ID-bearing row, incoming legacy record
        val b = Node()
        b.insertLocal(5, at = 5_000L, eventId = "modern")
        assertEquals(1, b.restore(load(Fmt.JSON, backup(listOf(ev(5, null)))), Mode.MERGE).eventsRestored)
        assertEquals(listOf(id(5, null), id(5, "modern")).sorted(), b.rows())
        // both in one backup (Recovery too)
        for (mode in Mode.values()) {
            val n = Node()
            n.restore(load(Fmt.JSON, backup(listOf(ev(5, null), ev(5, "modern")))), mode)
            assertEquals("$mode", 2, n.live().size)
        }
    }

    // 9. durations distinguish legacy records
    @Test fun `legacy records that differ only in durationMs are distinct`() = runBlocking {
        for (mode in Mode.values()) {
            val n = Node()
            n.restore(load(Fmt.JSON, backup(listOf(ev(5, null, durationMs = 100_000L), ev(5, null, durationMs = 200_000L)))), mode)
            assertEquals(listOf(id(5, null, durationMs = 100_000L), id(5, null, durationMs = 200_000L)), n.rows())
        }
        val n = Node()
        n.insertLocal(5, at = 5_000L, eventId = null, durationMs = 100_000L)
        assertEquals("a stored row with another duration is not the same record", 1,
            n.restore(load(Fmt.JSON, backup(listOf(ev(5, null, durationMs = 200_000L)))), Mode.MERGE).eventsRestored)
    }

    // 11. unrelated local ids with identical metadata are preserved
    @Test fun `existing local rows with unrelated ids and identical metadata are preserved`() = runBlocking {
        val n = Node()
        n.insertLocal(5, at = 5_000L, eventId = "local-a")
        n.insertLocal(5, at = 5_000L, eventId = null)
        val r = n.restore(load(Fmt.JSON, backup(listOf(ev(5, "incoming-b")))), Mode.MERGE)
        assertEquals(1, r.eventsRestored)
        assertEquals(listOf(id(5, "local-a"), id(5, null), id(5, "incoming-b")).sorted(), n.rows())
    }

    // 15 + 16. preview / apply parity and a stale preview
    @Test fun `preview reports actionable events by the same rules and a stale preview cannot duplicate`() = runBlocking {
        val b = load(Fmt.JSON, backup(listOf(ev(5, "p1"), ev(5, null), ev(5, null))))
        val n = Node()
        assertTrue(n.merge.previewMerge(b).hasMergeableData)
        // playback (or another restore) writes one of the events AFTER the preview and BEFORE the user confirms
        n.insertLocal(5, at = 5_000L, eventId = "p1")
        val r = n.restore(b, Mode.MERGE)
        assertEquals("apply recomputed against the live table: only the two legacy records were new", 2, r.eventsRestored)
        assertEquals(1, r.eventsSkippedDuplicate)
        assertEquals(3, n.live().size)
        assertFalse(n.merge.previewMerge(b).hasMergeableData)
        assertTrue(n.restore(b, Mode.MERGE).isNoOp)
        assertEquals(3, n.live().size)
    }

    // v1 (no ids, no durations guaranteed) and v2 compatibility
    @Test fun `a legacy v1 backup keeps source multiplicity and stays idempotent`() = runBlocking {
        val v1 = WavdropBackup(
            exportedAt = "2026-06-11T10:00:00Z", songs = listOf(backupSong(5)),
            trackStats = listOf(backupStats(5, 2, 0, false).copy(lastListenedAt = null)), importBaselines = emptyList(),
            listenEvents = listOf(ev(5, null), ev(5, null), ev(5, null, at = 7_000L)),
        )
        val n = Node()
        assertEquals(3, n.restore(v1, Mode.MERGE).eventsRestored)
        assertEquals(0, n.restore(v1, Mode.MERGE).eventsRestored)
        assertEquals(3, n.live().size)
    }

    // ── P5-STATS-18D1: stable ids are detected globally, not only inside the backup's time window ──────────────────────────────

    private val farAway = 900_000_000L

    private fun onlyEvents(events: List<BackupListenEvent>, id: String = "00000000-0000-0000-0000-0000000000e1", songs: List<Long> = listOf(5L)) =
        RecoveryTestFixtures.v2Backup(songs = songs.map { backupSong(it) }, stats = emptyList(), events = events, backupId = id)

    @Test fun `the same id stored outside the backup time window is detected as a conflict and nothing is duplicated`() = runBlocking {
        val n = Node()
        n.insertLocal(5, at = 5_000L, eventId = "X")                     // T1
        val before = n.rows()
        val b = load(Fmt.JSON, onlyEvents(listOf(ev(5, "X", at = farAway))))   // T2, window [T2, T2] does not contain T1
        assertFalse("a lone conflict is not an actionable write", n.merge.previewMerge(b).hasMergeableData)
        assertTrue(n.restore(b, Mode.MERGE).isNoOp)
        // with stats the import proceeds, and the conflict is reported
        val r = n.restore(load(Fmt.JSON, backup(listOf(ev(5, "X", at = farAway)))), Mode.MERGE)
        assertEquals(1, r.eventsSkippedConflict)
        assertEquals(0, r.eventsRestored)
        assertEquals("no second row with id X, no overwrite", before, n.rows())
    }

    @Test fun `the same id on a different song outside the time window is a conflict`() = runBlocking {
        val n = Node()
        n.insertLocal(6, at = 5_000L, eventId = "X")
        val r = n.restore(load(Fmt.JSON, backup(listOf(ev(5, "X", at = farAway)))), Mode.MERGE)
        assertEquals(1, r.eventsSkippedConflict)
        assertEquals(listOf(id(6, "X")), n.rows())
    }

    @Test fun `a distinct id with identical metadata is still restored next to an out-of-window stored id`() = runBlocking {
        val n = Node()
        n.insertLocal(5, at = 5_000L, eventId = "X")
        val r = n.restore(load(Fmt.JSON, backup(listOf(ev(5, "Y", at = 5_000L), ev(5, "X", at = farAway)))), Mode.MERGE)
        assertEquals(1, r.eventsRestored)
        assertEquals(1, r.eventsSkippedConflict)
        assertEquals(listOf(id(5, "X"), id(5, "Y")).sorted(), n.rows())
    }

    @Test fun `an identical id and payload outside any other window is a plain duplicate and a repeat adds nothing`() = runBlocking {
        for (f in Fmt.values()) {
            val n = Node()
            n.insertLocal(5, at = farAway, eventId = "X")
            val b = load(f, backup(listOf(ev(5, "X", at = farAway), ev(5, "W", at = 5_000L))))
            val r = n.restore(b, Mode.MERGE)
            assertEquals("$f", 1, r.eventsSkippedDuplicate)
            assertEquals(1, r.eventsRestored)
            assertEquals(0, r.eventsSkippedConflict)
            assertEquals(2, n.live().size)
            assertTrue("$f repeat", n.restore(b, Mode.MERGE).isNoOp)
        }
    }

    @Test fun `a stale preview cannot let an out-of-window id duplicate - apply recomputes against the table`() = runBlocking {
        val n = Node()
        val b = load(Fmt.JSON, onlyEvents(listOf(ev(5, "X", at = farAway))))
        assertTrue("nothing stored yet: actionable", n.merge.previewMerge(b).hasMergeableData)
        n.insertLocal(5, at = 5_000L, eventId = "X")                       // playback / another restore wrote X after the preview
        assertTrue("apply sees the conflict and writes nothing", n.restore(b, Mode.MERGE).isNoOp)
        assertEquals(listOf(id(5, "X")), n.rows())
    }

    @Test fun `Recovery keeps source authority over a stored out-of-window id and never reports a conflict`() = runBlocking {
        val n = Node()
        n.insertLocal(5, at = 5_000L, eventId = "X")                       // exported source: removed by Recovery
        n.insertLocal(5, at = 6_000L, eventId = "keep-me", source = TrackListenEventEntity.SOURCE_BLACKPLAYER_IMPORT)   // not exported: survives
        val r = n.restore(load(Fmt.WDBK, backup(listOf(ev(5, "X", at = farAway)))), Mode.RECOVERY)
        assertEquals(0, r.eventsSkippedConflict)
        assertEquals(listOf(id(5, "keep-me", at = 6_000L), id(5, "X", at = farAway)).sorted(), n.rows())
    }

    @Test fun `mixed legacy and id-bearing events keep the legacy window rule and the global id rule`() = runBlocking {
        val n = Node()
        n.insertLocal(5, at = 5_000L, eventId = "X")                       // out of the incoming window
        n.insertLocal(5, at = farAway, eventId = null)                     // legacy twin inside the window
        val incoming = listOf(ev(5, null, at = farAway), ev(5, null, at = farAway), ev(5, "X", at = farAway), ev(5, "N", at = farAway))
        val r = n.restore(load(Fmt.JSON, backup(incoming)), Mode.MERGE)
        assertEquals("one missing legacy twin + the new id N", 2, r.eventsRestored)
        assertEquals("one legacy twin accounted for", 1, r.eventsSkippedDuplicate)
        assertEquals("X exists with another timestamp", 1, r.eventsSkippedConflict)
        assertEquals(listOf(id(5, "X"), id(5, null, at = farAway), id(5, null, at = farAway), id(5, "N", at = farAway)).sorted(), n.rows())
    }

    @Test fun `id batches far beyond SQLite's bound-parameter limit are matched exactly`() = runBlocking {
        val n = Node()
        val stored = 2_500
        n.events.insertAll((0 until stored).map { i ->
            TrackListenEventEntity(songId = 5, eventType = "PLAY", occurredAt = 1_000L + i, listenedMs = 60_000L, durationMs = 180_000L,
                source = TrackListenEventEntity.SOURCE_WAVDROP_PLAYBACK, eventId = "old$i")
        })
        // 2,500 ids that already exist but at a time far outside the window (conflicts) + 2,500 genuinely new ids
        val incoming = (0 until stored).map { i -> ev(5, "old$i", at = farAway + i) } + (0 until stored).map { i -> ev(5, "fresh$i", at = farAway + i) }
        val before = n.rows()
        val r = n.restore(load(Fmt.JSON, backup(incoming)), Mode.MERGE)
        assertEquals(stored, r.eventsSkippedConflict)
        assertEquals(stored, r.eventsRestored)
        assertEquals(stored * 2, n.live().size)
        val ids = n.live().mapNotNull { it.eventId }
        assertEquals("no duplicate id", ids.size, ids.toSet().size)
        assertTrue("no fabricated id: every stored id came from the table or the backup",
            ids.all { it.startsWith("old") || it.startsWith("fresh") })
        assertTrue("stored rows untouched", n.rows().containsAll(before))
    }

    @Test fun `the identity page query is a rowid range scan without a sort and without a temp b-tree`() = runBlocking {
        val n = Node()
        val cursor = n.db.openHelper.readableDatabase.query(
            "EXPLAIN QUERY PLAN SELECT id, eventId, songId, occurredAt, eventType, listenedMs, durationMs FROM track_listen_events " +
                "WHERE id > ? AND eventId IS NOT NULL ORDER BY id ASC LIMIT ?",
            arrayOf<Any>(Long.MIN_VALUE, 5000),
        )
        val plan = cursor.use { c -> buildList { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("detail"))) } }.joinToString(" | ")
        assertTrue("plan: $plan", plan.contains("USING INTEGER PRIMARY KEY"))
        assertFalse("plan: $plan", plan.contains("TEMP B-TREE"))
        assertFalse("plan: $plan", plan.contains("SCAN track_listen_events USING INDEX"))
    }

    // ── P5-STATS-18D2: Recovery against the rows that SURVIVE it (non-exported sources) ─────────────────────────────────────────

    private val preserved = TrackListenEventEntity.SOURCE_BLACKPLAYER_IMPORT   // never exported, never deleted by Recovery

    @Test fun `a surviving non-exported row with id X blocks a conflicting incoming X - one row, reported, nothing overwritten`() = runBlocking {
        for (f in Fmt.values()) {
            val n = Node()
            n.insertLocal(5, at = 5_000L, eventId = "X", durationMs = 111_000L, source = preserved)
            val before = n.rows()
            val r = n.restore(load(f, backup(listOf(ev(5, "X", at = farAway)))), Mode.RECOVERY)
            assertEquals("$f", 1, r.eventsSkippedConflict)
            assertEquals(0, r.eventsRestored)
            assertEquals("$f: the preserved row is untouched and X exists exactly once", before, n.rows())
            assertEquals(preserved, n.live().single().source)
        }
    }

    @Test fun `a surviving non-exported row with the identical payload makes the incoming X a duplicate`() = runBlocking {
        val n = Node()
        n.insertLocal(5, at = 5_000L, eventId = "X", source = preserved)
        val r = n.restore(load(Fmt.JSON, backup(listOf(ev(5, "X", at = 5_000L)))), Mode.RECOVERY)
        assertEquals(1, r.eventsSkippedDuplicate)
        assertEquals(0, r.eventsSkippedConflict)
        assertEquals(1, n.live().size)
        assertEquals(preserved, n.live().single().source)
    }

    @Test fun `an exported-source row with id X is legitimately replaced and is never compared`() = runBlocking {
        val n = Node()
        n.insertLocal(5, at = 5_000L, eventId = "X", durationMs = 111_000L)           // wavdrop_playback: deleted by Recovery
        val r = n.restore(load(Fmt.JSON, backup(listOf(ev(5, "X", at = farAway)))), Mode.RECOVERY)
        assertEquals(0, r.eventsSkippedConflict)
        assertEquals(0, r.eventsSkippedDuplicate)
        assertEquals(listOf(id(5, "X", at = farAway)), n.rows())
        assertEquals(TrackListenEventEntity.SOURCE_MANUAL_RESTORE, n.live().single().source)
    }

    @Test fun `a surviving X does not affect an unrelated incoming Y, and legacy multiplicity is kept`() = runBlocking {
        val n = Node()
        n.insertLocal(5, at = 5_000L, eventId = "X", source = preserved)
        n.insertLocal(5, at = 7_000L, eventId = null, source = preserved)             // a surviving legacy row must NOT absorb incoming twins
        val r = n.restore(load(Fmt.JSON, backup(listOf(ev(5, "Y", at = 5_000L), ev(5, null, at = 7_000L), ev(5, null, at = 7_000L)))), Mode.RECOVERY)
        assertEquals(3, r.eventsRestored)
        assertEquals(0, r.eventsSkippedConflict)
        assertEquals(listOf(id(5, "X"), id(5, "Y"), id(5, null, at = 7_000L), id(5, null, at = 7_000L), id(5, null, at = 7_000L)).sorted(), n.rows())
    }

    @Test fun `the same id several times inside the backup follows the id rules next to a surviving row`() = runBlocking {
        val n = Node()
        n.insertLocal(5, at = 5_000L, eventId = "X", source = preserved)
        val incoming = listOf(ev(5, "Z", at = 8_000L), ev(5, "Z", at = 8_000L), ev(5, "Z", at = 9_000L), ev(5, "X", at = 5_000L))
        val r = n.restore(load(Fmt.JSON, backup(incoming)), Mode.RECOVERY)
        assertEquals("Z once", 1, r.eventsRestored)
        assertEquals("identical Z, and X equal to the surviving row", 2, r.eventsSkippedDuplicate)
        assertEquals("Z with another timestamp", 1, r.eventsSkippedConflict)
        assertEquals(listOf(id(5, "X"), id(5, "Z", at = 8_000L)).sorted(), n.rows())
    }

    @Test fun `repeated Recovery is idempotent with a surviving row`() = runBlocking {
        for (f in Fmt.values()) {
            val n = Node()
            n.insertLocal(5, at = 5_000L, eventId = "X", durationMs = 111_000L, source = preserved)
            val b = load(f, backup(listOf(ev(5, "X", at = farAway), ev(5, "Q", at = 6_000L), ev(5, null, at = 6_500L), ev(5, null, at = 6_500L))))
            n.restore(b, Mode.RECOVERY)
            val first = n.rows()
            n.restore(b, Mode.RECOVERY)
            assertEquals("$f", first, n.rows())
            val ids = n.live().mapNotNull { it.eventId }
            assertEquals("never a second X", 1, ids.count { it == "X" })
        }
    }

    @Test fun `a failure after the plan rolls everything back and leaves the surviving row alone`() = runBlocking {
        val n = Node()
        n.insertLocal(5, at = 5_000L, eventId = "X", source = preserved)
        n.insertLocal(5, at = 6_000L, eventId = "gone")                                // exported source: would be deleted
        val before = n.rows()
        n.recovery.stageHook = { stage -> if (stage == "eventsWritten") error("injected") }
        try {
            n.restore(load(Fmt.JSON, backup(listOf(ev(5, "X", at = farAway), ev(5, "New", at = 7_000L)))), Mode.RECOVERY)
            org.junit.Assert.fail("expected the injected failure")
        } catch (e: IllegalStateException) { assertEquals("injected", e.message) }
        assertEquals("rolled back in full", before, n.rows())
    }

    // 20. a large synthetic history: paged snapshot, exact counts
    @Test fun `a large existing history is deduplicated exactly through the paged snapshot`() = runBlocking {
        val n = Node()
        val total = 12_000
        val locals = (0 until total).map { i ->
            TrackListenEventEntity(
                songId = 5, eventType = "PLAY", occurredAt = 1_000L + i, listenedMs = 60_000L, durationMs = 180_000L,
                source = TrackListenEventEntity.SOURCE_WAVDROP_PLAYBACK, eventId = if (i % 2 == 0) "e$i" else null,
            )
        }
        n.events.insertAll(locals)
        // the backup repeats every local event (same ids / legacy keys), doubles one legacy record, and adds 100 new ones
        val incoming = locals.map { l -> ev(5, l.eventId, at = l.occurredAt) } +
            ev(5, null, at = 1_001L) +
            (0 until 100).map { ev(5, "new$it", at = 500_000L + it) }
        val b = load(Fmt.JSON, backup(incoming))
        val r = n.restore(b, Mode.MERGE)
        assertEquals("100 new ids + 1 missing legacy twin", 101, r.eventsRestored)
        assertEquals(total + 101, n.live().size)
        assertEquals(0, n.restore(b, Mode.MERGE).eventsRestored)
    }
}
