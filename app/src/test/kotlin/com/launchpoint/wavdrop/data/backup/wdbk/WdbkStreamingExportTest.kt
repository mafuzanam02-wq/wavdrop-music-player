package com.launchpoint.wavdrop.data.backup.wdbk

import com.launchpoint.wavdrop.data.backup.BackupListenEvent
import com.launchpoint.wavdrop.data.backup.CanonicalEventStream
import com.launchpoint.wavdrop.data.backup.CanonicalEventStreamTest
import com.launchpoint.wavdrop.data.backup.RecoveryTestFixtures
import com.launchpoint.wavdrop.data.backup.WavdropBackup
import com.launchpoint.wavdrop.data.backup.WavdropBackupIntegrityV2
import com.launchpoint.wavdrop.data.local.entity.TrackListenEventEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference

/**
 * Behavioural proof that WDBK export consumes the history incrementally, and that the streamed export's semantic
 * fingerprint is byte-for-byte the legacy full-list fingerprint.
 */
class WdbkStreamingExportTest {

    private val chunk = 100

    private fun smallSections(events: List<BackupListenEvent> = emptyList()): WavdropBackup =
        WdbkTestSupport.fullBackup(eventCount = 0, overlay = true).copy(listenEvents = events)

    /** A snapshot whose events come ONLY from [openEvents] - the WavdropBackup it is derived from carries none. */
    private fun snapshotWith(openEvents: () -> WdbkEventSource): WdbkExportSnapshot {
        val b = smallSections()
        return WdbkExportSnapshot(
            backupId = b.backupId!!, sourceInstallationId = b.sourceInstallationId!!, exportedAtMs = b.exportedAtMs!!,
            appVersionCode = null, appVersionName = null, songs = b.songs, trackStats = b.trackStats,
            importBaselines = b.importBaselines, lyricsOverrides = b.lyricsOverrides, preferences = b.preferences,
            playlists = b.playlists, desktopOverlayRawJson = b.desktopOverlay?.rawJson, openEvents = openEvents,
        )
    }

    private fun export(snapshot: WdbkExportSnapshot, perChunk: Int = chunk): Pair<ByteArray, WdbkWriteReceipt> {
        val out = ByteArrayOutputStream()
        val receipt = runBlocking { WdbkWriter(perChunk).write(snapshot, out) }
        return out.toByteArray() to receipt
    }

    /** Streams [events] (already canonical) lazily from a list, as a source. */
    private fun sourceOf(events: List<BackupListenEvent>) = {
        var next = 0
        WdbkEventSource { max -> events.subList(next, minOf(next + max, events.size)).toList().also { next += it.size } }
    }

    private fun assertStreamedFingerprintEqualsLegacy(label: String, events: List<BackupListenEvent>, perChunk: Int = chunk) {
        val canonical = events.sortedWith(WavdropBackupIntegrityV2.EVENT_ORDER)
        val (bytes, receipt) = export(snapshotWith(sourceOf(canonical)), perChunk)
        val legacy = WavdropBackupIntegrityV2.fingerprint(smallSections(events))
        assertEquals("$label: manifest fingerprint", legacy, receipt.fingerprint)
        assertEquals("$label: manifest count", events.size, receipt.manifest.counts.listenEventCount)
        val read = WdbkTestSupport.read(bytes)
        assertNull("$label: ${read.error}", read.error)
        assertEquals("$label: decoded events", events.size, read.backup!!.listenEvents.size)
        assertEquals("$label: reader fingerprint", legacy, WavdropBackupIntegrityV2.fingerprint(read.backup!!))
        assertEquals("$label: each event exactly once", canonical, read.backup!!.listenEvents.sortedWith(WavdropBackupIntegrityV2.EVENT_ORDER))
    }

    private fun ev(
        at: Long, songId: Long = 1L, eventId: String? = null, listened: Long = 60_000L, type: String = "PLAY",
    ) = RecoveryTestFixtures.backupEvent(songId, eventId, at).copy(listenedMs = listened, eventType = type)

    // ── Fingerprint equivalence: legacy full-list vs streamed ─────────────────

