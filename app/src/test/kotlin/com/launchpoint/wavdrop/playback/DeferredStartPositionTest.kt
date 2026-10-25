package com.launchpoint.wavdrop.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The deferred-command bookkeeping for the position a retargeting index-and-position seek carries. */
class DeferredStartPositionTest {

    private fun library(index: Int = 1, songId: Long = 11L) = DeferredStart.LibraryQueue(selectionId = 1L, startPlaybackIndex = index, startSongId = songId)

    @Test fun retargetingAStartCarriesTheRequestedPositionAndTheTargetTogether() {
        val c = DeferredPlaybackCommands()
        c.acceptStart { library() }
        assertEquals(JumpCapture.RetargetedStart, c.captureQueueJump(songId = 99L, playbackIndex = 20_000, queueGeneration = 5L, positionMs = 45_000L))
        val start = c.take().start!!
        assertEquals(20_000, start.startPlaybackIndex)
        assertEquals(99L, start.startSongId)
        assertEquals(45_000L, start.startPositionMs)
    }

    @Test fun anOrdinaryJumpAndANewerZeroPositionReplaceAnEarlierPosition() {
        val c = DeferredPlaybackCommands()
        c.acceptStart { library() }
        c.captureQueueJump(songId = 2L, playbackIndex = 5, positionMs = 30_000L)
        c.captureQueueJump(songId = 3L, playbackIndex = 6) // ordinary: no position
        assertEquals(0L, c.take().start!!.startPositionMs)
        c.acceptStart { library() }
        c.captureQueueJump(songId = 2L, playbackIndex = 5, positionMs = 30_000L)
        c.captureQueueJump(songId = 3L, playbackIndex = 6, positionMs = 0L)
        assertEquals(0L, c.take().start!!.startPositionMs)
    }

    @Test fun successiveSeeksLatestWins() {
        val c = DeferredPlaybackCommands()
        c.acceptStart { library() }
        c.captureQueueJump(songId = 2L, playbackIndex = 10, positionMs = 1_000L)
        c.captureQueueJump(songId = 3L, playbackIndex = 20, positionMs = 2_000L)
        c.captureQueueJump(songId = 4L, playbackIndex = 30, positionMs = 3_000L)
        val s = c.take().start!!
        assertEquals(30, s.startPlaybackIndex)
        assertEquals(3_000L, s.startPositionMs)
    }

    @Test fun aRebaseAcrossAKnownEditKeepsThePositionAndRemovalCancelsTheStart() {
        val c = DeferredPlaybackCommands()
        c.acceptStart(3L) { library() }
        c.captureQueueJump(songId = 9L, playbackIndex = 40, queueGeneration = 3L, positionMs = 12_000L)
        assertTrue(c.rebaseStart(generationBeforeEdit = 3L, generationAfterEdit = 4L, newIndex = 41))
        val s = c.start!!
        assertEquals(41, s.startPlaybackIndex)
        assertEquals(12_000L, s.startPositionMs)
        assertTrue(c.rebaseStart(4L, 5L, newIndex = null))
        assertNull(c.start)
    }

    @Test fun aNewerStartDoesNotInheritAnOlderRetargetedPosition() {
        val c = DeferredPlaybackCommands()
        c.acceptStart { library() }
        c.captureQueueJump(songId = 2L, playbackIndex = 5, positionMs = 30_000L)
        val newer = c.acceptStart { DeferredStart.LibraryQueue(it, 7, 7L) }
        val s = c.take().start!!
        assertEquals(newer.selectionId, s.selectionId)
        assertEquals(0L, s.startPositionMs)
    }

    @Test fun anExternalOneItemStartOnlyChangesItsPosition() {
        val c = DeferredPlaybackCommands()
        c.acceptStart { DeferredStart.ExternalUri(selectionId = it, startSongId = 5L, uri = "content://x", displayName = null) }
        c.captureQueueJump(songId = 5L, playbackIndex = 0, positionMs = 8_000L)
        val s = c.take().start as DeferredStart.ExternalUri
        assertEquals(0, s.startPlaybackIndex)
        assertEquals(8_000L, s.startPositionMs)
    }
}
