package com.launchpoint.wavdrop.data.backup

import com.launchpoint.wavdrop.data.backup.wdbk.WdbkEventSource
import com.launchpoint.wavdrop.data.local.entity.TrackListenEventEntity

/**
 * Resume point of the export keyset: every component of the database order, in order. `eventKey` is
 * `COALESCE(eventId, '')`. Strings compare by code point, which is what SQLite's BINARY collation does on UTF-8. The
 * final component, `id`, is ordered DESCENDING (a larger id sorts earlier).
 */
internal data class EventExportCursor(
    val occurredAt: Long,
    val songId: Long,
    val eventType: String,
    val listenedMs: Long,
    val durationMs: Long,
    val source: String,
    val eventKey: String,
    val id: Long,
) : Comparable<EventExportCursor> {

    override fun compareTo(other: EventExportCursor): Int {
        occurredAt.compareTo(other.occurredAt).let { if (it != 0) return it }
        songId.compareTo(other.songId).let { if (it != 0) return it }
        compareCodePoints(eventType, other.eventType).let { if (it != 0) return it }
        listenedMs.compareTo(other.listenedMs).let { if (it != 0) return it }
        durationMs.compareTo(other.durationMs).let { if (it != 0) return it }
        compareCodePoints(source, other.source).let { if (it != 0) return it }
        compareCodePoints(eventKey, other.eventKey).let { if (it != 0) return it }
        return other.id.compareTo(id) // final physical key runs DESCENDING (see TrackListenEventDao.getExportPage)
    }

    companion object {
        /** Sorts before every real row: ids run descending, so its id component is above every real id. */
        val START = EventExportCursor(Long.MIN_VALUE, Long.MIN_VALUE, "", Long.MIN_VALUE, Long.MIN_VALUE, "", "", Long.MAX_VALUE)

        fun of(e: TrackListenEventEntity) =
            EventExportCursor(e.occurredAt, e.songId, e.eventType, e.listenedMs, e.durationMs, e.source, e.eventId ?: "", e.id)

        private fun compareCodePoints(a: String, b: String): Int {
            var i = 0
            var j = 0
            while (i < a.length && j < b.length) {
                val ca = a.codePointAt(i)
                val cb = b.codePointAt(j)
                if (ca != cb) return ca.compareTo(cb)
                i += Character.charCount(ca)
                j += Character.charCount(cb)
            }
            return (a.length - i).compareTo(b.length - j)
        }
    }
}

/**
 * Streams the exportable listen events of a captured export snapshot in the CANONICAL fingerprint order
 * ([WavdropBackupIntegrityV2.EVENT_ORDER]) using bounded keyset pages. Nothing history-shaped is ever collected.
 *
 * Ordering. The database itself returns rows in the canonical order, so no sorting or run buffering happens here. The
 * canonical order is occurredAt, songId, contentUri, eventType, listenedMs, durationMs, source, title, artist, album,
 * eventId. Export maps every row through ONE captured song map, so contentUri/title/artist/album are the same for equal
 * songId (and a missing song yields the same empty strings every time), and for different songIds the songId comparison
 * has already decided. Those four fields therefore never change the order, and the database order is
 * (occurredAt, songId, eventType, listenedMs, durationMs, source, COALESCE(eventId, ''), id DESC). `id` is only the final
 * physical paging tie-breaker; it is not part of the event, the fingerprint or the payload. Rows that tie on every
 * canonical field are emitted in DESCENDING id order, which is exactly what the legacy path produced: it stable-sorted
 * getAllSnapshot() (ORDER BY occurredAt DESC, served by a backwards scan of the occurredAt index, i.e. descending id for
 * equal occurredAt). This matters only where the comparator ties but the canonical records differ (null vs empty eventId).
 *
 * Snapshot boundary. [upToId] is MAX(id) captured once by the caller; rows above it never appear, so the walk terminates
 * while playback keeps inserting, and the fingerprint/counts describe exactly the captured snapshot.
 *
 * Filtering. [BackupEventExportRules.shouldExport] stays authoritative: excluded rows are skipped in the OUTPUT but still
 * advance the cursor, so a page of only excluded rows cannot stall or loop.
 *
 * Retained state: the cursor, the not-yet-delivered events of the current page(s) (at most one page plus one requested
 * chunk) and the previously delivered event used to verify the order. Every delivered event is checked against the
 * canonical comparator; if SQLite's BINARY text order and the fingerprint's UTF-16 string order ever disagree (only
 * possible for two rows tied on every earlier key whose eventIds differ at a supplementary versus U+E000..U+FFFF
 * character) the export fails loudly instead of writing a wrong fingerprint.
 */
internal class CanonicalEventStream(
    private val upToId: Long?,
    private val pageSize: Int,
    private val fetchPage: suspend (after: EventExportCursor, upToId: Long, limit: Int) -> List<TrackListenEventEntity>,
    private val map: (TrackListenEventEntity) -> BackupListenEvent,
) : WdbkEventSource {

    private var cursor = EventExportCursor.START
    private var exhausted = upToId == null
    private val ready = ArrayDeque<BackupListenEvent>()
    private var previous: BackupListenEvent? = null

    /** Number of database pages requested so far (observable for tests). */
    var pagesFetched = 0
        private set

    /** Largest number of delivered-but-not-yet-requested events ever held at once (observable for tests). */
    var peakBuffered = 0
        private set

    init {
        require(pageSize > 0) { "pageSize must be positive" }
    }

    override suspend fun nextChunk(maxEvents: Int): List<BackupListenEvent> {
        require(maxEvents > 0) { "maxEvents must be positive" }
        while (ready.size < maxEvents && !exhausted) fetchNextPage()
        val out = ArrayList<BackupListenEvent>(minOf(maxEvents, ready.size))
        while (out.size < maxEvents && ready.isNotEmpty()) out += ready.removeFirst()
        return out
    }

    private suspend fun fetchNextPage() {
        val bound = upToId ?: run { exhausted = true; return }
        val page = fetchPage(cursor, bound, pageSize)
        pagesFetched++
        if (page.isEmpty()) {
            exhausted = true
            return
        }
        for (row in page) {
            val key = EventExportCursor.of(row)
            // The keyset must strictly advance, or a misbehaving source would loop forever.
            check(key > cursor) { "Event export paging did not advance (after=$cursor, got=$key)" }
            cursor = key
            if (!BackupEventExportRules.shouldExport(row.source)) continue
            val event = map(row)
            previous?.let {
                check(WavdropBackupIntegrityV2.EVENT_ORDER.compare(it, event) <= 0) {
                    "Event export order disagrees with the canonical fingerprint order at row id ${row.id}"
                }
            }
            previous = event
            ready += event
        }
        if (ready.size > peakBuffered) peakBuffered = ready.size
        if (page.size < pageSize) exhausted = true
    }
}
