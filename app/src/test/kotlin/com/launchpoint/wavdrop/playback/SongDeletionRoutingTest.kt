package com.launchpoint.wavdrop.playback

import com.launchpoint.wavdrop.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Test

/** Deletion routing is decided by the resolved current occurrence, never by stale Now Playing state. */
class SongDeletionRoutingTest {

    private fun song(id: Long, tag: Long = 0L) = Song(
        id = id, title = "S$id", artist = "Artist", album = "Album",
        albumId = 0L, duration = 200_000L, uri = "content://media/$id/$tag",
        dateAdded = tag, trackNumber = 0, year = 2020,
    )

    private val queue = listOf(song(1, 0), song(2, 1), song(1, 2))

    @Test fun deletedIdMatchingResolvedCurrentRoutesAsCurrent() =
        assertEquals(SongDeletionRoute.Current, routeSongDeletion(queue, 1, 2L))

    @Test fun deletedIdNotMatchingResolvedCurrentRoutesAsNonCurrent() =
        assertEquals(SongDeletionRoute.NonCurrent, routeSongDeletion(queue, 1, 1L))

    @Test fun unresolvedCurrentOccurrenceFailsClosed() {
        assertEquals(SongDeletionRoute.Unresolved, routeSongDeletion(queue, null, 2L))
        assertEquals(SongDeletionRoute.Unresolved, routeSongDeletion(queue, 9, 2L))
        assertEquals(SongDeletionRoute.Unresolved, routeSongDeletion(queue, -1, 2L))
        assertEquals(SongDeletionRoute.Unresolved, routeSongDeletion(emptyList(), 0, 2L))
    }

    @Test fun duplicateIdsRouteByPositionNotFirstMatch() {
        // Id 1 sits at positions 0 and 2; the resolved occurrence decides, and only its id is compared.
        assertEquals(SongDeletionRoute.Current, routeSongDeletion(queue, 2, 1L))
        assertEquals(SongDeletionRoute.Current, routeSongDeletion(queue, 0, 1L))
        assertEquals(SongDeletionRoute.NonCurrent, routeSongDeletion(queue, 1, 1L))
    }

    @Test fun resolvedCurrentWinsWhenNowPlayingStateIsStale() {
        // Media3 already advanced to position 1 (song 2) while Now Playing still says song 1 at index 0.
        val resolved = resolveCurrentPlaybackIndex(
            playbackQueue = queue,
            controllerIndex = 1,
            controllerSongId = 2L,
            stateIndex = 0,
            stateSongId = 1L,
            playerQueueNeedsSync = false,
        )
        assertEquals(1, resolved)
        // Deleting song 1 must route as NON-current (the resolved current is song 2), and deleting song 2
        // as current - the opposite of what the stale state would have said.
        assertEquals(SongDeletionRoute.NonCurrent, routeSongDeletion(queue, resolved, 1L))
        assertEquals(SongDeletionRoute.Current, routeSongDeletion(queue, resolved, 2L))
    }
}
