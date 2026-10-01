package com.launchpoint.wavdrop.playback

import com.launchpoint.wavdrop.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Loop-boundary stats resolve the song via the shared occurrence resolver, never first-id-match. */
class LoopBoundaryOccurrenceTest {

    private fun song(id: Long, tag: Long = 0L) = Song(
        id = id, title = "S$id", artist = "Artist", album = "Album",
        albumId = 0L, duration = 200_000L, uri = "content://media/$id/$tag",
        dateAdded = tag, trackNumber = 0, year = 2020,
    )

    private val queue = listOf(song(1, 0), song(2, 1), song(1, 2), song(1, 3))

    private fun loopSong(
        controllerIndex: Int?,
        controllerSongId: Long?,
        stateIndex: Int? = null,
        stateSongId: Long? = null,
        dirty: Boolean = false,
    ): Song? = songAtResolvedPlaybackIndex(
        queue,
        resolveCurrentPlaybackIndex(queue, controllerIndex, controllerSongId, stateIndex, stateSongId, dirty),
    )

    @Test fun alignedControllerResolvesSecondDuplicateOccurrence() =
        assertEquals(2L, loopSong(2, 1L)?.dateAdded)

    @Test fun alignedControllerResolvesThirdDuplicateOccurrence() =
        assertEquals(3L, loopSong(3, 1L)?.dateAdded)

    @Test fun dirtyQueueFollowsLogicalStateAuthority() =
        assertEquals(
            3L,
            loopSong(controllerIndex = 0, controllerSongId = 1L, stateIndex = 3, stateSongId = 1L, dirty = true)?.dateAdded,
        )

    @Test fun ambiguousIdOnlyStateDoesNotChooseFirstDuplicate() =
        assertNull(loopSong(controllerIndex = null, controllerSongId = 1L, dirty = true))

    @Test fun unresolvedIndexYieldsNoSong() = assertNull(songAtResolvedPlaybackIndex(queue, null))

    @Test fun uniqueIdFallbackStillAllowed() =
        assertEquals(1L, loopSong(controllerIndex = null, controllerSongId = 2L, dirty = true)?.dateAdded)
}
