package com.launchpoint.wavdrop.ui.screen.home

import com.launchpoint.wavdrop.data.local.entity.TrackListenEventEntity
import com.launchpoint.wavdrop.data.local.entity.TrackStatsEntity
import com.launchpoint.wavdrop.data.model.HomeWrappedPreview
import com.launchpoint.wavdrop.data.model.HomeWrappedSongActivity
import com.launchpoint.wavdrop.data.model.ListeningPeriodRange
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.stats.HomeWrappedPreviewBuilder
import com.launchpoint.wavdrop.data.stats.WrappedBuilder
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WC-05 (lightweight Home Wrapped preview) and WC-11 (shared Home library lookup). The preview is checked against the OLD card
 * (full WrappedBuilder.buildYear + `hasActivity && !emptyState.isEmpty`) through a pure model of the new SQL aggregate; the SQL
 * itself is executed by the androidTest TrackListenEventHomeWrappedDaoTest.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeDashboardProjectionsTest {

    private val utc: ZoneId = ZoneOffset.UTC

    // ── fixtures ─────────────────────────────────────────────────────────────────────────────────────────────────────────

    private fun at(year: Int, month: Int, day: Int, hour: Int = 12, zone: ZoneId = utc): Long =
        LocalDateTime.of(year, month, day, hour, 0).atZone(zone).toInstant().toEpochMilli()

    private fun song(id: Long, title: String = "Song $id", artist: String = "Artist $id") = Song(
        id = id, title = title, artist = artist, album = "Album", albumId = id, duration = 200_000L,
        uri = "content://media/$id", dateAdded = 0L, trackNumber = 0, year = 2020,
    )

    private fun ev(song: Long, type: String, at: Long) = TrackListenEventEntity(
        songId = song, eventType = type, occurredAt = at, listenedMs = if (type == "PLAY") 60_000L else 0L,
        durationMs = 200_000L, source = TrackListenEventEntity.SOURCE_WAVDROP_PLAYBACK,
    )

    private fun play(song: Long, at: Long) = ev(song, TrackListenEventEntity.TYPE_PLAY, at)
    private fun skip(song: Long, at: Long) = ev(song, TrackListenEventEntity.TYPE_SKIP, at)

    /** The old Home card: full yearly WrappedSummary, shown only with matched live activity. */
    private fun old(year: Int, songs: List<Song>, events: List<TrackListenEventEntity>, zone: ZoneId) =
        WrappedBuilder.buildYear(year = year, songs = songs, events = events.sortedByDescending { it.occurredAt }, zone = zone) // the DAO read ORDER BY occurredAt DESC
            .takeIf { it.hasActivity && !it.emptyState.isEmpty }

    /** The new SQL aggregate, modelled: live INNER JOIN rows for PLAY/SKIP in the inclusive range + all-event PLAY total. */
    private fun aggregate(events: List<TrackListenEventEntity>, live: Set<Long>, year: Int, zone: ZoneId): List<HomeWrappedSongActivity> {
        val range = ListeningPeriodRange.year(year, zone)
        val inRange = events.filter { it.occurredAt in range.fromMs..range.toMs }
        val total = inRange.count { it.eventType == "PLAY" }
        return inRange.filter { (it.eventType == "PLAY" || it.eventType == "SKIP") && it.songId in live }
            .groupBy { it.songId }
            .map { (id, l) -> HomeWrappedSongActivity(id, l.count { it.eventType == "PLAY" }, l.count { it.eventType == "SKIP" }, total, l.filter { it.eventType == "PLAY" }.maxOfOrNull { it.occurredAt }) }
            .sortedBy { it.songId }
    }

    private fun new(year: Int, songs: List<Song>, events: List<TrackListenEventEntity>, zone: ZoneId): HomeWrappedPreview? =
        HomeWrappedPreviewBuilder.build(year, songs.associateBy { it.id }, aggregate(events, songs.map { it.id }.toSet(), year, zone))

    private fun assertSameCard(label: String, year: Int, songs: List<Song>, events: List<TrackListenEventEntity>, zone: ZoneId = utc) {
        val o = old(year, songs, events, zone)
        val n = new(year, songs, events, zone)
        assertEquals("$label: present", o != null, n != null)
        if (o == null || n == null) return
        assertEquals("$label: label", o.period.displayLabel, n.displayLabel)
        assertEquals("$label: total", o.totalPlayCount, n.totalPlayCount)
        assertEquals("$label: artist", o.mostPlayedArtist?.artistKey, n.topArtistKey)
        assertEquals("$label: song id", o.mostPlayedSong?.song?.id, n.topSong?.id)
        assertEquals("$label: song title", o.mostPlayedSong?.song?.displayTitle, n.topSong?.displayTitle)
    }

    // ── WC-05 equivalence cases ──────────────────────────────────────────────────────────────────────────────────────────

    @Test fun noEventsAndUnsupportedTypesOnlyShowNoPreview() {
        assertSameCard("none", 2026, listOf(song(1)), emptyList())
        assertSameCard("unsupported", 2026, listOf(song(1)), listOf(ev(1, "PAUSE", at(2026, 3, 1)), ev(1, "FUTURE", at(2026, 3, 2))))
        assertNull(new(2026, listOf(song(1)), listOf(ev(1, "PAUSE", at(2026, 3, 1))), utc))
    }

    @Test fun orphanOnlyHistoryNeverCreatesAPreview() {
        val songs = listOf(song(1))
        assertSameCard("orphan play", 2026, songs, listOf(play(99, at(2026, 3, 1))))
        assertSameCard("orphan skip", 2026, songs, listOf(skip(99, at(2026, 3, 1))))
        assertNull(new(2026, songs, listOf(play(99, at(2026, 3, 1)), skip(98, at(2026, 3, 2))), utc))
    }

    @Test fun skipOnlyLiveActivityShowsAPreviewWithZeroPlaysAndNoTopItems() {
        val songs = listOf(song(1))
        val events = listOf(skip(1, at(2026, 3, 1)))
        assertSameCard("skip only", 2026, songs, events)
        val card = new(2026, songs, events, utc)!!
        assertEquals(0, card.totalPlayCount)
        assertNull(card.topArtistKey); assertNull(card.topSong)
    }

    @Test fun orphanPlaysCountInTheTotalWhenLiveActivityExists() {
        val songs = listOf(song(1))
        val mixed = listOf(play(99, at(2026, 3, 1)), play(99, at(2026, 3, 2)), skip(1, at(2026, 3, 3)))
        assertSameCard("orphan play + live skip", 2026, songs, mixed)
        assertEquals(2, new(2026, songs, mixed, utc)!!.totalPlayCount)
        val livePlusOrphan = listOf(play(1, at(2026, 3, 1)), play(99, at(2026, 3, 2)))
        assertSameCard("live + orphan play", 2026, songs, livePlusOrphan)
        assertEquals(2, new(2026, songs, livePlusOrphan, utc)!!.totalPlayCount)
        assertEquals(1L, new(2026, songs, livePlusOrphan, utc)!!.topSong?.id)
    }

    @Test fun rankingAndTiesMatchTheOldCard() {
        val songs = listOf(
            song(1, "beta", "Zed"), song(2, "Alpha", "Zed"), song(3, "alpha", "Mid"), song(4, "Åsa", "Åsa"), song(5, "x", "  "), song(6, "y", ""),
        )
        val events = listOf(
            play(1, at(2026, 1, 1)), play(2, at(2026, 1, 2)), play(3, at(2026, 1, 3)), // song tie on title lowercase -> id
            play(4, at(2026, 1, 4)), play(5, at(2026, 1, 5)), play(6, at(2026, 1, 6)),  // blank artists -> Unknown Artist (2 plays)
            skip(1, at(2026, 1, 7)),
        )
        assertSameCard("ties", 2026, songs, events)
        val card = new(2026, songs, events, utc)!!
        assertEquals("Unknown Artist", card.topArtistKey) // 2-2 tie with Zed: "unknown artist" < "zed"
        assertSameCard("single", 2026, songs.take(1), events.take(1))
    }

    @Test fun eventsOutsideTheYearAndTimezoneBoundariesMatch() {
        val songs = listOf(song(1), song(2))
        val events = listOf(play(1, at(2025, 12, 31, 23)), play(2, at(2026, 1, 1, 0)), play(2, at(2026, 12, 31, 23)), play(1, at(2027, 1, 1, 0)))
        for (zone in listOf(utc, ZoneId.of("Pacific/Auckland"), ZoneId.of("America/Los_Angeles"), ZoneId.of("Asia/Kolkata"))) {
            for (year in 2025..2027) assertSameCard("$zone/$year", year, songs, events, zone)
        }
        // inclusive upper bound: an event at the last millisecond of the year belongs to it
        val last = ListeningPeriodRange.year(2026, utc).toMs
        assertSameCard("last ms", 2026, songs, listOf(play(1, last), play(2, last + 1)))
        assertEquals(1, new(2026, songs, listOf(play(1, last), play(2, last + 1)), utc)!!.totalPlayCount)
    }

    @Test fun randomizedDatasetsMatchTheOldCard() {
        val rnd = Random(2026)
        val artists = listOf("A", "b", "C", " ", "", "  Pad  ", "Åsa", "éclair", "Zed", "Unknown Artist")
        val zones = listOf(utc, ZoneId.of("Pacific/Auckland"), ZoneId.of("America/Los_Angeles"))
        repeat(400) { n ->
            val songCount = 1 + rnd.nextInt(8)
            val songs = (1L..songCount).map { song(it, title = listOf("t", "T", "u", "Ünï", "ünï")[rnd.nextInt(5)], artist = artists[rnd.nextInt(artists.size)]) }
            val types = listOf("PLAY", "PLAY", "SKIP", "PAUSE")
            val events = (0 until rnd.nextInt(25)).map { k ->
                val songId = 1L + rnd.nextInt(songCount + 3) // some ids are orphans
                ev(songId, types[rnd.nextInt(types.size)], at(2025 + rnd.nextInt(3), 1 + rnd.nextInt(12), 1 + rnd.nextInt(28), rnd.nextInt(24)) + k) // unique ms: exact-timestamp ties were never specified
            }
            assertSameCard("random#$n", 2026, songs, events, zones[n % zones.size])
        }
    }

    // ── lowercase-equal artist keys: the old stable-sort order must be reproduced ───────────────────────────────────────────

    @Test fun caseOnlyArtistKeysWithEqualPlaysFollowTheOldPreSortOrderAndFlipWhenRecencyFlips() {
        val songs = listOf(song(1, artist = "Artist"), song(2, artist = "artist"))
        // both artists have 2 plays; "Artist" owns the most recent PLAY -> the old stable sort kept it first
        val artistLatest = listOf(play(2, at(2026, 3, 1)), play(1, at(2026, 3, 2)), play(2, at(2026, 3, 3)), play(1, at(2026, 3, 4)))
        assertSameCard("Artist most recent", 2026, songs, artistLatest)
        assertEquals("Artist", old(2026, songs, artistLatest, utc)!!.mostPlayedArtist!!.artistKey)
        assertEquals("Artist", new(2026, songs, artistLatest, utc)!!.topArtistKey)
        // reverse the meaningful ordering: now "artist" owns the most recent PLAY
        val lowerLatest = listOf(play(1, at(2026, 3, 1)), play(2, at(2026, 3, 2)), play(1, at(2026, 3, 3)), play(2, at(2026, 3, 4)))
        assertSameCard("artist most recent", 2026, songs, lowerLatest)
        assertEquals("artist", old(2026, songs, lowerLatest, utc)!!.mostPlayedArtist!!.artistKey)
        assertEquals("artist", new(2026, songs, lowerLatest, utc)!!.topArtistKey)
        // a case-sensitive fallback ("Artist" < "artist") would have answered "Artist" for both
    }

    @Test fun anArtistIsPositionedByItsMostRecentPlayBackedSongNotByASingleSong() {
        val songs = listOf(song(1, artist = "Artist"), song(2, artist = "artist"), song(3, artist = "Artist"), song(4, artist = "ARTIST"))
        // "Artist" = songs 1+3 (2 plays; song 3 most recent overall), "artist" = song 2 (2 plays), "ARTIST" = song 4 (2 plays)
        val events = listOf(
            play(1, at(2026, 1, 1)), play(2, at(2026, 1, 2)), play(2, at(2026, 1, 3)), play(4, at(2026, 1, 4)),
            play(4, at(2026, 1, 5)), play(3, at(2026, 1, 6)), skip(2, at(2026, 1, 7)), // skips never move an artist
        )
        assertSameCard("multi-song", 2026, songs, events)
        assertEquals("Artist", new(2026, songs, events, utc)!!.topArtistKey)
        val shifted = events + play(4, at(2026, 2, 1)) + play(4, at(2026, 2, 2)) // ARTIST now 4 plays: wins on count
        assertSameCard("count wins", 2026, songs, shifted)
        assertEquals("ARTIST", new(2026, songs, shifted, utc)!!.topArtistKey)
        val lastWins = listOf(play(1, at(2026, 1, 1)), play(3, at(2026, 1, 2)), play(2, at(2026, 1, 3)), play(2, at(2026, 1, 4)), play(1, at(2026, 1, 9)))
        assertSameCard("song 1 latest", 2026, songs, lastWins) // Artist 3 plays vs artist 2 plays -> Artist by count
        val tied = listOf(play(1, at(2026, 1, 1)), play(3, at(2026, 1, 9)), play(2, at(2026, 1, 3)), play(2, at(2026, 1, 4)))
        assertSameCard("recent via song 3", 2026, songs, tied)
        assertEquals("Artist", new(2026, songs, tied, utc)!!.topArtistKey)
    }

    @Test fun randomizedCaseOnlyArtistCollisionsMatchTheOldCard() {
        val rnd = Random(77)
        val artists = listOf("Artist", "artist", "ARTIST", "Béla", "béla", "Other") // lowercase collisions on purpose
        repeat(600) { n ->
            val songCount = 2 + rnd.nextInt(6)
            // guarantee at least one lowercase-collision pair among the songs
            val songs = (1L..songCount).map { id ->
                val a = when (id) { 1L -> "Artist"; 2L -> "artist"; else -> artists[rnd.nextInt(artists.size)] }
                song(id, title = listOf("t", "T", "u")[rnd.nextInt(3)], artist = a)
            }
            val events = (0 until 4 + rnd.nextInt(14)).map { k ->
                ev(1L + rnd.nextInt(songCount), if (rnd.nextInt(5) == 0) "SKIP" else "PLAY", at(2026, 1 + rnd.nextInt(12), 1 + rnd.nextInt(28), rnd.nextInt(24)) + k)
            }
            assertSameCard("collision#$n", 2026, songs, events)
        }
    }

    // ── WC-11 library projection ─────────────────────────────────────────────────────────────────────────────────────────

    @Test fun libraryProjectionContainsEverySongAndMapsIdsCorrectly() {
        val songs = (1L..5L).map { song(it) }
        val p = HomeLibraryProjection.of(songs)
        assertSame(songs, p.songs)
        assertEquals(5, p.songsById.size)
        songs.forEach { assertSame(it, p.songsById[it.id]) }
        assertEquals(0, HomeLibraryProjection.of(emptyList()).songsById.size)
        assertTrue(HomeLibraryProjection.EMPTY.songs.isEmpty())
    }

    private class Inputs {
        val library = MutableStateFlow<List<Song>?>(null)
        val stats = MutableStateFlow(emptyList<TrackStatsEntity>() to emptyList<TrackStatsEntity>())
        val playlists = MutableStateFlow(emptyList<com.launchpoint.wavdrop.data.model.PlaylistSummary>())
        val smart = MutableStateFlow(emptyList<com.launchpoint.wavdrop.data.model.SmartCollection>())
        val wrapped = MutableStateFlow<HomeWrappedPreview?>(null)
        var builds = 0
    }

    private fun stat(id: Long, plays: Int) = TrackStatsEntity(songId = id, contentUri = "u$id", playCount = plays)

    @Test fun theLookupIsBuiltOncePerLibraryEmissionAndReusedByEveryOtherDashboardInput() = runBlocking {
        val i = Inputs()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val projection = homeLibraryProjectionFlow(i.library) { i.builds++; HomeLibraryProjection.of(it) }
                .stateIn(scope, SharingStarted.Eagerly, HomeLibraryProjection.EMPTY)
            val states = mutableListOf<HomeDashboardUiState>()
            val job = scope.launch { homeDashboardFlow(projection, i.stats, i.playlists, i.smart, i.wrapped).collect { states += it } }
            // a second consumer of the same projection (the Wrapped card) must not build again
            val wrappedLookup = projection

            val songs = (1L..4L).map { song(it) }
            i.library.value = songs
            val afterLibrary = i.builds
            repeat(20) { n ->
                i.stats.value = listOf(stat(2, 9), stat(1, 5)) to listOf(stat(3, n + 1))
                i.playlists.value = emptyList()
                i.wrapped.value = HomeWrappedPreview(2026, "2026", n, null, null)
            }
            assertEquals("non-library emissions never rebuild the lookup", afterLibrary, i.builds)
            assertSame(projection.value.songsById, wrappedLookup.value.songsById)

            val last = states.last()
            assertEquals(4, last.totalSongs)
            assertEquals(listOf(2L, 1L), last.recentlyPlayed.map { it.id }) // WC-04 row order preserved
            assertEquals(listOf(3L), last.mostPlayed.map { it.id })
            assertSame(songs[1], last.recentlyPlayed.first()) // the actual Song objects

            // a real library change rebuilds exactly once and no stale lookup survives it
            val before = projection.value
            val shrunk = songs.filter { it.id != 2L }
            i.library.value = shrunk
            assertEquals(afterLibrary + 1, i.builds)
            assertNotSame(before, projection.value)
            assertFalse(projection.value.songsById.containsKey(2L))
            assertEquals(listOf(1L), states.last().recentlyPlayed.map { it.id }) // 2 vanished, orphan stat skipped
            assertEquals(3, states.last().totalSongs)
            job.cancel()
        } finally {
            scope.cancel()
        }
    }


    // ── source guards ────────────────────────────────────────────────────────────────────────────────────────────────────

    private fun read(path: String) = File("src/main/kotlin/com/launchpoint/wavdrop/$path").readText().replace("\r\n", "\n")
    private fun code(path: String) = read(path).lines().filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") || it.trimStart().startsWith("/**") }.joinToString("\n")

    @Test fun homeNoLongerBuildsAWrappedSummaryOrRebuildsTheLookupInTheDashboardCombine() {
        val vm = code("ui/screen/home/HomeViewModel.kt")
        assertFalse(vm.contains("WrappedBuilder"))
        assertFalse(vm.contains("buildYear"))
        assertFalse(vm.contains("WrappedSummary"))
        val wrappedBlock = vm.substringAfter("private val wrappedPreview").substringBefore("private val homeSmartCollections")
        assertTrue(wrappedBlock.contains("homeWrappedActivity("))
        assertFalse("Home Wrapped must not subscribe to the year's event rows", wrappedBlock.contains("listenEventsInRange"))
        val dashboard = vm.substringAfter("internal fun homeDashboardFlow").substringBefore("\n}\n")
        assertFalse("the dashboard combine must not build a lookup", dashboard.contains("associateBy"))
        val associateLines = vm.lines().filter { it.contains("associateBy") }
        assertEquals(associateLines.toString(), 1, associateLines.size)
        assertTrue(associateLines.single().contains("fun of("))
    }

    @Test fun theNewAggregateQueryJoinsLiveSongsAndKeepsOrphanPlaysInTheTotal() {
        val dao = read("data/local/dao/TrackListenEventDao.kt").replace(Regex("\\s+"), " ")
        assertTrue(dao.contains("INNER JOIN songs AS s ON s.id = e.songId"))
        assertTrue(dao.contains("(SELECT COUNT(*) FROM track_listen_events AS t WHERE t.eventType = 'PLAY' AND t.occurredAt >= :fromMs AND t.occurredAt <= :toMs) AS totalPlayCount"))
        assertTrue(dao.contains("e.occurredAt >= :fromMs AND e.occurredAt <= :toMs AND e.eventType IN ('PLAY', 'SKIP') GROUP BY e.songId ORDER BY e.songId ASC"))
    }
}
