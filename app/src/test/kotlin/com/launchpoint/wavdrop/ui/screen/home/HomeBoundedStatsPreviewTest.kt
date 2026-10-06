package com.launchpoint.wavdrop.ui.screen.home

import com.launchpoint.wavdrop.data.local.entity.TrackStatsEntity
import com.launchpoint.wavdrop.data.model.Song
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WC-04: Home previews are ranked, live-song-filtered and limited in SQLite. There is no JVM Room harness, so the SQL text is pinned
 * by source-contract tests and its behaviour is pinned by a pure model of the query checked against the old Kotlin pipeline; the SQL
 * itself is physically executed by the androidTest TrackStatsPreviewDaoTest.
 */
class HomeBoundedStatsPreviewTest {

    private fun stat(id: Long, plays: Int = 0, listened: Long = 0L) =
        TrackStatsEntity(songId = id, contentUri = "content://media/$id", playCount = plays, lastListenedAt = listened)

    private fun song(id: Long) = Song(
        id = id, title = "S$id", artist = "A", album = "B", albumId = 0L, duration = 1L,
        uri = "content://media/$id", dateAdded = 0L, trackNumber = 0, year = 2020,
    )

    /** The pre-WC-04 Home pipeline over the whole stats table (read in songId order, as getAllStats() practically returned it). */
    private fun oldMostPlayed(stats: List<TrackStatsEntity>, live: Set<Long>, limit: Int) =
        stats.sortedBy { it.songId }.filter { it.playCount > 0 }.sortedByDescending { it.playCount }
            .mapNotNull { if (it.songId in live) it.songId else null }.take(limit)

    private fun oldRecentlyListened(stats: List<TrackStatsEntity>, live: Set<Long>, limit: Int) =
        stats.sortedBy { it.songId }.filter { it.lastListenedAt > 0 }.sortedByDescending { it.lastListenedAt }
            .mapNotNull { if (it.songId in live) it.songId else null }.take(limit)

    /** The intended SQL: live INNER JOIN + metric filter + ORDER BY metric DESC, songId ASC + LIMIT. */
    private fun sqlMostPlayed(stats: List<TrackStatsEntity>, live: Set<Long>, limit: Int) =
        stats.filter { it.songId in live && it.playCount > 0 }
            .sortedWith(compareByDescending<TrackStatsEntity> { it.playCount }.thenBy { it.songId }).take(limit).map { it.songId }

    private fun sqlRecentlyListened(stats: List<TrackStatsEntity>, live: Set<Long>, limit: Int) =
        stats.filter { it.songId in live && it.lastListenedAt > 0 }
            .sortedWith(compareByDescending<TrackStatsEntity> { it.lastListenedAt }.thenBy { it.songId }).take(limit).map { it.songId }

    private val live = (1L..10L).toSet()

    @Test fun orphanHighPlayStatDoesNotConsumeTheLimitAndTheFifthValidRowBackfills() {
        val stats = listOf(stat(99, 1000), stat(1, 100), stat(2, 90), stat(3, 80), stat(4, 70), stat(5, 60))
        assertEquals(listOf(1L, 2L, 3L, 4L), sqlMostPlayed(stats, live, 4))
        assertEquals(oldMostPlayed(stats, live, 4), sqlMostPlayed(stats, live, 4))
        // the naive LIMIT-then-filter would have produced only 1,2,3
        assertEquals(listOf(1L, 2L, 3L), stats.sortedByDescending { it.playCount }.take(4).map { it.songId }.filter { it in live })
    }

    @Test fun orphanNewestListenedStatDoesNotConsumeTheLimit() {
        val stats = listOf(stat(99, listened = 9_000), stat(1, listened = 800), stat(2, listened = 700), stat(3, listened = 600), stat(4, listened = 500), stat(5, listened = 400))
        assertEquals(listOf(1L, 2L, 3L, 4L), sqlRecentlyListened(stats, live, 4))
        assertEquals(oldRecentlyListened(stats, live, 4), sqlRecentlyListened(stats, live, 4))
    }

    @Test fun zeroMetricsAreExcluded() {
        val stats = listOf(stat(1, plays = 0, listened = 0), stat(2, plays = 3, listened = 0), stat(3, plays = 0, listened = 50))
        assertEquals(listOf(2L), sqlMostPlayed(stats, live, 4))
        assertEquals(listOf(3L), sqlRecentlyListened(stats, live, 4))
        assertEquals(emptyList<Long>(), sqlMostPlayed(listOf(stat(1), stat(2)), live, 4)) // blank stats rows fill nothing
        assertEquals(emptyList<Long>(), sqlRecentlyListened(listOf(stat(1), stat(2)), live, 4))
    }

    @Test fun tiesResolveBySongIdAscendingAndMatchTheOldStableSort() {
        val stats = listOf(stat(7, 5, 100), stat(3, 5, 100), stat(5, 5, 100), stat(1, 9, 200), stat(9, 5, 100), stat(2, 5, 100))
        assertEquals(listOf(1L, 2L, 3L, 5L), sqlMostPlayed(stats, live, 4))
        assertEquals(oldMostPlayed(stats, live, 4), sqlMostPlayed(stats, live, 4))
        assertEquals(oldRecentlyListened(stats, live, 4), sqlRecentlyListened(stats, live, 4))
        assertEquals(listOf(1L, 2L, 3L, 5L), sqlRecentlyListened(stats, live, 4))
    }

