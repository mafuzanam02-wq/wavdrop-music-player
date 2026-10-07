package com.launchpoint.wavdrop.data.backup

import com.launchpoint.wavdrop.data.local.entity.TrackListenEventEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The streaming event source: bounded full-order keyset pages already in canonical order; no run buffering or sorting. */
class CanonicalEventStreamTest {

    private fun row(
        id: Long, at: Long, songId: Long = id % 4, type: String = "PLAY", listened: Long = 100L - id % 7,
        source: String = TrackListenEventEntity.SOURCE_WAVDROP_PLAYBACK, eventId: String? = if (id % 3 == 0L) "e$id" else null,
    ) = TrackListenEventEntity(
        id = id, songId = songId, eventType = type, occurredAt = at, listenedMs = listened, durationMs = 500L,
        source = source, eventId = eventId,
    )

    internal class Call(val after: EventExportCursor, val upToId: Long, val limit: Int)

    /**
     * Fake table: rows sorted by the full database order; the page query is a binary-searched keyset, exactly like the SQL.
     * It also records `largestRequestedLimit`, the only history-shaped quantity a caller can make the table return.
     */
    internal class FakeTable(rows: List<TrackListenEventEntity>) {
        private val sorted = rows.sortedBy { EventExportCursor.of(it) }
        val calls = mutableListOf<Call>()

