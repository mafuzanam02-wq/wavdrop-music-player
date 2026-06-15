package com.launchpoint.wavdrop.data.playback

import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.settings.ResumeBehaviorSettings
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

    @Test
    fun `parsePlaybackOrder valid CSV returns indexes`() {
        assertEquals(listOf(0, 3, 1, 2), PlaybackSessionRules.parsePlaybackOrder("0,3,1,2"))
    }

    @Test
    fun `parsePlaybackOrder filters non-numeric entries`() {
        assertEquals(listOf(0, 2), PlaybackSessionRules.parsePlaybackOrder("0,nope,2"))
    }

    @Test
    fun `parsePlaybackOrder blank string returns empty list`() {
        assertEquals(emptyList<Int>(), PlaybackSessionRules.parsePlaybackOrder(" "))
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

    @Test
    fun `validatePlaybackOrder accepts full queue index permutation`() {
        assertEquals(
            listOf(2, 0, 3, 1),
            PlaybackSessionRules.validatePlaybackOrder(listOf(2, 0, 3, 1), queueSize = 4),
        )
    }

    @Test
    fun `validatePlaybackOrder rejects duplicate indexes`() {
        assertNull(PlaybackSessionRules.validatePlaybackOrder(listOf(0, 1, 1), queueSize = 3))
    }

    @Test
    fun `validatePlaybackOrder rejects mismatched size`() {
        assertNull(PlaybackSessionRules.validatePlaybackOrder(listOf(0, 1), queueSize = 3))
    }

    @Test
    fun `validatePlaybackOrder rejects out of range indexes`() {
        assertNull(PlaybackSessionRules.validatePlaybackOrder(listOf(0, 1, 3), queueSize = 3))
    }

    @Test
    fun `restorePlaybackOrder with shuffle uses saved effective order when valid`() {
        val result = PlaybackSessionRules.restorePlaybackOrder(
            savedPlaybackOrder = listOf(1, 3, 2, 0),
            queueSize = 4,
            currentQueueIndex = 1,
            shuffleEnabled = true,
        )

        assertEquals(listOf(1, 3, 2, 0), result)
    }

    @Test
    fun `restorePlaybackOrder preserves search preserve queue continuation order`() {
        val queue = listOf(song(99), song(3), song(4))
        val order = PlaybackSessionRules.restorePlaybackOrder(
            savedPlaybackOrder = listOf(0, 1, 2),
            queueSize = queue.size,
            currentQueueIndex = 0,
            shuffleEnabled = true,
        )
        val restoredQueueIds = order.map { queue[it].id }

        assertEquals(listOf(99L, 3L, 4L), restoredQueueIds)
    }

    @Test
    fun `restorePlaybackOrder ignores invalid saved order and keeps non-shuffle identity behavior`() {
        val result = PlaybackSessionRules.restorePlaybackOrder(
            savedPlaybackOrder = listOf(0, 0, 1),
            queueSize = 3,
            currentQueueIndex = 1,
            shuffleEnabled = false,
        )

        assertEquals(listOf(0, 1, 2), result)
    }

    @Test
    fun `restorePlaybackOrder with no saved order keeps existing non-shuffle behavior`() {
        val result = PlaybackSessionRules.restorePlaybackOrder(
            savedPlaybackOrder = null,
            queueSize = 4,
            currentQueueIndex = 2,
            shuffleEnabled = false,
        )

        assertEquals(listOf(0, 1, 2, 3), result)
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

    // ── applyResumeBehavior ───────────────────────────────────────────────────

    private fun snapshot(
        queueIds: List<Long> = listOf(1L, 2L, 3L),
        playbackOrder: List<Int>? = listOf(1, 0, 2),
        currentSongId: Long? = 2L,
        currentIndex: Int = 1,
        positionMs: Long = 45_000L,
    ) = PlaybackSessionSnapshot(
        queueSongIds   = queueIds,
        playbackOrder  = playbackOrder,
        currentSongId  = currentSongId,
        currentIndex   = currentIndex,
        positionMs     = positionMs,
        repeatMode     = RepeatMode.OFF,
        shuffleEnabled = false,
        updatedAtMs    = 0L,
    )

    private val allOn = ResumeBehaviorSettings(
        rememberLastTrack = true,
        rememberPosition  = true,
        restoreQueue      = true,
    )

    @Test
    fun `applyResumeBehavior all defaults returns snapshot unchanged`() {
        val s = snapshot()
        val result = PlaybackSessionRules.applyResumeBehavior(s, allOn)
        assertEquals(s, result)
    }

    @Test
    fun `applyResumeBehavior rememberLastTrack false returns null`() {
        val settings = allOn.copy(rememberLastTrack = false)
        assertNull(PlaybackSessionRules.applyResumeBehavior(snapshot(), settings))
    }

    @Test
    fun `applyResumeBehavior rememberPosition false zeroes position`() {
        val settings = allOn.copy(rememberPosition = false)
        val result = PlaybackSessionRules.applyResumeBehavior(snapshot(positionMs = 90_000L), settings)!!
        assertEquals(0L, result.positionMs)
    }

    @Test
    fun `applyResumeBehavior rememberPosition false keeps queue and song intact`() {
        val settings = allOn.copy(rememberPosition = false)
        val s = snapshot()
        val result = PlaybackSessionRules.applyResumeBehavior(s, settings)!!
        assertEquals(s.queueSongIds,  result.queueSongIds)
        assertEquals(s.currentSongId, result.currentSongId)
        assertEquals(s.currentIndex,  result.currentIndex)
    }

    @Test
    fun `applyResumeBehavior restoreQueue false collapses queue to current song by id`() {
        val settings = allOn.copy(restoreQueue = false)
        val result = PlaybackSessionRules.applyResumeBehavior(snapshot(), settings)!!
        assertEquals(listOf(2L), result.queueSongIds)
        assertNull(result.playbackOrder)
        assertEquals(0, result.currentIndex)
    }

    @Test
    fun `applyResumeBehavior restoreQueue false preserves position and song id`() {
        val settings = allOn.copy(restoreQueue = false)
        val result = PlaybackSessionRules.applyResumeBehavior(snapshot(positionMs = 30_000L), settings)!!
        assertEquals(2L, result.currentSongId)
        assertEquals(30_000L, result.positionMs)
    }

    @Test
    fun `applyResumeBehavior restoreQueue false with null currentSongId falls back to index`() {
        val s = snapshot(queueIds = listOf(10L, 20L, 30L), currentSongId = null, currentIndex = 2)
        val settings = allOn.copy(restoreQueue = false)
        val result = PlaybackSessionRules.applyResumeBehavior(s, settings)!!
        assertEquals(listOf(30L), result.queueSongIds)
        assertNull(result.playbackOrder)
        assertEquals(0, result.currentIndex)
    }

    @Test
    fun `applyResumeBehavior restoreQueue false and rememberPosition false both applied`() {
        val settings = allOn.copy(restoreQueue = false, rememberPosition = false)
        val result = PlaybackSessionRules.applyResumeBehavior(snapshot(positionMs = 60_000L), settings)!!
        assertEquals(listOf(2L), result.queueSongIds)
        assertEquals(0L, result.positionMs)
    }

    @Test
    fun `applyResumeBehavior rememberLastTrack false ignores other settings`() {
        val settings = ResumeBehaviorSettings(
            rememberLastTrack = false,
            rememberPosition  = true,
            restoreQueue      = true,
        )
        assertNull(PlaybackSessionRules.applyResumeBehavior(snapshot(), settings))
    }
}
