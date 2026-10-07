package com.launchpoint.wavdrop.data.local.dao

import androidx.room.Room
import com.launchpoint.wavdrop.data.backup.BackupEventExportRules
import com.launchpoint.wavdrop.data.backup.BackupListenEvent
import com.launchpoint.wavdrop.data.backup.CanonicalEventStream
import com.launchpoint.wavdrop.data.backup.EventExportCursor
import com.launchpoint.wavdrop.data.backup.LegacyExportOrder
import com.launchpoint.wavdrop.data.backup.ReferenceFingerprintV2
import com.launchpoint.wavdrop.data.backup.WavdropBackupIntegrityV2
import com.launchpoint.wavdrop.data.backup.toBackupListenEvent
import com.launchpoint.wavdrop.data.backup.wdbk.WdbkExportSnapshot
import com.launchpoint.wavdrop.data.backup.wdbk.WdbkTestSupport
import com.launchpoint.wavdrop.data.backup.wdbk.WdbkWriter
import com.launchpoint.wavdrop.data.local.WavdropDatabase
import com.launchpoint.wavdrop.data.local.entity.SongEntity
import com.launchpoint.wavdrop.data.local.entity.TrackListenEventEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

/**
 * Pins what production legacy export REALLY did with rows that tie on every fingerprint-comparator field, through real
 * Room/SQLite and the production DAO (no test-side sort by id), and proves the WDBK streamed export matches it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LegacyExportTieOrderRobolectricTest {

    private lateinit var db: WavdropDatabase
    private lateinit var dao: TrackListenEventDao

    @Before fun setUp() = openDb()

    private fun openDb() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), WavdropDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.trackListenEventDao()
    }

    @After fun tearDown() = db.close()

    private val song = SongEntity(1, "T", "A", "B", 1, 1000, "content://s/1", 1, 1, 2000)

    private fun tied(id: Long, eventId: String?) = TrackListenEventEntity(
        id = id, songId = 1, eventType = "PLAY", occurredAt = 5_000L, listenedMs = 100, durationMs = 200,
        source = TrackListenEventEntity.SOURCE_WAVDROP_PLAYBACK, eventId = eventId,
    )

    private fun map(e: TrackListenEventEntity) = e.toBackupListenEvent(song)

    /** The real legacy computation: getAllSnapshot() exactly as returned (never re-sorted by id), stable-sorted by EVENT_ORDER. */
    private fun legacyEvents(): List<BackupListenEvent> = runBlocking {
        dao.getAllSnapshot().filter { BackupEventExportRules.shouldExport(it.source) }.map(::map)
            .sortedWith(WavdropBackupIntegrityV2.EVENT_ORDER)
    }

    private val fetch: suspend (EventExportCursor, Long, Int) -> List<TrackListenEventEntity> = { c, upTo, limit ->
        dao.getExportPage(c.occurredAt, c.songId, c.eventType, c.listenedMs, c.durationMs, c.source, c.eventKey, c.id, upTo, limit)
    }

    /** The WDBK streamed fingerprint (what the writer records) for whatever is currently in the table. */
    private fun streamedFingerprint(pageSize: Int = 2): String = runBlocking {
        val small = WdbkTestSupport.fullBackup(eventCount = 0, overlay = false)
        val boundary = dao.getMaxId()
        val snapshot = WdbkExportSnapshot(
            backupId = small.backupId!!, sourceInstallationId = small.sourceInstallationId!!, exportedAtMs = small.exportedAtMs!!,
            appVersionCode = null, appVersionName = null, songs = small.songs, trackStats = small.trackStats,
            importBaselines = small.importBaselines, lyricsOverrides = small.lyricsOverrides, preferences = small.preferences,
            playlists = small.playlists, desktopOverlayRawJson = null,
            openEvents = { CanonicalEventStream(boundary, pageSize, fetch, ::map) },
        )
        WdbkWriter(eventsPerChunk = 3).write(snapshot, ByteArrayOutputStream()).fingerprint
    }

    private fun referenceFingerprint(events: List<BackupListenEvent>): String =
        ReferenceFingerprintV2.fingerprint(WdbkTestSupport.fullBackup(eventCount = 0, overlay = false).copy(listenEvents = events))

    @Test fun `getAllSnapshot visits equal occurredAt rows in descending id order - the observed legacy tie order`() = runBlocking {
        dao.insertAll(listOf(tied(1, null), tied(2, ""), tied(3, "x"), tied(4, null)))
        assertEquals(listOf(4L, 3L, 2L, 1L), dao.getAllSnapshot().map { it.id })
        // Mixed timestamps: occurredAt DESC first, then descending id inside each timestamp.
        dao.insertAll(listOf(tied(5, "a").copy(occurredAt = 9_000L), tied(6, "b").copy(occurredAt = 1L), tied(7, "c").copy(occurredAt = 9_000L)))
        assertEquals(listOf(7L, 5L, 4L, 3L, 2L, 1L, 6L), dao.getAllSnapshot().map { it.id })
        assertEquals(LegacyExportOrder.snapshot(dao.getAllSnapshot()).map { it.id }, dao.getAllSnapshot().map { it.id }) // the fake-table model agrees
    }

    @Test fun `null versus empty eventId - legacy and streamed WDBK fingerprints agree for both insertion arrangements`() = runBlocking {
        val hashes = mutableListOf<String>()
        for ((nullId, emptyId) in listOf(1L to 2L, 2L to 1L)) {
            tearDown(); openDb()
            dao.insertAll(listOf(tied(nullId, null), tied(emptyId, "")).sortedBy { it.id }) // physical insertion in id order
            val legacy = legacyEvents()
            // Observed production tie order: the HIGHER id first.
            assertEquals(if (nullId > emptyId) null else "", legacy[0].eventId)
            assertEquals(0, WavdropBackupIntegrityV2.EVENT_ORDER.compare(legacy[0], legacy[1])) // the comparator really ties them
            // The reference fingerprint sorts the production snapshot order itself (stable), exactly like the old exporter.
            val legacyHash = referenceFingerprint(dao.getAllSnapshot().map(::map))
            assertEquals(legacyHash, referenceFingerprint(legacy))
            assertEquals("null id=$nullId empty id=$emptyId", legacyHash, streamedFingerprint())
            assertNotEquals("reversing the pair is a different hash", legacyHash, referenceFingerprint(legacy.reversed()))
            hashes += legacyHash
        }
        assertNotEquals("the two arrangements really differ", hashes[0], hashes[1])
    }

    @Test fun `the same-timestamp edge with many mixed rows matches the real legacy path across page sizes`() = runBlocking {
        val rnd = java.util.Random(5)
        dao.insertAll((1L..400L).map { tied(it, when (rnd.nextInt(3)) { 0 -> null; 1 -> ""; else -> "e${rnd.nextInt(3)}" }).copy(listenedMs = rnd.nextInt(2).toLong()) })
        val want = referenceFingerprint(legacyEvents())
        for (page in listOf(1, 2, 7, 64, 399, 400, 401)) assertEquals("page=$page", want, streamedFingerprint(page))
    }

    @Test fun `the cursor is strictly advancing with a descending id tie-break and handles extreme ids without overflow`() = runBlocking {
        val ids = listOf(1L, 2L, 3L, Long.MAX_VALUE, Long.MAX_VALUE - 1, Long.MAX_VALUE / 2, Long.MIN_VALUE + 1, -5L)
        dao.insertAll(ids.map { tied(it, null) })
        val page = fetch(EventExportCursor.START, Long.MAX_VALUE, 10)
        assertEquals(ids.sortedDescending(), page.map { it.id })
        // One row at a time: the cursor resumes strictly after the previous row.
        var cursor = EventExportCursor.START
        val seen = mutableListOf<Long>()
        while (true) {
            val one = fetch(cursor, Long.MAX_VALUE, 1)
            if (one.isEmpty()) break
            seen += one.single().id
            val next = EventExportCursor.of(one.single())
            assertTrue(next > cursor)
            cursor = next
        }
        assertEquals(ids.sortedDescending(), seen)
    }
}
