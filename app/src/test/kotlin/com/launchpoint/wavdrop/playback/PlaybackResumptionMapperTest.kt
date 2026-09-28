package com.launchpoint.wavdrop.playback

import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.playback.PlaybackSessionSnapshot
import com.launchpoint.wavdrop.data.settings.ResumeBehaviorSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackResumptionMapperTest {
    private val songs = listOf(song(1), song(2), song(3), song(4))
    private val settings = ResumeBehaviorSettings()

    @Test
    fun `normal queue restores exact item and position`() {
        val plan = ready(snapshot(currentSongId = 2, currentIndex = 1, positionMs = 42_000))
        val media3 = plan.toMedia3Resumption()
        assertEquals(listOf(1L, 2L, 3L), plan.playbackQueue.map { it.id })
        assertEquals(listOf("1", "2", "3"), media3.mediaItems.map { it.mediaId })
        assertEquals(1, media3.startIndex)
        assertEquals(42_000L, media3.startPositionMs)
    }

    @Test
    fun `duplicate queue restores second occurrence`() {
        val plan = ready(
            snapshot(queueIds = listOf(1, 2, 1, 3), currentSongId = 1, currentIndex = 2),
        )
        assertEquals(2, plan.currentLibraryIndex)
        assertEquals(2, plan.startPlaybackIndex)
    }

    @Test
    fun `shuffled duplicate queue maps second occurrence to playback index zero`() {
        val plan = ready(
            snapshot(
                queueIds = listOf(1, 2, 1, 3),
                playbackOrder = listOf(2, 3, 0, 1),
                currentSongId = 1,
                currentIndex = 2,
                shuffleEnabled = true,
            )
        )
        assertEquals(listOf(1L, 3L, 1L, 2L), plan.playbackQueue.map { it.id })
        assertEquals(0, plan.startPlaybackIndex)
        assertEquals(2, plan.currentLibraryIndex)
    }

    @Test
    fun `remember position false starts at zero`() {
        val result = PlaybackResumptionMapper.map(
            snapshot = snapshot(positionMs = 90_000),
            settings = settings.copy(rememberPosition = false),
            availableSongs = songs,
        )
        assertEquals(0L, (result as PlaybackResumptionResult.Ready).plan.startPositionMs)
    }

    @Test
    fun `no saved session has no resumption`() {
        assertUnavailable(
            PlaybackResumptionFailure.NO_SAVED_SESSION,
            PlaybackResumptionMapper.map(null, settings, songs),
        )
    }

    @Test
    fun `remember last track false has no resumption`() {
        assertUnavailable(
            PlaybackResumptionFailure.DISABLED_BY_SETTINGS,
            PlaybackResumptionMapper.map(
                snapshot(),
                settings.copy(rememberLastTrack = false),
                songs,
            ),
        )
    }

    @Test
    fun `saved current song missing fails conservatively`() {
        assertUnavailable(
            PlaybackResumptionFailure.SAVED_QUEUE_UNAVAILABLE,
            PlaybackResumptionMapper.map(snapshot(), settings, listOf(song(1), song(3))),
        )
    }

    @Test
    fun `invalid playback order uses existing validated fallback`() {
        val plan = ready(
            snapshot(playbackOrder = listOf(0, 0, 2), currentSongId = 2, currentIndex = 1),
        )
        assertEquals(listOf(0, 1, 2), plan.playbackOrder)
        assertEquals(1, plan.startPlaybackIndex)
    }

    @Test
    fun `old session without playback order remains compatible`() {
        val plan = ready(snapshot(playbackOrder = null, currentSongId = 2, currentIndex = 1))
        assertEquals(listOf(0, 1, 2), plan.playbackOrder)
        assertEquals(1, plan.startPlaybackIndex)
    }

    @Test
    fun `restore queue false produces current item only`() {
        val result = PlaybackResumptionMapper.map(
            snapshot = snapshot(currentSongId = 2, currentIndex = 1),
            settings = settings.copy(restoreQueue = false),
            availableSongs = songs,
        )
        val plan = (result as PlaybackResumptionResult.Ready).plan
        assertEquals(listOf(2L), plan.playbackQueue.map { it.id })
        assertEquals(0, plan.startPlaybackIndex)
    }

    @Test
    fun `repeat shuffle source URI and media item metadata remain coherent`() {
        val plan = ready(
            snapshot(
                playbackOrder = listOf(1, 0, 2),
                currentSongId = 2,
                currentIndex = 1,
                repeatMode = RepeatMode.ALL,
                shuffleEnabled = true,
            )
        )
        val item = plan.mediaItems.first()
        assertEquals(RepeatMode.ALL, plan.repeatMode)
        assertTrue(plan.shuffleEnabled)
        assertEquals("content://media/2", plan.playbackQueue.first().uri)
        assertEquals(20L, plan.playbackQueue.first().albumId)
        assertEquals("2", item.mediaId)
        assertEquals("Song 2", item.mediaMetadata.title)
        assertEquals("Artist 2", item.mediaMetadata.artist)
        assertEquals("Album 2", item.mediaMetadata.albumTitle)
    }

    private fun ready(snapshot: PlaybackSessionSnapshot): PlaybackResumptionPlan =
        (PlaybackResumptionMapper.map(snapshot, settings, songs) as PlaybackResumptionResult.Ready).plan

    private fun assertUnavailable(
        expected: PlaybackResumptionFailure,
        actual: PlaybackResumptionResult,
    ) {
        assertEquals(expected, (actual as PlaybackResumptionResult.Unavailable).reason)
    }

    private fun snapshot(
        queueIds: List<Long> = listOf(1, 2, 3),
        playbackOrder: List<Int>? = listOf(0, 1, 2),
        currentSongId: Long? = 1,
        currentIndex: Int = 0,
        positionMs: Long = 12_000,
        repeatMode: RepeatMode = RepeatMode.OFF,
        shuffleEnabled: Boolean = false,
    ) = PlaybackSessionSnapshot(
        queueSongIds = queueIds,
        playbackOrder = playbackOrder,
        currentSongId = currentSongId,
        currentIndex = currentIndex,
        positionMs = positionMs,
        repeatMode = repeatMode,
        shuffleEnabled = shuffleEnabled,
        updatedAtMs = 1,
    )

    private fun song(id: Long) = Song(
        id = id,
        title = "Song $id",
        artist = "Artist $id",
        album = "Album $id",
        albumId = id * 10,
        duration = 180_000,
        uri = "content://media/$id",
        dateAdded = 0,
        trackNumber = 0,
        year = 2024,
    )
}