    @Test fun `streamed fingerprint equals the legacy full-list fingerprint for the required shapes`() {
        assertStreamedFingerprintEqualsLegacy("0 events", emptyList())
        assertStreamedFingerprintEqualsLegacy("1 event", listOf(ev(5L, eventId = "a")))
        assertStreamedFingerprintEqualsLegacy("identical occurredAt", (0 until 250).map { ev(7L, songId = (it % 5).toLong(), listened = it.toLong()) })
        assertStreamedFingerprintEqualsLegacy("identical songId", (0 until 250).map { ev(1_000L - it, songId = 9L, eventId = "e$it") })
        assertStreamedFingerprintEqualsLegacy("null and non-null eventId", (0 until 250).map { ev((it / 2).toLong(), eventId = if (it % 2 == 0) null else "id-$it") })
        assertStreamedFingerprintEqualsLegacy("exact duplicate records", List(250) { ev(42L, eventId = null) } + List(30) { ev(42L, eventId = "dup") })
        assertStreamedFingerprintEqualsLegacy("multiple pages (chunk 7)", (0 until 250).map { ev(1_000L - it % 41, songId = (it % 6).toLong(), eventId = "m$it") }, perChunk = 7)
        assertStreamedFingerprintEqualsLegacy(
            "unicode and mixed types",
            (0 until 120).map { ev(it.toLong() / 3, songId = (it % 3).toLong(), type = if (it % 5 == 0) "SKIP" else "PLAY", eventId = "é中-$it") },
        )
    }

    @Test fun `60k plus events stream exactly - counts fingerprint and every event once`() {
        val n = 62_345
        val events = List(n) { i -> ev(10_000_000L + (i * 7919L) % 50_021L, songId = (i % 97).toLong(), eventId = if (i % 4 == 0) null else "k-$i", listened = (i % 13).toLong()) }
        assertStreamedFingerprintEqualsLegacy("60k", events, perChunk = WdbkLimits.EVENTS_PER_CHUNK)
    }

    // ── Behavioural non-materialization ───────────────────────────────────────

    /** Generates canonical events on demand from a counter - there is NO backing list of the history anywhere. */
    private class GeneratedSource(private val total: Int) : WdbkEventSource {
        var requests = 0
        var maxRequested = 0
        var delivered = 0
        val handed = mutableListOf<WeakReference<List<BackupListenEvent>>>()
        val outlived = mutableListOf<Int>()
        val chunkOutlivedTwoCalls get() = outlived.size
        var afterEndCalls = 0

        override suspend fun nextChunk(maxEvents: Int): List<BackupListenEvent> {
            requests++
            maxRequested = maxOf(maxRequested, maxEvents)
            if (delivered >= total) { afterEndCalls++; return emptyList() }
            // The chunk handed out two calls ago must already be unreachable: the writer may not retain old chunks.
            if (requests >= 3) {
                val old = handed[requests - 3]
                var tries = 0
                while (old.get() != null && tries++ < 20) { System.gc(); Thread.sleep(5) }
                if (old.get() != null) outlived += requests
            }
            val n = minOf(maxEvents, total - delivered)
            val chunk = List(n) { k ->
                val i = delivered + k
                RecoveryTestFixtures.backupEvent(1L + i % 5, "gen-$i", 5_000_000L + i) // strictly increasing => canonical order
            }
            delivered += n
            handed += WeakReference(chunk)
            return chunk
        }
    }

    @Test fun `the writer consumes a large history one released chunk at a time`() {
        val total = 64_000
        val source = GeneratedSource(total)
        val (bytes, receipt) = export(snapshotWith { source }, perChunk = WdbkLimits.EVENTS_PER_CHUNK)

        // Many bounded requests, never more than one chunk asked for, and the source was not asked again after the end.
        assertEquals(total / WdbkLimits.EVENTS_PER_CHUNK + 1, source.requests) // 32 data chunks + 1 empty (end)
        assertEquals(WdbkLimits.EVENTS_PER_CHUNK, source.maxRequested)
        assertEquals("exactly one terminating (empty) request, none after it", 1, source.afterEndCalls)
        // Chunks were released as the export progressed (nothing kept the history alive).
        assertEquals("chunks retained for more than two calls, at requests " + source.outlived, 0, source.chunkOutlivedTwoCalls)

        assertEquals(total, receipt.manifest.counts.listenEventCount)
        val chunks = receipt.manifest.entries.filter { it.section == "listenEvents" }
        assertEquals(32, chunks.size)
        assertEquals(total, chunks.sumOf { it.eventCount ?: 0 })

        // And it still decodes to the exact history with the fingerprint legacy full-list semantics give.
        val read = WdbkTestSupport.read(bytes)
        assertNull(read.error)
        assertEquals(total, read.backup!!.listenEvents.size)
        val regenerated = List(total) { i -> RecoveryTestFixtures.backupEvent(1L + i % 5, "gen-$i", 5_000_000L + i) }
        assertEquals(WavdropBackupIntegrityV2.fingerprint(smallSections(regenerated)), receipt.fingerprint)
    }

