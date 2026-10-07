package com.launchpoint.wavdrop.data.backup

import com.launchpoint.wavdrop.data.backup.wdbk.WdbkTestSupport
import com.launchpoint.wavdrop.data.local.entity.SongEntity
import com.launchpoint.wavdrop.data.local.entity.TrackListenEventEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * Proves that reducing the fingerprint's EVENT_ORDER to the event-table order
 * (occurredAt, songId, eventType, listenedMs, durationMs, source, COALESCE(eventId,''), id) is valid for WDBK export,
 * using the PRODUCTION entity-to-event mapping and the production cursor ordering.
 */
class CanonicalExportOrderReductionTest {

    private fun song(id: Long, uri: String, title: String, artist: String, album: String) = SongEntity(
        id = id, title = title, artist = artist, album = album, albumId = 1, duration = 1000, uri = uri, dateAdded = 1,
        trackNumber = 1, year = 2000,
    )

    private fun entity(
        id: Long, songId: Long, at: Long = 100, type: String = "PLAY", listened: Long = 10, duration: Long = 50,
        source: String = TrackListenEventEntity.SOURCE_WAVDROP_PLAYBACK, eventId: String? = null,
    ) = TrackListenEventEntity(id, songId, type, at, listened, duration, source, eventId)

    private fun dbOrder(rows: List<TrackListenEventEntity>) = rows.sortedBy { EventExportCursor.of(it) }

    // ── A: different songIds - songId decides before contentUri/title/artist/album ─────────────────

    @Test fun `A - songId decides before any song-derived field`() {
        // Metadata deliberately sorts OPPOSITE to songId: song 1 has the largest uri/title/artist/album.
        val songs = mapOf(
            1L to song(1, "content://z", "Zulu", "Zed", "Zoo"),
            2L to song(2, "content://a", "Alpha", "Ann", "Abbey"),
        )
        val e1 = entity(1, songId = 1).toBackupListenEvent(songs[1L])
        val e2 = entity(2, songId = 2).toBackupListenEvent(songs[2L])
        assertTrue(e1.contentUri > e2.contentUri && e1.title > e2.title && e1.artist > e2.artist && e1.album > e2.album)
        assertTrue("songId must win over the metadata", WavdropBackupIntegrityV2.EVENT_ORDER.compare(e1, e2) < 0)
        assertEquals(listOf(1L, 2L), dbOrder(listOf(entity(2, 2), entity(1, 1))).map { it.songId })
    }

    // ── B: same songId - one captured song gives identical metadata to every event ─────────────────

    @Test fun `B - every event of one song maps to identical song-derived fields`() {
        val s = song(7, "content://seven", "Seven", "Artist", "Album")
        val events = listOf(
            entity(1, 7, at = 5, type = "SKIP"), entity(2, 7, at = 9, listened = 99, eventId = "x"),
            entity(3, 7, at = 1, source = TrackListenEventEntity.SOURCE_MANUAL_RESTORE),
        ).map { it.toBackupListenEvent(s) }
        assertEquals(1, events.map { listOf(it.contentUri, it.title, it.artist, it.album) }.toSet().size)
    }

    // ── C: missing song - the existing empty-string behaviour, identical for all its events ───────

    @Test fun `C - a missing song maps to empty strings for every event`() {
        val events = (1L..4L).map { entity(it, songId = 99, at = it).toBackupListenEvent(null) }
        for (e in events) assertEquals(listOf("", "", "", ""), listOf(e.contentUri, e.title, e.artist, e.album))
        // ...and songId still orders missing-song events against present songs, whatever the present songs' text is.
        val present = entity(10, songId = 100).toBackupListenEvent(song(100, "", "", "", ""))
        val missingLow = entity(11, songId = 5).toBackupListenEvent(null)
        assertTrue(WavdropBackupIntegrityV2.EVENT_ORDER.compare(missingLow, present) < 0)
    }

    // ── D: database order, mapped, is exactly the fingerprint order ───────────────────────────────

