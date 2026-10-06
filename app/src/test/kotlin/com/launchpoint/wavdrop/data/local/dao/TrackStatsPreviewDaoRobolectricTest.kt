package com.launchpoint.wavdrop.data.local.dao

import androidx.room.Room
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import com.launchpoint.wavdrop.data.local.WavdropDatabase
import com.launchpoint.wavdrop.data.local.entity.SongEntity
import com.launchpoint.wavdrop.data.local.entity.TrackStatsEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** JVM (Robolectric, real SQLite) twin of the androidTest class of the same name. WC-04: executes the real Home preview SQL against an in-memory Room database. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TrackStatsPreviewDaoRobolectricTest {

    private lateinit var db: WavdropDatabase
    private lateinit var songDao: SongDao
    private lateinit var statsDao: TrackStatsDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), WavdropDatabase::class.java)
            .allowMainThreadQueries().build()
        songDao = db.songDao()
        statsDao = db.trackStatsDao()
    }

    @After
    fun tearDown() = db.close()

    private fun song(id: Long) = SongEntity(
        id = id, title = "S$id", artist = "A", album = "B", albumId = 0L, duration = 1L,
        uri = "content://media/$id", dateAdded = 0L, trackNumber = 0, year = 2020,
    )

    private suspend fun stat(id: Long, plays: Int = 0, listened: Long = 0L) =
        statsDao.insertIfAbsent(TrackStatsEntity(songId = id, contentUri = "content://media/$id", playCount = plays, lastListenedAt = listened))

    private suspend fun most(limit: Int = 4) = statsDao.observeMostPlayedPreview(limit).first().map { it.songId }
    private suspend fun recent(limit: Int = 4) = statsDao.observeRecentlyListenedPreview(limit).first().map { it.songId }

    @Test fun orphansDoNotConsumeTheLimitAndTheFifthLiveRowBackfills() = runBlocking {
        songDao.upsertAll((1L..5L).map(::song)) // 99 is an orphan: stats without a song
        stat(99, plays = 1000, listened = 9_000)
        (1L..5L).forEach { stat(it, plays = (110 - it * 10).toInt(), listened = 1_000 - it) }
        assertEquals(listOf(1L, 2L, 3L, 4L), most())
        assertEquals(listOf(1L, 2L, 3L, 4L), recent())
    }

    @Test fun zeroMetricsAreExcludedAndBlankLibraryIsEmpty() = runBlocking {
        songDao.upsertAll((1L..3L).map(::song))
        (1L..3L).forEach { stat(it) }
        assertEquals(emptyList<Long>(), most())
        assertEquals(emptyList<Long>(), recent())
        statsDao.insertIfAbsent(TrackStatsEntity(songId = 4, contentUri = "u4"))
        stat(5, plays = 2); stat(6, listened = 7)
        songDao.upsertAll(listOf(song(5), song(6)))
        assertEquals(listOf(5L), most())
        assertEquals(listOf(6L), recent())
    }

    @Test fun tiesResolveBySongIdAscendingAndFewerThanLimitReturnsAll() = runBlocking {
        songDao.upsertAll((1L..9L).map(::song))
        listOf(7L, 3L, 5L, 9L, 2L).forEach { stat(it, plays = 5, listened = 100) }
        stat(1, plays = 9, listened = 200)
        assertEquals(listOf(1L, 2L, 3L, 5L), most())
        assertEquals(listOf(1L, 2L, 3L, 5L), recent())
        assertEquals(listOf(1L, 2L), most(limit = 2))
    }

    @Test fun deletingATopSongBackfillsAndTheFlowReEmits() = runBlocking {
        songDao.upsertAll((1L..6L).map(::song))
        (1L..6L).forEach { stat(it, plays = (10 - it).toInt(), listened = 100 - it) }
        assertEquals(listOf(1L, 2L, 3L, 4L), most())
        songDao.deleteSong(1L)
        assertEquals(listOf(2L, 3L, 4L, 5L), most())
        assertEquals(listOf(2L, 3L, 4L, 5L), recent())
    }

    @Test fun listenStartAndPlayCountChangesReorderThePreviews() = runBlocking {
        songDao.upsertAll((1L..5L).map(::song))
        (1L..5L).forEach { stat(it, plays = (6 - it).toInt(), listened = 100 - it) }
        statsDao.updateLastListenedAt(5L, 10_000L)
        assertEquals(5L, recent().first())
        repeat(10) { statsDao.incrementPlayCount(5L, nowMs = 1L, listenedMs = 1L) }
        assertEquals(5L, most().first())
        assertEquals(4, most().size)
    }
}