    @Test fun fewerThanLimitReturnsAllValidAndNoneReturnsEmpty() {
        val stats = listOf(stat(1, 4, 40), stat(2, 2, 20), stat(99, 50, 500))
        assertEquals(listOf(1L, 2L), sqlMostPlayed(stats, live, 4))
        assertEquals(listOf(1L, 2L), sqlRecentlyListened(stats, live, 4))
        assertEquals(emptyList<Long>(), sqlMostPlayed(stats, emptySet(), 4))
        assertEquals(emptyList<Long>(), sqlRecentlyListened(emptyList(), live, 4))
    }

    @Test fun deletingATopLiveSongBackfillsTheNextCandidate() {
        val stats = (1L..6L).map { stat(it, plays = (10 - it).toInt(), listened = 100 - it) }
        assertEquals(listOf(1L, 2L, 3L, 4L), sqlMostPlayed(stats, live, 4))
        val afterDelete = live - 1L
        assertEquals(listOf(2L, 3L, 4L, 5L), sqlMostPlayed(stats, afterDelete, 4))
        assertEquals(listOf(2L, 3L, 4L, 5L), sqlRecentlyListened(stats, afterDelete, 4))
        assertEquals(oldMostPlayed(stats, afterDelete, 4), sqlMostPlayed(stats, afterDelete, 4))
    }

    @Test fun modelMatchesTheOldPipelineAcrossRandomizedTablesWithOrphansAndTies() {
        val rnd = java.util.Random(42)
        repeat(300) {
            val stats = (1L..30L).map { stat(it, plays = rnd.nextInt(4), listened = rnd.nextInt(4).toLong() * 10) }
            val liveIds = (1L..30L).filter { rnd.nextInt(3) != 0 }.toSet()
            assertEquals(oldMostPlayed(stats, liveIds, 4), sqlMostPlayed(stats, liveIds, 4))
            assertEquals(oldRecentlyListened(stats, liveIds, 4), sqlRecentlyListened(stats, liveIds, 4))
        }
    }

    @Test fun homeMapsTheBoundedStatIdsToTheCorrectSongsInSqlOrder() {
        val songsById = (1L..5L).associateWith { song(it) }
        val ranked = listOf(stat(3, 9), stat(1, 8), stat(99, 7), stat(5, 6))
        assertEquals(listOf(3L, 1L, 5L), homePreviewSongs(ranked, songsById).map { it.id })
        assertEquals(songsById[3L], homePreviewSongs(ranked, songsById).first())
        assertEquals(emptyList<Song>(), homePreviewSongs(emptyList(), songsById))
    }

    // ── source contracts ─────────────────────────────────────────────────────────────────────────────────────────────────

    private fun read(path: String) = File("src/main/kotlin/com/launchpoint/wavdrop/$path").readText().replace("\r\n", "\n")
    private fun squash(s: String) = s.replace(Regex("\\s+"), " ")

    @Test fun theDaoQueriesJoinLiveSongsBeforeLimitAndOrderDeterministically() {
        val dao = squash(read("data/local/dao/TrackStatsDao.kt"))
        val most = "SELECT ts.* FROM track_stats AS ts INNER JOIN songs AS s ON s.id = ts.songId WHERE ts.playCount > 0 ORDER BY ts.playCount DESC, ts.songId ASC LIMIT :limit"
        val recent = "SELECT ts.* FROM track_stats AS ts INNER JOIN songs AS s ON s.id = ts.songId WHERE ts.lastListenedAt > 0 ORDER BY ts.lastListenedAt DESC, ts.songId ASC LIMIT :limit"
        assertTrue(dao.contains(most))
        assertTrue(dao.contains(recent))
        assertFalse("Home Recently Played must use lastListenedAt, never lastPlayedAt", recent.contains("lastPlayedAt"))
        // the general APIs keep their contracts
        assertTrue(dao.contains("""@Query("SELECT * FROM track_stats ORDER BY playCount DESC") fun getMostPlayed()"""))
        assertTrue(dao.contains("""@Query("SELECT * FROM track_stats WHERE lastPlayedAt > 0 ORDER BY lastPlayedAt DESC") fun getRecentlyPlayed()"""))
    }

    @Test fun theRepositoryPreviewsAreBoundedAndGuardNonPositiveLimits() {
        val repo = squash(read("data/repository/StatsRepository.kt"))
        assertTrue(repo.contains("fun mostPlayedPreview(limit: Int): Flow<List<TrackStatsEntity>> = if (limit <= 0) flowOf(emptyList()) else dao.observeMostPlayedPreview(limit)"))
        assertTrue(repo.contains("fun recentlyListenedPreview(limit: Int): Flow<List<TrackStatsEntity>> = if (limit <= 0) flowOf(emptyList()) else dao.observeRecentlyListenedPreview(limit)"))
        assertTrue(repo.contains("fun allTrackStatsEntities(): Flow<List<TrackStatsEntity>> = dao.getAllStats()")) // untouched
    }

    @Test fun homeDashboardNoLongerSubscribesToTheFullStatsTableOrSortsItLocally() {
        val code = read("ui/screen/home/HomeViewModel.kt").lines().filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }.joinToString("\n")
        assertFalse("Home must not read the whole stats table", code.contains("allTrackStatsEntities"))
        assertFalse("Home must not locally sort stats for the previews", Regex("sortedByDescending\\s*\\{\\s*it\\.(playCount|lastListenedAt)").containsMatchIn(code))
        assertTrue(code.contains("recentlyListenedPreview(DASHBOARD_SONG_PREVIEW_LIMIT)"))
        assertTrue(code.contains("mostPlayedPreview(DASHBOARD_SONG_PREVIEW_LIMIT)"))
    }
}