    @Test fun `D - database-ordered mapped events are non-decreasing and equal the legacy stable canonical sort`() {
        val rnd = Random(20261007L)
        val texts = listOf("", "a", "B", "Zebra", "zebra", "é", "中", "mid", "Ａ", String(Character.toChars(0x1F3B5)))
        // Songs with random, deliberately anti-correlated metadata; some songIds are absent (missing song).
        val songs = (1L..40L).filter { it % 7 != 0L }.associateWith {
            song(it, "content://${texts[rnd.nextInt(texts.size)]}/${rnd.nextInt(9)}", texts[rnd.nextInt(texts.size)],
                texts[rnd.nextInt(texts.size)], texts[rnd.nextInt(texts.size)])
        }
        val rows = (1L..6_000L).map { id ->
            entity(
                id, songId = 1L + rnd.nextInt(40), at = 1_000L + rnd.nextInt(6), type = if (rnd.nextBoolean()) "PLAY" else "SKIP",
                listened = rnd.nextInt(4).toLong(), duration = rnd.nextInt(3).toLong(),
                source = listOf(
                    TrackListenEventEntity.SOURCE_WAVDROP_PLAYBACK, TrackListenEventEntity.SOURCE_MANUAL_RESTORE,
                    TrackListenEventEntity.SOURCE_DESKTOP_PLAYBACK,
                )[rnd.nextInt(3)],
                eventId = when (rnd.nextInt(4)) { 0 -> null; 1 -> "a${rnd.nextInt(3)}"; 2 -> "b${rnd.nextInt(3)}"; else -> "" },
            )
        }
        val mapped = dbOrder(rows).map { it.toBackupListenEvent(songs[it.songId]) }
        val order = WavdropBackupIntegrityV2.EVENT_ORDER
        for (i in 1 until mapped.size) assertTrue("row $i out of canonical order", order.compare(mapped[i - 1], mapped[i]) <= 0)
        // Exactly what the legacy path emitted: the getAllSnapshot() order stably sorted by the comparator.
        val legacy = LegacyExportOrder.snapshot(rows).map { it.toBackupListenEvent(songs[it.songId]) }.sortedWith(order)
        assertEquals(legacy, mapped)
    }

    // ── id tie-breaker cannot change the semantic fingerprint ─────────────────────────────────────

    private fun fingerprintOf(events: List<BackupListenEvent>) =
        WavdropBackupIntegrityV2.fingerprint(WdbkTestSupport.fullBackup(eventCount = 0, overlay = false).copy(listenEvents = events))

    @Test fun `rows tied on every canonical field are byte-identical events so their id order cannot change the fingerprint`() {
        val s = song(3, "content://t", "T", "A", "B")
        val tied = (1L..6L).map { entity(it, songId = 3, at = 50, eventId = "same").toBackupListenEvent(s) }
        assertEquals(1, tied.toSet().size) // identical canonical events
        val other = entity(7, songId = 4, at = 50).toBackupListenEvent(song(4, "c", "t", "a", "b"))
        val base = fingerprintOf(tied + other)
        assertEquals(base, fingerprintOf(tied.reversed() + other))
        assertEquals(base, fingerprintOf(listOf(other) + tied))
    }

    @Test fun `null versus empty eventId tie in the comparator, so the descending id tie-breaker preserves the legacy order`() {
        // Honest edge: a null eventId emits no eventId record while "" emits an empty one, yet the comparator treats both
        // as "". Legacy export stable-sorted getAllSnapshot() (occurredAt DESC; equal occurredAt in DESCENDING id), so the
        // HIGHER id stayed first. The export order must reproduce that, because swapping them changes the hash.
        val s = song(3, "content://t", "T", "A", "B")
        for ((nullId, emptyId) in listOf(1L to 2L, 2L to 1L)) {
            val rows = listOf(entity(nullId, 3, eventId = null), entity(emptyId, 3, eventId = ""))
            val legacy = LegacyExportOrder.snapshot(rows).map { it.toBackupListenEvent(s) }.sortedWith(WavdropBackupIntegrityV2.EVENT_ORDER)
            assertEquals(0, WavdropBackupIntegrityV2.EVENT_ORDER.compare(legacy[0], legacy[1]))
            val export = dbOrder(rows).map { it.toBackupListenEvent(s) }
            assertEquals("null id=$nullId empty id=$emptyId", legacy, export)
            assertEquals(fingerprintOf(legacy), fingerprintOf(export))
            assertNotEquals("the swapped order is a different hash", fingerprintOf(legacy), fingerprintOf(legacy.reversed()))
            // The higher id comes first in both.
            assertEquals(if (nullId > emptyId) null else "", export[0].eventId)
        }
    }

    @Test fun `the cursor order compares strings by code point like SQLite BINARY`() {
        val emoji = String(Character.toChars(0x1F600))
        assertTrue(EventExportCursor.of(entity(1, 1, eventId = "�")) < EventExportCursor.of(entity(2, 1, eventId = emoji)))
        assertTrue("UTF-16 String order disagrees", emoji < "\uFFFD")
        assertTrue(EventExportCursor.START < EventExportCursor.of(entity(1, Long.MIN_VALUE, at = Long.MIN_VALUE)))
    }
}
