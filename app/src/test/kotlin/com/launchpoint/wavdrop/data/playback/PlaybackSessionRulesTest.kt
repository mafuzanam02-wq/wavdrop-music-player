package com.launchpoint.wavdrop.data.playback

import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.playback.RepeatMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackSessionRulesTest {

    private fun song(id: Long) = Song(
        id = id, title = "T$id", artist = "A", album = "B",
        albumId = 0L, duration = 180_000L, uri = "content://media/$id",
        dateAdded = 0L, trackNumber = 0, year = 2020,
    )

    // ── parseQueueIds ─────────────────────────────────────────────────────────

    @Test
    fun `parseQueueIds blank string returns empty list`() {
        assertEquals(emptyList<Long>(), PlaybackSessionRules.parseQueueIds(""))
        assertEquals(emptyList<Long>(), PlaybackSessionRules.parseQueueIds("   "))
    }

    @Test
    fun `parseQueueIds valid CSV returns longs`() {
        assertEquals(listOf(1L, 2L, 3L), PlaybackSessionRules.parseQueueIds("1,2,3"))
    }

    @Test
    fun `parseQueueIds filters out non-numeric entries`() {
        assertEquals(listOf(1L, 3L), PlaybackSessionRules.parseQueueIds("1,abc,3"))
    }

    @Test
    fun `parseQueueIds handles single id`() {
        assertEquals(listOf(42L), PlaybackSessionRules.parseQueueIds("42"))
    }

    @Test
    fun `parseQueueIds trims whitespace around values`() {
        assertEquals(listOf(1L, 2L), PlaybackSessionRules.parseQueueIds("1, 2"))
    }

    // ── clampIndex ────────────────────────────────────────────────────────────

    @Test
    fun `clampIndex valid index is unchanged`() {
        assertEquals(2, PlaybackSessionRules.clampIndex(2, 5))
    }

    @Test
    fun `clampIndex too-high index clamps to last`() {
        assertEquals(4, PlaybackSessionRules.clampIndex(99, 5))
    }

    @Test
    fun `clampIndex negative index clamps to zero`() {
        assertEquals(0, PlaybackSessionRules.clampIndex(-1, 5))
    }

    @Test
    fun `clampIndex empty queue returns zero`() {
        assertEquals(0, PlaybackSessionRules.clampIndex(3, 0))
    }

    // ── parseRepeatMode ───────────────────────────────────────────────────────

    @Test
    fun `parseRepeatMode ALL returns ALL`() {
        assertEquals(RepeatMode.ALL, PlaybackSessionRules.parseRepeatMode("ALL"))
    }

    @Test
    fun `parseRepeatMode ONE returns ONE`() {
        assertEquals(RepeatMode.ONE, PlaybackSessionRules.parseRepeatMode("ONE"))
    }

    @Test
    fun `parseRepeatMode OFF returns OFF`() {
        assertEquals(RepeatMode.OFF, PlaybackSessionRules.parseRepeatMode("OFF"))
    }

    @Test
    fun `parseRepeatMode invalid string returns OFF`() {
        assertEquals(RepeatMode.OFF, PlaybackSessionRules.parseRepeatMode("garbage"))
    }

    @Test
    fun `parseRepeatMode null returns OFF`() {
        assertEquals(RepeatMode.OFF, PlaybackSessionRules.parseRepeatMode(null))
    }

    // ── resolveStartSong ──────────────────────────────────────────────────────

    @Test
    fun `resolveStartSong matches by id`() {
        val queue = listOf(song(1), song(2), song(3))
        val result = PlaybackSessionRules.resolveStartSong(
            sessionSongId = 2L,
            sessionIndex  = 0,
            mappedQueue   = queue,
        )
        assertEquals(2L, result?.id)
    }

    @Test
    fun `resolveStartSong id not found falls back to index`() {
        val queue = listOf(song(1), song(2), song(3))
        val result = PlaybackSessionRules.resolveStartSong(
            sessionSongId = 99L,
            sessionIndex  = 1,
            mappedQueue   = queue,
        )
        assertEquals(2L, result?.id)
    }

    @Test
    fun `resolveStartSong null id uses index`() {
        val queue = listOf(song(10), song(20))
        val result = PlaybackSessionRules.resolveStartSong(
            sessionSongId = null,
            sessionIndex  = 1,
            mappedQueue   = queue,
        )
        assertEquals(20L, result?.id)
    }

    @Test
    fun `resolveStartSong empty queue returns null`() {
        assertNull(
            PlaybackSessionRules.resolveStartSong(
                sessionSongId = 1L,
                sessionIndex  = 0,
                mappedQueue   = emptyList(),
            )
        )
    }
}
