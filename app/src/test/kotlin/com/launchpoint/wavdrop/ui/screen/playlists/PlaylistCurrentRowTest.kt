package com.launchpoint.wavdrop.ui.screen.playlists

import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.playback.NowPlayingState
import com.launchpoint.wavdrop.playback.PlaybackQueueSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaylistCurrentRowTest {
    private fun song(id: Long) = Song(
        id = id, title = "Song $id", artist = "Artist", album = "Album", albumId = 0L,
        duration = 1_000L, uri = "content://media/$id", dateAdded = 0L, trackNumber = 0, year = 2020,
    )

    /** Rows built from (songId, persisted position) pairs. */
    private fun rows(vararg pairs: Pair<Long, Int>) = pairs.map { (id, pos) ->
        PlaylistSongItem(playlistId = 42L, songId = id, position = pos, song = song(id))
    }

    private fun queue(vararg ids: Long) = ids.map(::song)

    private val a = 1L
    private val b = 2L
    private val c = 3L
    private val d = 4L
    private val x = 9L
    private val y = 8L

    private val abac = rows(a to 0, b to 1, a to 2, c to 3)

    @Test
    fun `second duplicate resolves only the second occurrence`() {
        assertEquals(2, resolve(abac, queue(a, b, a, c), 2))
    }

    @Test
    fun `first duplicate resolves only the first occurrence`() {
        assertEquals(0, resolve(abac, queue(a, b, a, c), 0))
    }

    @Test
    fun `three identical songs resolve the middle occurrence only`() {
        val rows = rows(a to 0, a to 1, a to 2)
        assertEquals(1, resolve(rows, queue(a, a, a), 1))
    }

    @Test
    fun `queue mismatch resolves nothing`() {
        assertNull(resolve(abac, queue(a, b, c), 0))
    }

    @Test
    fun `same current song but unrelated playback queue resolves nothing`() {
        assertNull(resolve(abac, queue(x, a, y), 1))
    }

    @Test
    fun `filtered playlist resolves the persisted position of the visible occurrence`() {
        // Full playlist A B A C D; visible (filtered) rows are A A D at positions 0, 2, 4.
        val visible = rows(a to 0, a to 2, d to 4)
        assertEquals(2, resolve(visible, queue(a, a, d), 1))
        assertEquals(4, resolve(visible, queue(a, a, d), 2))
    }

    @Test
    fun `filter changed after playback resolves nothing`() {
        // Playback started from A A D, but the visible rows are now the full playlist.
        val full = rows(a to 0, b to 1, a to 2, c to 3, d to 4)
        assertNull(resolve(full, queue(a, a, d), 1))
    }

    @Test
    fun `reordered or shuffled queue with same songs resolves nothing`() {
        assertNull(resolve(abac, queue(c, a, b, a), 1))
    }

    @Test
    fun `invalid current index resolves nothing`() {
        assertNull(resolve(abac, queue(a, b, a, c), -1))
        assertNull(resolve(abac, queue(a, b, a, c), 4))
    }

    @Test
    fun `empty queues resolve nothing`() {
        assertNull(resolve(emptyList(), queue(a), 0))
        assertNull(resolve(abac, emptyList(), 0))
        assertNull(resolve(emptyList(), emptyList(), 0))
    }

    // --- Provenance ------------------------------------------------------------------------------

    private val playlist42 = PlaybackQueueSource.Playlist(42L)

    private fun resolve(
        rows: List<PlaylistSongItem>,
        queue: List<Song>,
        index: Int,
        playlistId: Long = 42L,
        source: PlaybackQueueSource = playlist42,
    ): Int? = resolveCurrentPlaylistPosition(playlistId, source, rows, queue, index)

    @Test
    fun `same sequence but a different playlist source resolves nothing`() {
        assertNull(resolve(abac, queue(a, b, a, c), 2, source = PlaybackQueueSource.Playlist(99L)))
    }

    @Test
    fun `same sequence from a non playlist source resolves nothing`() {
        assertNull(resolve(abac, queue(a, b, a, c), 2, source = PlaybackQueueSource.Other))
    }

    @Test
    fun `correct playlist source resolves the second occurrence`() {
        assertEquals(2, resolve(abac, queue(a, b, a, c), 2, source = PlaybackQueueSource.Playlist(42L)))
    }

    @Test
    fun `queue source defaults to other so unidentified queues never match a playlist`() {
        assertEquals(PlaybackQueueSource.Other, NowPlayingState().queueSource)
        assertNull(resolve(abac, queue(a, b, a, c), 2, source = NowPlayingState().queueSource))
    }

    @Test
    fun `replacement by an unrelated queue drops playlist provenance`() {
        val playlistState = NowPlayingState(queue = queue(a, b, a, c), currentIndex = 2, queueSource = playlist42)
        assertEquals(2, resolve(abac, playlistState.queue, playlistState.currentIndex, source = playlistState.queueSource))
        // A queue-replacing operation without a trusted source sets Other explicitly.
        val replaced = playlistState.copy(queue = queue(a, b, a, c), queueSource = PlaybackQueueSource.Other)
        assertNull(resolve(abac, replaced.queue, replaced.currentIndex, source = replaced.queueSource))
    }

    @Test
    fun `append style updates that only change the queue do not relabel the source`() {
        val other = NowPlayingState(queue = queue(x, y), currentIndex = 0)
        // playAllNext / addAllToQueue only copy queue fields; the source stays Other.
        val appended = other.copy(queue = queue(x, a, b, a, c, y))
        assertEquals(PlaybackQueueSource.Other, appended.queueSource)
        assertNull(resolve(abac, appended.queue, 1, source = appended.queueSource))
    }
}
