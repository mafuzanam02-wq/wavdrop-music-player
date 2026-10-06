package com.launchpoint.wavdrop.data.local.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.launchpoint.wavdrop.data.local.WavdropDatabase
import com.launchpoint.wavdrop.data.local.entity.SongEntity
import com.launchpoint.wavdrop.data.local.entity.TrackListenEventEntity
import com.launchpoint.wavdrop.data.model.HomeWrappedSongActivity
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** WC-05: executes the real Home Wrapped aggregate SQL against an in-memory Room database. */
@RunWith(AndroidJUnit4::class)
class TrackListenEventHomeWrappedDaoTest {

    private lateinit var db: WavdropDatabase
    private lateinit var songDao: SongDao
    private lateinit var eventDao: TrackListenEventDao

    private val from = 1_000L
    private val to = 2_000L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), WavdropDatabase::class.java)
            .allowMainThreadQueries().build()
        songDao = db.songDao()
        eventDao = db.trackListenEventDao()
    }

    @After
    fun tearDown() = db.close()

    private fun song(id: Long) = SongEntity(
        id = id, title = "S$id", artist = "A", album = "B", albumId = 0L, duration = 1L,
        uri = "content://media/$id", dateAdded = 0L, trackNumber = 0, year = 2020,
    )

    private fun ev(song: Long, type: String, at: Long) = TrackListenEventEntity(
        songId = song, eventType = type, occurredAt = at, listenedMs = 1L, durationMs = 10L,
        source = TrackListenEventEntity.SOURCE_WAVDROP_PLAYBACK,
    )

    private suspend fun rows() = eventDao.observeHomeWrappedActivity(from, to).first()

    @Test fun liveRowsCarryPerSongCountsAndTheAllEventPlayTotalIncludingOrphans() = runBlocking {
        songDao.upsertAll(listOf(song(1), song(2)))
        eventDao.insertAll(
            listOf(
                ev(2, "PLAY", from), ev(2, "SKIP", 1_500), ev(1, "PLAY", to), ev(1, "PLAY", 1_200), // inclusive bounds
                ev(99, "PLAY", 1_300), ev(99, "SKIP", 1_300),          // orphan: PLAY counts in the total only
                ev(1, "PAUSE", 1_400), ev(1, "FUTURE", 1_400),         // unsupported types excluded
                ev(1, "PLAY", from - 1), ev(1, "PLAY", to + 1), ev(2, "SKIP", to + 1), // outside the range
            ),
        )
        assertEquals(
            listOf(HomeWrappedSongActivity(1, 2, 0, 4, 2_000L), HomeWrappedSongActivity(2, 1, 1, 4, 1_000L)),
            rows(),
        )
    }

    @Test fun skipOnlyLiveActivityProducesARowWithZeroPlays() = runBlocking {
        songDao.upsertAll(listOf(song(1)))
        eventDao.insert(ev(1, "SKIP", 1_500))
        assertEquals(listOf(HomeWrappedSongActivity(1, 0, 1, 0, null)), rows())
    }

    @Test fun orphanOnlyHistoryAndEmptyRangeProduceNoRows() = runBlocking {
        songDao.upsertAll(listOf(song(1)))
        eventDao.insertAll(listOf(ev(99, "PLAY", 1_500), ev(98, "SKIP", 1_500)))
        assertEquals(emptyList<HomeWrappedSongActivity>(), rows())
        assertEquals(emptyList<HomeWrappedSongActivity>(), eventDao.observeHomeWrappedActivity(5_000, 6_000).first())
    }

    @Test fun deletingTheSongTurnsItsHistoryIntoOrphanActivityAndKeepsItInTheTotal() = runBlocking {
        songDao.upsertAll(listOf(song(1), song(2)))
        eventDao.insertAll(listOf(ev(1, "PLAY", 1_100), ev(1, "PLAY", 1_200), ev(2, "PLAY", 1_300)))
        assertEquals(listOf(HomeWrappedSongActivity(1, 2, 0, 3, 1_200L), HomeWrappedSongActivity(2, 1, 0, 3, 1_300L)), rows())
        songDao.deleteSong(1)
        assertEquals(listOf(HomeWrappedSongActivity(2, 1, 0, 3, 1_300L)), rows())
        songDao.deleteSong(2)
        assertEquals(emptyList<HomeWrappedSongActivity>(), rows())
    }

    @Test fun theQueryReEmitsOnNewEventsAndOnSongChanges() = runBlocking {
        songDao.upsertAll(listOf(song(1)))
        eventDao.insert(ev(1, "PLAY", 1_100))
        val flow = eventDao.observeHomeWrappedActivity(from, to)

        val sawSecondPlay = async { flow.first { r -> r.singleOrNull()?.playCount == 2 } }
        eventDao.insert(ev(1, "PLAY", 1_200))
        assertEquals(2, withTimeout(5_000) { sawSecondPlay.await() }.single().totalPlayCount)

        val sawOrphanTotal = async { flow.first { r -> r.singleOrNull()?.totalPlayCount == 3 } }
        eventDao.insert(ev(77, "PLAY", 1_300)) // an orphan insert changes the total but creates no row
        assertEquals(1, withTimeout(5_000) { sawOrphanTotal.await() }.size)

        val sawNewSong = async { flow.first { r -> r.size == 2 } }
        songDao.upsertAll(listOf(song(77))) // the formerly-orphan history becomes live
        assertEquals(setOf(1L, 77L), withTimeout(5_000) { sawNewSong.await() }.map { it.songId }.toSet())
    }
}