        suspend fun page(after: EventExportCursor, upToId: Long, limit: Int): List<TrackListenEventEntity> {
            calls += Call(after, upToId, limit)
            var lo = 0
            var hi = sorted.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (EventExportCursor.of(sorted[mid]) > after) hi = mid else lo = mid + 1
            }
            val out = ArrayList<TrackListenEventEntity>()
            var i = lo
            while (i < sorted.size && out.size < limit) {
                // The bound filter is applied like the SQL does (rows above it are skipped, not paged).
                if (sorted[i].id <= upToId) out += sorted[i]
                i++
            }
            return out
        }
    }

    // Song-derived metadata makes the full canonical order depend on text, not only on numbers; it is a pure function of songId.
    private fun toBackup(e: TrackListenEventEntity) = BackupListenEvent(
        songId = e.songId, contentUri = "content://media/${e.songId}", title = "Title ${(e.songId * 7) % 3}",
        artist = "Artist ${e.songId % 2}", album = "Album", eventType = e.eventType, occurredAt = e.occurredAt,
        listenedMs = e.listenedMs, durationMs = e.durationMs, source = e.source, eventId = e.eventId,
    )

    private fun stream(table: FakeTable, pageSize: Int, upToId: Long?) =
        CanonicalEventStream(upToId, pageSize, table::page, ::toBackup)

    private fun drain(s: CanonicalEventStream, chunk: Int): List<List<BackupListenEvent>> = runBlocking {
        val chunks = mutableListOf<List<BackupListenEvent>>()
        while (true) {
            val c = s.nextChunk(chunk)
            if (c.isEmpty()) break
            chunks += c
        }
        chunks
    }

    /** What the legacy path produced: the getAllSnapshot() order (occurredAt DESC, ties by descending id), filtered, mapped, stably sorted by the fingerprint comparator. */
    private fun expected(rows: List<TrackListenEventEntity>, upTo: Long) = LegacyExportOrder.snapshot(rows)
        .filter { it.id <= upTo && BackupEventExportRules.shouldExport(it.source) }
        .map(::toBackup)
        .sortedWith(WavdropBackupIntegrityV2.EVENT_ORDER)

    @Test fun `output equals the fully sorted canonical history for every page and chunk size`() {
        // Many rows share a timestamp (runs span page boundaries) and are inserted in adversarial order.
        val rows = (1L..400L).map { id -> row(id, at = 1_000L + (400L - id) / 9, songId = (id * 13) % 11, listened = (id * 31) % 17) }
        val want = expected(rows, 400L)
        for (page in listOf(1, 2, 7, 9, 10, 50, 399, 400, 401, 1000)) {
            for (chunk in listOf(1, 5, 64, 400, 1000)) {
                val got = drain(stream(FakeTable(rows), page, 400L), chunk).flatten()
                assertEquals("page=$page chunk=$chunk", want, got)
            }
        }
    }

    @Test fun `a single shared timestamp is ordered by the remaining keys with no run buffering`() {
        val rows = (1L..500L).map { id ->
            row(id, at = 42L, songId = (id * 17) % 13, type = if (id % 5 == 0L) "SKIP" else "PLAY", listened = (id * 7) % 23, eventId = if (id % 2 == 0L) "z${500 - id}" else null)
        }
        for (page in listOf(1, 3, 64, 499, 500, 600)) {
            assertEquals("page=$page", expected(rows, 500L), drain(stream(FakeTable(rows), page, 500L), 77).flatten())
        }
    }

    @Test fun `identical canonical records and ties on every field keep a stable multiset`() {
        val rows = (1L..30L).map { id -> row(id, at = 5L, songId = 1L, listened = 10L, eventId = null) } // exact duplicates
        val got = drain(stream(FakeTable(rows), 4, 30L), 8).flatten()
        assertEquals(30, got.size)
        assertEquals(expected(rows, 30L), got)
    }

    @Test fun `chunks are exactly the requested size except the last and the end is an empty chunk`() {
        val rows = (1L..23L).map { row(it, it) }
        val chunks = drain(stream(FakeTable(rows), 5, 23L), 10)
        assertEquals(listOf(10, 10, 3), chunks.map { it.size })
        runBlocking { assertTrue(stream(FakeTable(emptyList()), 5, null).nextChunk(10).isEmpty()) }
    }

    @Test fun `pages are bounded and keyset-driven never offset-driven`() {
        val rows = (1L..95L).map { row(it, it * 10, songId = 1L, listened = 5L, eventId = null) }
        val table = FakeTable(rows)
        val s = stream(table, 20, 95L)
        drain(s, 1000)
        assertTrue(table.calls.all { it.limit == 20 })
        assertEquals(EventExportCursor.START, table.calls.first().after)
        // Each resume point is the LAST ROW of the previous page: occurredAt 200, 400, 600, 800.
        assertEquals(listOf(200L, 400L, 600L, 800L), table.calls.drop(1).take(4).map { it.after.occurredAt })
        assertEquals(listOf(20L, 40L, 60L, 80L), table.calls.drop(1).take(4).map { it.after.id })
        assertTrue("one request per page, no extra", table.calls.size <= 6)
        assertEquals(table.calls.size, s.pagesFetched)
        val cursors = table.calls.map { it.after }
        assertEquals("cursors strictly increase", cursors, cursors.sorted())
        assertEquals(cursors.size, cursors.toSet().size)
    }

    @Test fun `the cursor carries every ordering component of the database order`() {
        val rows = listOf(row(7, at = 9, songId = 3, type = "SKIP", listened = 11, source = TrackListenEventEntity.SOURCE_MANUAL_RESTORE, eventId = "abc"), row(8, at = 10))
        val table = FakeTable(rows)
        drain(stream(table, 1, 8L), 10)
        val resumed = table.calls[1].after
        assertEquals(EventExportCursor(9, 3, "SKIP", 11, 500, TrackListenEventEntity.SOURCE_MANUAL_RESTORE, "abc", 7), resumed)
        assertEquals("null eventId keys as empty", "", EventExportCursor.of(row(1, 1, eventId = null)).eventKey)
    }

    @Test fun `an empty snapshot makes no query`() {
        val table = FakeTable(emptyList())
        assertTrue(drain(stream(table, 10, null), 10).isEmpty())
        assertEquals(0, table.calls.size)
    }

    @Test fun `rows above the captured boundary never appear`() {
        val rows = (1L..60L).map { row(it, at = 1_000L - it) } // later ids have EARLIER timestamps
        val got = drain(stream(FakeTable(rows), 7, 40L), 9).flatten()
        assertEquals(40, got.size)
        assertEquals(expected(rows, 40L), got)
        assertTrue(FakeTable(rows).let { t -> drain(stream(t, 7, 40L), 9); t.calls.all { it.upToId == 40L } })
    }

    @Test fun `excluded sources are skipped in output but still advance the cursor - no loop no empty chunks`() {
        val rows = (1L..90L).map { id ->
            row(id, at = id, source = if (id in 21L..60L) TrackListenEventEntity.SOURCE_BLACKPLAYER_IMPORT else TrackListenEventEntity.SOURCE_WAVDROP_PLAYBACK)
        }
        val table = FakeTable(rows)
        val chunks = drain(stream(table, 10, 90L), 7)
        val got = chunks.flatten()
        assertEquals(50, got.size)
        assertTrue(got.none { it.source == TrackListenEventEntity.SOURCE_BLACKPLAYER_IMPORT })
        assertEquals(expected(rows, 90L), got)
        assertTrue("no empty history chunk", chunks.none { it.isEmpty() })
        assertEquals(listOf(7, 7, 7, 7, 7, 7, 7, 1), chunks.map { it.size })
        assertTrue("paging terminated in a bounded number of requests", table.calls.size <= 10)
    }

    @Test fun `a history that is entirely excluded produces no events and terminates`() {
        val rows = (1L..25L).map { row(it, it, source = TrackListenEventEntity.SOURCE_BLACKPLAYER_IMPORT) }
        val table = FakeTable(rows)
        assertTrue(drain(stream(table, 5, 25L), 10).isEmpty())
        assertTrue(table.calls.size <= 6)
    }

    @Test fun `a source that does not advance is rejected instead of looping`() {
        val stuck = CanonicalEventStream(100L, 2, { _, _, _ -> listOf(row(1, 1), row(2, 1)) }, ::toBackup)
        try {
            runBlocking { while (stuck.nextChunk(50).isNotEmpty()) { /* drain */ } }
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("did not advance"))
        }
    }

    @Test fun `buffered state is bounded by page plus chunk even when every row shares one timestamp`() {
        val n = 20_000
        val rows = (1L..n.toLong()).map { id -> row(id, at = 777L, songId = id % 50, listened = id % 31, eventId = "e$id") }
        val table = FakeTable(rows)
        val s = stream(table, 500, n.toLong())
        var delivered = 0
        runBlocking {
            while (true) {
                val c = s.nextChunk(300)
                if (c.isEmpty()) break
                delivered += c.size
            }
        }
        assertEquals(n, delivered)
        assertTrue("peak buffered ${s.peakBuffered}", s.peakBuffered <= 500 + 300)
        assertTrue(table.calls.all { it.limit == 500 })
        assertEquals(41, table.calls.size) // 40 full pages plus one empty probe that proves the end
    }

    @Test fun `an order disagreement between the database text order and the fingerprint text order fails loudly`() {
        // U+1F600 (surrogate pair, UTF-16 starts D83D) vs U+FFFD: code-point/UTF-8 order puts U+FFFD first, but the
        // fingerprint's UTF-16 String order puts the emoji first. Rows tied on every earlier key expose it.
        val emoji = String(Character.toChars(0x1F600))
        val rows = listOf(
            row(1, 10, songId = 1, listened = 5, eventId = emoji),
            row(2, 10, songId = 1, listened = 5, eventId = "�"),
        )
        try {
            drain(stream(FakeTable(rows), 10, 2L), 10)
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message, e.message!!.contains("canonical fingerprint order"))
        }
    }

    @Test fun `events are mapped faithfully - null eventId stays null`() {
        val rows = listOf(row(1, 10, eventId = null), row(2, 20, eventId = "x"))
        assertEquals(listOf(null, "x"), drain(stream(FakeTable(rows), 10, 2L), 10).flatten().map { it.eventId })
    }
}
