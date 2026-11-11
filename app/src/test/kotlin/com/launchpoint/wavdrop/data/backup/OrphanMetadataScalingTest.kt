package com.launchpoint.wavdrop.data.backup

import com.launchpoint.wavdrop.data.backup.RecoveryTestFixtures.backupEvent
import com.launchpoint.wavdrop.data.backup.RecoveryTestFixtures.backupPlaylistSong
import com.launchpoint.wavdrop.data.backup.RecoveryTestFixtures.backupSong
import com.launchpoint.wavdrop.data.backup.RecoveryTestFixtures.backupStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P5-STATS-17C1: orphan metadata collection must be linear in the number of records, with results identical to the naive
 * per-orphan scan it replaced. Pure planner tests: no database, fully deterministic (work is counted, never timed).
 */
class OrphanMetadataScalingTest {

    private val fp = "fp"

    /** A list that counts every element read, so work can be asserted structurally instead of by wall-clock time. */
    private class CountingList<T>(private val inner: List<T>, private val reads: LongArray) : AbstractList<T>() {
        override val size: Int get() = inner.size
        override fun get(index: Int): T { reads[0]++; return inner[index] }
    }

    private data class Meta(val key: String, val title: String, val artist: String, val album: String, val uri: String?)

    private fun plan(b: WavdropBackup) = QuarantinePlanner.plan(b, fp, emptySet(), emptySet())

    private fun metas(b: WavdropBackup) = plan(b).newCandidates.map { Meta(it.originKey, it.title, it.artist, it.album, it.sourceUri) }

    /** The semantics 17C shipped, written the naive way: one full scan of every list per orphan. The reference for equivalence. */
    private fun naive(b: WavdropBackup): List<Meta> {
        val described = b.songs.map { it.id }.toSet()
        val ids = (b.trackStats.map { it.songId } + b.listenEvents.map { it.songId } + b.lyricsOverrides.map { it.songId } +
            b.importBaselines.map { it.songId } + b.playlists.flatMap { p -> p.songs.map { it.songId } })
            .filter { it !in described }.distinct().sorted()
        val fromSongs = b.songs.filter { s ->
            b.trackStats.any { it.songId == s.id } || b.listenEvents.any { it.songId == s.id } || b.lyricsOverrides.any { it.songId == s.id } ||
                b.importBaselines.any { it.songId == s.id } || b.playlists.any { p -> p.songs.any { it.songId == s.id } }
        }.map { Meta(QuarantinePlanner.originKey(fp, it.id), it.title, it.artist, it.album, it.uri) }
        val orphans = ids.map { id ->
            val tags = b.listenEvents.filter { it.songId == id }.map { Triple(it.title, it.artist, it.album) } +
                b.playlists.flatMap { p -> p.songs.filter { it.songId == id }.map { Triple(it.title, it.artist, it.album) } }
            val uris = b.trackStats.filter { it.songId == id }.map { it.contentUri } + b.lyricsOverrides.filter { it.songId == id }.map { it.contentUri } +
                b.listenEvents.filter { it.songId == id }.map { it.contentUri } +
                b.playlists.flatMap { p -> p.songs.filter { it.songId == id }.map { it.contentUri } }
            val t = tags.filter { it.first.isNotBlank() || it.second.isNotBlank() || it.third.isNotBlank() }.distinct().singleOrNull()
            Meta(QuarantinePlanner.originKey(fp, id), t?.first ?: "", t?.second ?: "", t?.third ?: "",
                uris.filter { it.isNotBlank() }.distinct().singleOrNull())
        }
        return fromSongs + orphans
    }

    private fun ev(id: Long, title: String, artist: String = "A", album: String = "B", uri: String = "content://media/$id", at: Long = 1_000L) =
        backupEvent(id, null, at, title).copy(artist = artist, album = album, contentUri = uri)

    private fun pl(id: Long, pos: Int, title: String, uri: String = "content://media/$id") =
        backupPlaylistSong(id, pos).copy(title = title, contentUri = uri)

    /** `n` orphans with ids from 1000 cycling through every record distribution, plus descriptor songs, plus the hand-made edge cases. */
    private fun big(n: Int): WavdropBackup {
        val stats = ArrayList<BackupTrackStats>(); val events = ArrayList<BackupListenEvent>(); val lyrics = ArrayList<BackupLyricsOverride>()
        val plSongs = ArrayList<BackupPlaylistSong>()
        for (i in 0 until n) {
            val id = 1_000L + i
            when (i % 5) {
                0 -> stats += backupStats(id, 3, 0, false)
                1 -> { events += ev(id, "T$id", at = 1L); events += ev(id, "T$id", at = 2L) }
                2 -> lyrics += BackupLyricsOverride(id, "content://media/$id", "l", 1L)
                3 -> { plSongs += pl(id, 0, "X$id"); plSongs += pl(id, 1, "Y$id") }            // conflicting titles: stays unknown
                4 -> events += ev(id, "", artist = "", album = "", uri = "", at = 3L)          // states nothing: stays unknown
            }
        }
        return RecoveryTestFixtures.v2Backup(
            songs = List(40) { backupSong(1L + it, "Gone $it") },                              // descriptor-bearing, unresolved
            stats = List(40) { backupStats(1L + it, 5, 1, false) } + stats,
            events = events, lyrics = lyrics,
            playlists = listOf(BackupPlaylist(1, "P", 1L, 2L, plSongs)),
        )
    }