    @Test fun `the writer needs no complete event list - the snapshot type has none and the model it wraps has no events`() {
        val props = WdbkExportSnapshot::class.java.declaredFields.map { it.type.name + " " + it.name }
        assertTrue(props.toString(), props.none { "listenEvents" in it })
        // A snapshot built from a backup whose list is empty still exports a full history from the source alone.
        val events = (0 until 55).map { ev(it.toLong(), eventId = "s$it") }
        val (bytes, _) = export(snapshotWith(sourceOf(events)), perChunk = 10)
        assertEquals(55, WdbkTestSupport.read(bytes).backup!!.listenEvents.size)
    }

    @Test fun `an out-of-order source is refused rather than producing a wrong fingerprint`() {
        val shuffled = listOf(ev(5L), ev(3L))
        try {
            export(snapshotWith(sourceOf(shuffled)))
            org.junit.Assert.fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("canonical"))
        }
    }

    @Test fun `a source that returns an oversized chunk is refused`() {
        val src = { WdbkEventSource { List(11) { i -> ev(i.toLong()) } } }
        try {
            export(snapshotWith(src), perChunk = 10)
            org.junit.Assert.fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("more than the requested"))
        }
    }

    // ── Production source end to end over a fake paged table ──────────────────

    @Test fun `the production paged source exports 60k events through many bounded pages in full database order`() {
        val n = 61_000
        val rows = List(n) { i ->
            TrackListenEventEntity(
                id = i + 1L, songId = (i % 9).toLong(), eventType = "PLAY", occurredAt = 1_000_000L + (i * 31L) % 40_000L,
                listenedMs = (i % 11).toLong(), durationMs = 500L,
                source = if (i % 10 == 0) TrackListenEventEntity.SOURCE_BLACKPLAYER_IMPORT else TrackListenEventEntity.SOURCE_WAVDROP_PLAYBACK,
                eventId = if (i % 3 == 0) "r$i" else null,
            )
        }
        val table = CanonicalEventStreamTest.FakeTable(rows)
        fun map(e: TrackListenEventEntity) = BackupListenEvent(
            songId = e.songId, contentUri = "content://media/${e.songId}", title = "T${e.songId}", artist = "A", album = "B",
            eventType = e.eventType, occurredAt = e.occurredAt, listenedMs = e.listenedMs, durationMs = e.durationMs,
            source = e.source, eventId = e.eventId,
        )
        var streams = 0
        val (bytes, receipt) = export(
            snapshotWith {
                streams++
                CanonicalEventStream(n.toLong(), WdbkLimits.EVENT_EXPORT_PAGE_SIZE, table::page, ::map)
            },
            perChunk = WdbkLimits.EVENTS_PER_CHUNK,
        )
        val exportable = rows.filter { it.source != TrackListenEventEntity.SOURCE_BLACKPLAYER_IMPORT }
        assertEquals(exportable.size, receipt.manifest.counts.listenEventCount)
        assertTrue("many bounded pages, not one", table.calls.size > 25)
        assertTrue(table.calls.all { it.limit == WdbkLimits.EVENT_EXPORT_PAGE_SIZE })
        assertEquals(1, streams)

        val legacy = WavdropBackupIntegrityV2.fingerprint(smallSections(exportable.map(::map)))
        assertEquals(legacy, receipt.fingerprint)
        val read = WdbkTestSupport.read(bytes)
        assertNull(read.error)
        assertEquals(exportable.size, read.backup!!.listenEvents.size)
        assertEquals(legacy, WavdropBackupIntegrityV2.fingerprint(read.backup!!))
    }

    // ── Same-timestamp stress: the former unbounded equal-occurredAt run ──────

    /** Wraps a source and observes what the writer asks for and what it is handed; also proves old chunks are released. */
    private class ObservedSource(private val inner: WdbkEventSource) : WdbkEventSource {
        var requests = 0
        var maxRequested = 0
        var maxReturned = 0
        val handed = mutableListOf<WeakReference<List<BackupListenEvent>>>()
        var outlived = 0

        override suspend fun nextChunk(maxEvents: Int): List<BackupListenEvent> {
            requests++
            maxRequested = maxOf(maxRequested, maxEvents)
            if (requests >= 3) {
                val old = handed[requests - 3]
                var tries = 0
                while (old.get() != null && tries++ < 20) { System.gc(); Thread.sleep(5) }
                if (old.get() != null) outlived++
            }
            val chunk = inner.nextChunk(maxEvents)
            maxReturned = maxOf(maxReturned, chunk.size)
            handed += WeakReference(chunk)
            return chunk
        }
    }

    @Test fun `55k events sharing exactly one occurredAt export with bounded state and the legacy fingerprint`() {
        val n = 55_000
        val sharedAt = 1_700_000_000_000L
        val rows = List(n) { i ->
            TrackListenEventEntity(
                id = i + 1L, songId = ((i * 37L) % 211L), eventType = if (i % 9 == 0) "SKIP" else "PLAY", occurredAt = sharedAt,
                listenedMs = (i % 17).toLong(), durationMs = (i % 3).toLong() * 1_000L,
                source = if (i % 8 == 0) TrackListenEventEntity.SOURCE_BLACKPLAYER_IMPORT else TrackListenEventEntity.SOURCE_WAVDROP_PLAYBACK,
                eventId = when (i % 3) { 0 -> null; 1 -> "u-${(i * 7919L) % 100_003L}-$i"; else -> "w-$i" },
            )
        }
        // Song-derived text deliberately anti-correlated with songId.
        fun map(e: TrackListenEventEntity) = BackupListenEvent(
            songId = e.songId, contentUri = "content://media/${1_000 - e.songId}", title = "T${(e.songId * 31) % 17}", artist = "A${e.songId % 5}",
            album = "B", eventType = e.eventType, occurredAt = e.occurredAt, listenedMs = e.listenedMs, durationMs = e.durationMs,
            source = e.source, eventId = e.eventId,
        )
        val table = CanonicalEventStreamTest.FakeTable(rows)
        lateinit var production: CanonicalEventStream
        lateinit var observed: ObservedSource
        val (bytes, receipt) = export(
            snapshotWith {
                production = CanonicalEventStream(n.toLong(), WdbkLimits.EVENT_EXPORT_PAGE_SIZE, table::page, ::map)
                observed = ObservedSource(production)
                observed
            },
            perChunk = WdbkLimits.EVENTS_PER_CHUNK,
        )
        val exportable = rows.filter { it.source != TrackListenEventEntity.SOURCE_BLACKPLAYER_IMPORT }
        assertEquals(exportable.size, receipt.manifest.counts.listenEventCount)

        // Many pages and many chunks were crossed...
        assertTrue("pages ${table.calls.size}", table.calls.size > 25)
        assertTrue("chunk requests ${observed.requests}", observed.requests > 20)
        // ...but nothing history-shaped grew: requests, pages and the buffer of the source stay at the configured sizes.
        assertEquals(WdbkLimits.EVENTS_PER_CHUNK, observed.maxRequested)
        assertTrue(observed.maxReturned <= WdbkLimits.EVENTS_PER_CHUNK)
        assertTrue(table.calls.all { it.limit == WdbkLimits.EVENT_EXPORT_PAGE_SIZE })
        assertTrue(
            "buffered ${production.peakBuffered} must stay within page + chunk, nowhere near the ${exportable.size}-row timestamp group",
            production.peakBuffered < WdbkLimits.EVENT_EXPORT_PAGE_SIZE + WdbkLimits.EVENTS_PER_CHUNK,
        )
        assertEquals("delivered chunks are released", 0, observed.outlived)
        val cursors = table.calls.map { it.after }
        assertEquals("keyset strictly advances", cursors, cursors.sorted())
        assertEquals(cursors.size, cursors.toSet().size)

        // Exactly once, round trip, and the old full-list reference fingerprint.
        val legacy = WavdropBackupIntegrityV2.fingerprint(smallSections(exportable.map(::map)))
        assertEquals(legacy, receipt.fingerprint)
        val read = WdbkTestSupport.read(bytes)
        assertNull(read.error)
        assertEquals(exportable.size, read.backup!!.listenEvents.size)
        assertEquals(legacy, WavdropBackupIntegrityV2.fingerprint(read.backup!!))
        assertEquals(
            exportable.map(::map).sortedWith(WavdropBackupIntegrityV2.EVENT_ORDER), // stable over id order, like legacy
            read.backup!!.listenEvents,
        )
    }
}
