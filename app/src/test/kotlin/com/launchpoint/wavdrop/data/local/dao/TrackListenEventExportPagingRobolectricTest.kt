package com.launchpoint.wavdrop.data.local.dao

import androidx.room.Room
import com.launchpoint.wavdrop.data.backup.BackupEventExportRules
import com.launchpoint.wavdrop.data.backup.BackupListenEvent
import com.launchpoint.wavdrop.data.backup.CanonicalEventStream
import com.launchpoint.wavdrop.data.backup.EventExportCursor
import com.launchpoint.wavdrop.data.backup.toBackupListenEvent
import com.launchpoint.wavdrop.data.local.entity.SongEntity
import com.launchpoint.wavdrop.data.backup.RecoveryTestFixtures
import com.launchpoint.wavdrop.data.backup.WavdropBackupIntegrityV2
import com.launchpoint.wavdrop.data.backup.wdbk.WdbkExportSnapshot
import com.launchpoint.wavdrop.data.backup.wdbk.WdbkReader
import com.launchpoint.wavdrop.data.backup.wdbk.WdbkTestSupport
import com.launchpoint.wavdrop.data.backup.wdbk.WdbkWriter
import com.launchpoint.wavdrop.data.local.WavdropDatabase
import com.launchpoint.wavdrop.data.local.entity.TrackListenEventEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Runs the real export-paging SQL (getMaxId / getExportPage: full canonical-order keyset) against in-memory Room/SQLite. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TrackListenEventExportPagingRobolectricTest {

    private lateinit var db: WavdropDatabase
    private lateinit var dao: TrackListenEventDao

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), WavdropDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.trackListenEventDao()
    }

    @After fun tearDown() = db.close()

    private fun ev(
        i: Int, at: Long = 1_000L + i / 4, source: String = TrackListenEventEntity.SOURCE_WAVDROP_PLAYBACK,
    ) = TrackListenEventEntity(
        songId = (i % 4).toLong(), eventType = if (i % 7 == 0) "SKIP" else "PLAY", occurredAt = at,
        listenedMs = (i % 9).toLong(), durationMs = 10L, source = source, eventId = if (i % 5 == 0) "id-$i" else null,
    )

    private fun map(e: TrackListenEventEntity) = BackupListenEvent(
        songId = e.songId, contentUri = "content://media/${e.songId}", title = "T${e.songId % 2}", artist = "A", album = "B",
        eventType = e.eventType, occurredAt = e.occurredAt, listenedMs = e.listenedMs, durationMs = e.durationMs,
        source = e.source, eventId = e.eventId,
    )

    private val fetch: suspend (EventExportCursor, Long, Int) -> List<TrackListenEventEntity> = { c, upTo, limit ->
        dao.getExportPage(c.occurredAt, c.songId, c.eventType, c.listenedMs, c.durationMs, c.source, c.eventKey, c.id, upTo, limit)
    }

    private fun stream(pageSize: Int, bound: Long?) = CanonicalEventStream(bound, pageSize, fetch, ::map)

    private fun drain(s: CanonicalEventStream, chunk: Int = 1_000): List<BackupListenEvent> = runBlocking {
        val out = mutableListOf<BackupListenEvent>()
        while (true) {
            val c = s.nextChunk(chunk)
            if (c.isEmpty()) break
            out += c
        }
        out
    }

    private fun legacyCanonical(): List<BackupListenEvent> = runBlocking {
        dao.getAllSnapshot().filter { BackupEventExportRules.shouldExport(it.source) }.map(::map)
            .sortedWith(WavdropBackupIntegrityV2.EVENT_ORDER)
    }

    @Test fun `empty table has no max id and exports nothing`() = runBlocking {
        assertNull(dao.getMaxId())
        assertTrue(drain(stream(10, dao.getMaxId())).isEmpty())
    }

    @Test fun `paged canonical export equals the full legacy snapshot sorted canonically for every page size`() = runBlocking {
        dao.insertAll((1..120).map { ev(it) } + (121..140).map { ev(it, source = TrackListenEventEntity.SOURCE_BLACKPLAYER_IMPORT) })
        val want = legacyCanonical()
        assertEquals(120, want.size)
        for (page in listOf(1, 2, 7, 10, 119, 120, 121, 140, 141, 1_000)) {
            for (chunk in listOf(1, 13, 500)) {
                assertEquals("page=$page chunk=$chunk", want, drain(stream(page, dao.getMaxId()), chunk))
            }
        }
    }

    @Test fun `the page query is a bounded full-order keyset - strictly after the cursor and at or below the boundary`() = runBlocking {
        dao.insertAll((1..25).map { ev(it, at = 5_000L) }) // every row shares occurredAt: the remaining keys order them
        val max = dao.getMaxId()!!
        val first = fetch(EventExportCursor.START, max, 10)
        val second = fetch(EventExportCursor.of(first.last()), max, 10)
        val third = fetch(EventExportCursor.of(second.last()), max, 10)
        assertEquals(listOf(10, 10, 5), listOf(first, second, third).map { it.size })
        val all = first + second + third
        assertEquals(all.map { EventExportCursor.of(it) }.sorted(), all.map { EventExportCursor.of(it) })
        assertEquals(25, all.map { it.id }.toSet().size)
        assertTrue(fetch(EventExportCursor.of(third.last()), max, 10).isEmpty())
        assertEquals(12, fetch(EventExportCursor.START, 12L, 100).size)
    }

    @Test fun `SQL order equals the canonical fingerprint order for mixed song metadata, null and empty eventIds`() = runBlocking {
        val texts = listOf("", "a", "Zed", "\u00E9", "\u4E2D", "m")
        val songs = (1L..25L).filter { it % 6 != 0L }.associateWith {
            SongEntity(it, texts[(it * 5 % 6).toInt()] + it, texts[(it * 3 % 6).toInt()], texts[(it % 6).toInt()], 1, 1, "content://" + texts[(it * 7 % 6).toInt()] + (30 - it), 1, 1, 2000)
        }
        val rnd = java.util.Random(11)
        dao.insertAll(
            (1..3_000).map {
                TrackListenEventEntity(
                    songId = 1L + rnd.nextInt(25), eventType = if (rnd.nextBoolean()) "PLAY" else "SKIP", occurredAt = 500L + rnd.nextInt(4),
                    listenedMs = rnd.nextInt(3).toLong(), durationMs = rnd.nextInt(2).toLong(),
                    source = listOf(TrackListenEventEntity.SOURCE_WAVDROP_PLAYBACK, TrackListenEventEntity.SOURCE_MANUAL_RESTORE, TrackListenEventEntity.SOURCE_DESKTOP_PLAYBACK)[rnd.nextInt(3)],
                    eventId = when (rnd.nextInt(4)) { 0 -> null; 1 -> ""; 2 -> "x${rnd.nextInt(4)}"; else -> "Y${rnd.nextInt(4)}" },
                )
            },
        )
        val mapSong = { e: TrackListenEventEntity -> e.toBackupListenEvent(songs[e.songId]) }
        val got = drain(CanonicalEventStream(dao.getMaxId(), 97, fetch, mapSong), 250)
        val legacy = dao.getAllSnapshot().filter { BackupEventExportRules.shouldExport(it.source) }.map(mapSong)
            .sortedWith(WavdropBackupIntegrityV2.EVENT_ORDER)
        assertEquals(3_000, got.size)
        assertEquals(legacy, got)
    }

    @Test fun `many rows sharing one occurredAt page through real SQL with bounded state`() = runBlocking {
        dao.insertAll(
            (1..12_000).map {
                TrackListenEventEntity(
                    songId = (it * 31L) % 97, eventType = if (it % 5 == 0) "SKIP" else "PLAY", occurredAt = 9_000L,
                    listenedMs = (it % 13).toLong(), durationMs = 10L, source = TrackListenEventEntity.SOURCE_WAVDROP_PLAYBACK,
                    eventId = if (it % 2 == 0) "id-$it" else null,
                )
            },
        )
        val s = stream(500, dao.getMaxId())
        val got = drain(s, 300)
        assertEquals(12_000, got.size)
        assertEquals(legacyCanonical(), got)
        assertTrue("pages crossed", s.pagesFetched >= 24)
        assertTrue("buffered ${s.peakBuffered}", s.peakBuffered <= 500 + 300)
    }

    @Test fun `unusual but valid eventIds that tie on every earlier key are either exact or rejected loudly never reordered silently`() = runBlocking {
        val emoji = String(Character.toChars(0x1F600))
        dao.insertAll(listOf(ev(1, at = 1L).copy(songId = 1, eventId = emoji), ev(1, at = 1L).copy(songId = 1, eventId = "\uFFFD")))
        try {
            val got = drain(stream(10, dao.getMaxId()))
            // If SQLite and the fingerprint ever agreed, the result must be exactly canonical.
            assertEquals(legacyCanonical(), got)
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("canonical fingerprint order"))
        }
    }

    @Test fun `the page query seeks the occurredAt index and uses a bounded partial sort, not a whole-table scan`() = runBlocking {
        dao.insertAll((1..50).map { ev(it) })
        val sql = "EXPLAIN QUERY PLAN SELECT * FROM track_listen_events WHERE +id <= 50 AND occurredAt >= 0 AND " +
            "(occurredAt, songId, eventType, listenedMs, durationMs, source, COALESCE(eventId, ''), -id) > (0, 0, '', 0, 0, '', '', -9) " +
            "ORDER BY occurredAt ASC, songId ASC, eventType ASC, listenedMs ASC, durationMs ASC, source ASC, COALESCE(eventId, '') ASC, id DESC LIMIT 10"
        val plan = mutableListOf<String>()
        db.openHelper.readableDatabase.query(sql).use { c -> while (c.moveToNext()) plan += c.getString(c.columnCount - 1) }
        println("EXPORT PAGE PLAN (mixed-direction tie-break): $plan")
        assertEquals("exactly one scan step plus the partial-sort step: $plan", 2, plan.size)
        assertTrue(plan.toString(), plan[0].startsWith("SEARCH") && "index_track_listen_events_occurredAt" in plan[0] && "occurredAt>?" in plan[0])
        // ORDER BY occurredAt is served by the index; only the remaining keys are sorted, inside SQLite, per page (LIMIT keeps it top-N).
        assertEquals("USE TEMP B-TREE FOR RIGHT PART OF ORDER BY", plan[1])
    }

    @Test fun `end to end - real SQL export through the WDBK writer matches the legacy full-list fingerprint`() = runBlocking {
        // Same-timestamp runs, null/non-null eventIds, duplicates and an excluded source, across several pages and chunks.
        dao.insertAll(
            (1..300).map { ev(it) } +
                List(40) { ev(1, at = 777L) } + // exact duplicate canonical records
                (1..30).map { ev(it, source = TrackListenEventEntity.SOURCE_BLACKPLAYER_IMPORT) },
        )
        val boundary = dao.getMaxId()
        val small = WdbkTestSupport.fullBackup(eventCount = 0, overlay = false)
        val snapshot = WdbkExportSnapshot(
            backupId = small.backupId!!, sourceInstallationId = small.sourceInstallationId!!, exportedAtMs = small.exportedAtMs!!,
            appVersionCode = null, appVersionName = null, songs = small.songs, trackStats = small.trackStats,
            importBaselines = small.importBaselines, lyricsOverrides = small.lyricsOverrides, preferences = small.preferences,
            playlists = small.playlists, desktopOverlayRawJson = null,
            openEvents = { CanonicalEventStream(boundary, 25, fetch, ::map) },
        )
        val out = ByteArrayOutputStream()
        val receipt = WdbkWriter(eventsPerChunk = 60).write(snapshot, out)

        val legacyEvents = dao.getAllSnapshot().filter { BackupEventExportRules.shouldExport(it.source) }.map(::map)
        assertEquals(340, legacyEvents.size)
        val legacyFingerprint = WavdropBackupIntegrityV2.fingerprint(small.copy(listenEvents = legacyEvents))
        assertEquals(legacyFingerprint, receipt.fingerprint)
        assertEquals(340, receipt.manifest.counts.listenEventCount)

        val read = WdbkReader().read(ByteArrayInputStream(out.toByteArray()), RecoveryTestFixtures.NOW + 1_000_000L)
        assertNull(read.error)
        assertEquals(legacyFingerprint, WavdropBackupIntegrityV2.fingerprint(read.backup!!))
        assertEquals(legacyEvents.toSet(), read.backup!!.listenEvents.toSet())
    }
}