    // ── equivalence with the naive scan, ordering, edge cases ─────────────────

    @Test fun `result and candidate order are identical to the naive per-orphan scan`() {
        val b = big(500)
        assertEquals(naive(b), metas(b))
    }

    @Test fun `descriptor songs come first in song order, then orphans in ascending id order`() {
        val b = big(60)
        val keys = metas(b).map { it.key }
        val expected = (1L..40L).map { QuarantinePlanner.originKey(fp, it) } + (0 until 60).map { QuarantinePlanner.originKey(fp, 1_000L + it) }
        assertEquals(expected, keys)
        assertEquals(keys, metas(b).map { it.key })                                           // and stable across calls
    }

    @Test fun `unanimous metadata is kept, conflicting metadata and blank metadata stay unknown`() {
        val b = RecoveryTestFixtures.v2Backup(
            songs = emptyList(),
            stats = listOf(backupStats(7, 1, 0, false), backupStats(8, 1, 0, false).copy(contentUri = ""), backupStats(9, 1, 0, false)),
            events = listOf(
                ev(7, "Same"), ev(7, "Same", at = 2L),                                         // unanimous triple and uri
                ev(8, "", artist = "", album = "", uri = ""),                                  // blank: nothing stated
                ev(9, "One", uri = "content://a"), ev(9, "Two", uri = "content://b", at = 2L), // conflicting titles and uris
            ),
        )
        val m = metas(b).associateBy { it.key }
        assertEquals(Meta(QuarantinePlanner.originKey(fp, 7), "Same", "A", "B", "content://media/7"), m.getValue(QuarantinePlanner.originKey(fp, 7)))
        val unknown = m.getValue(QuarantinePlanner.originKey(fp, 8))
        assertEquals(listOf("", "", ""), listOf(unknown.title, unknown.artist, unknown.album)); assertNull(unknown.uri)
        val conflicted = m.getValue(QuarantinePlanner.originKey(fp, 9))
        assertEquals(listOf("", "", ""), listOf(conflicted.title, conflicted.artist, conflicted.album)); assertNull(conflicted.uri)
        assertEquals(naive(b), metas(b))
    }

    @Test fun `stats, lyrics, events and playlist records spread over different orphans each contribute only to their own`() {
        val b = RecoveryTestFixtures.v2Backup(
            songs = emptyList(),
            stats = listOf(backupStats(1, 1, 0, false)),
            lyrics = listOf(BackupLyricsOverride(2, "content://lyrics/2", "l", 1L)),
            events = listOf(ev(3, "Three")),
            playlists = listOf(BackupPlaylist(1, "P", 1L, 2L, listOf(pl(4, 0, "Four")))),
        )
        val m = metas(b).associateBy { it.key }
        fun k(i: Long) = QuarantinePlanner.originKey(fp, i)
        assertEquals("content://media/1", m.getValue(k(1)).uri); assertEquals("", m.getValue(k(1)).title)
        assertEquals("content://lyrics/2", m.getValue(k(2)).uri)
        assertEquals("Three", m.getValue(k(3)).title)
        assertEquals("Four", m.getValue(k(4)).title)
        assertEquals(naive(b), metas(b))
    }

    // ── bounded work ──────────────────────────────────────────────────────────

    private fun reads(n: Int): Pair<Long, Long> {
        val b = big(n)
        val reads = LongArray(1)
        val counted = b.copy(
            songs = CountingList(b.songs, reads), trackStats = CountingList(b.trackStats, reads), listenEvents = CountingList(b.listenEvents, reads),
            lyricsOverrides = CountingList(b.lyricsOverrides, reads), importBaselines = CountingList(b.importBaselines, reads),
            playlists = b.playlists.map { it.copy(songs = CountingList(it.songs, reads)) }.let { CountingList(it, reads) },
        )
        val records = (b.songs.size + b.trackStats.size + b.listenEvents.size + b.lyricsOverrides.size + b.importBaselines.size +
            b.playlists.sumOf { it.songs.size } + b.playlists.size).toLong()
        assertEquals(n + 40, plan(counted).newCandidates.size)
        return reads[0] to records
    }

    @Test fun `work is bounded by the record count, not by orphans times records`() {
        val (small, smallRecords) = reads(1_500)
        val (large, largeRecords) = reads(6_000)
        // A fixed number of passes over the data (the planner's own indexing plus one hint pass): well under 20 reads per record...
        assertTrue("reads=$small records=$smallRecords", small <= 20 * smallRecords)
        assertTrue("reads=$large records=$largeRecords", large <= 20 * largeRecords)
        // ...and quadrupling the input must roughly quadruple, never ~16x, the work.
        assertTrue("small=$small large=$large", large <= 5 * small)
    }
}
