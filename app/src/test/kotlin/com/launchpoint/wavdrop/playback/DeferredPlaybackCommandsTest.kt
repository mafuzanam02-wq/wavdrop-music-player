package com.launchpoint.wavdrop.playback

import com.launchpoint.wavdrop.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure policy for the bounded latest-wins deferred command authority. No Android, no Media3. */
class DeferredPlaybackCommandsTest {

    private fun song(id: Long) = Song(
        id = id, title = "T$id", artist = "A", album = "B", albumId = 0L, duration = 200_000L,
        uri = "content://media/$id", dateAdded = 0L, trackNumber = 0, year = 2020,
    )

    private fun lib(index: Int = 0, songId: Long = 1L) = { selection: Long -> DeferredStart.LibraryQueue(selection, index, songId) }
    private fun search(index: Int = 0, songId: Long = 2L) = { selection: Long -> DeferredStart.PreserveSearch(selection, index, songId) }
    private fun external(songId: Long = 3L) = { selection: Long -> DeferredStart.ExternalUri(selection, songId, "content://ext/$songId", "x") }

    private val kinds = mapOf<String, (Long) -> DeferredStart>(
        "library" to lib(index = 4, songId = 40L),
        "search" to search(index = 5, songId = 50L),
        "external" to external(songId = 60L),
    )

    @Test fun startsAreMonotonicAndNeverReused() {
        val commands = DeferredPlaybackCommands()
        val first = commands.acceptStart(lib())
        commands.take()
        commands.clear()
        val second = commands.acceptStart(lib())
        assertTrue(second.selectionId > first.selectionId)
    }

    @Test fun everyOrderedPairOfStartTypesLetsTheNewestWin() {
        for ((firstName, first) in kinds) for ((secondName, second) in kinds) {
            val commands = DeferredPlaybackCommands()
            val a = commands.acceptStart(first)
            val b = commands.acceptStart(second)
            val drained = commands.take()
            assertSame("$firstName then $secondName: newest start wins", b, drained.start)
            assertNotEquals("$firstName then $secondName", a.selectionId, drained.start!!.selectionId)
            assertEquals(b::class, drained.start!!::class)
        }
    }

    @Test fun threeDifferentTypesLeaveOnlyTheLast() {
        val commands = DeferredPlaybackCommands()
        commands.acceptStart(lib())
        commands.acceptStart(external())
        val c = commands.acceptStart(search(index = 7, songId = 70L))
        val drained = commands.take()
        assertSame(c, drained.start)
        assertTrue(commands.isEmpty)
    }

    @Test fun aNewStartDiscardsEveryOlderTransportPayload() {
        val commands = DeferredPlaybackCommands()
        commands.acceptStart(lib())
        commands.capturePlayWhenReady(false)
        commands.captureSeek(PendingSeek(queueGeneration = 1, playbackIndex = 0, targetSongId = 1L, positionMs = 5_000L))
        commands.captureNavigation(PendingNavigation(NavigationIntent.NEXT, 0, 1L))
        commands.captureQueueJump(songId = 1L, playbackIndex = 0)
        val newer = commands.acceptStart(search())
        val drained = commands.take()
        assertSame(newer, drained.start)
        assertNull(drained.playWhenReady)
        assertNull(drained.seek)
        assertNull(drained.navigation)
        assertNull(drained.queueJump)
    }

    @Test fun startThenPauseThenPlayKeepsTheLatestIntent() {
        val commands = DeferredPlaybackCommands()
        commands.acceptStart(lib())
        commands.capturePlayWhenReady(false)
        assertEquals(false, commands.playWhenReady)
        commands.capturePlayWhenReady(true)
        assertEquals(true, commands.take().playWhenReady)
    }

    @Test fun intendedStateDerivesFromExplicitIntentThenPendingStartThenCurrent() {
        val commands = DeferredPlaybackCommands()
        assertFalse(commands.intendedPlayWhenReady(currentlyPlaying = false))
        assertTrue(commands.intendedPlayWhenReady(currentlyPlaying = true))
        commands.acceptStart(lib())
        assertTrue("a pending start implies play even when the state says paused", commands.intendedPlayWhenReady(currentlyPlaying = false))
        commands.capturePlayWhenReady(false)
        assertFalse("explicit pause beats the implied start play", commands.intendedPlayWhenReady(currentlyPlaying = true))
    }

    @Test fun queueJumpAfterAStartRetargetsTheStartAndDropsOlderRelativeCommands() {
        val commands = DeferredPlaybackCommands()
        val start = commands.acceptStart(lib(index = 1, songId = 11L))
        commands.captureSeek(PendingSeek(1, 1, 11L, 9_000L))
        commands.captureNavigation(PendingNavigation(NavigationIntent.NEXT, 1, 11L))
        commands.capturePlayWhenReady(false)

        assertEquals(JumpCapture.RetargetedStart, commands.captureQueueJump(songId = 33L, playbackIndex = 3))
        val drained = commands.take()

        assertEquals(start.selectionId, drained.start!!.selectionId)
        assertEquals(3, drained.start!!.startPlaybackIndex)
        assertEquals(33L, drained.start!!.startSongId)
        assertNull(drained.queueJump)
        assertNull(drained.seek)
        assertNull(drained.navigation)
        assertEquals("the play/pause intent survives a jump", false, drained.playWhenReady)
    }

    @Test fun retargetingKeepsTheStartKindAndAnExternalOneItemQueueHasOneOccurrence() {
        val commands = DeferredPlaybackCommands()
        commands.acceptStart(search(index = 1, songId = 5L))
        commands.captureQueueJump(songId = 9L, playbackIndex = 2)
        assertTrue(commands.take().start is DeferredStart.PreserveSearch)

        val ext = commands.acceptStart(external(songId = 60L))
        commands.captureQueueJump(songId = 9L, playbackIndex = 2)
        val drained = commands.take()
        assertEquals(ext, drained.start)
        assertEquals(0, drained.start!!.startPlaybackIndex)
    }

    @Test fun queueJumpWithNoStartIsHeldAsASingleReplaceableSlot() {
        val commands = DeferredPlaybackCommands()
        assertEquals(JumpCapture.DeferredJump, commands.captureQueueJump(songId = 2L, playbackIndex = 1))
        assertEquals(JumpCapture.DeferredJump, commands.captureQueueJump(songId = 4L, playbackIndex = 3))
        val drained = commands.take()
        assertEquals(PendingQueueJump(songId = 4L, resolvedPlaybackIndex = 3), drained.queueJump)
        assertNull(drained.start)
    }

    @Test fun aJumpDropsAStaleSeekAndNavigationEvenWithoutAStart() {
        val commands = DeferredPlaybackCommands()
        commands.captureSeek(PendingSeek(1, 0, 1L, 1_000L))
        commands.captureNavigation(PendingNavigation(NavigationIntent.PREVIOUS, 0, 1L))
        commands.captureQueueJump(songId = 2L, playbackIndex = 1)
        val drained = commands.take()
        assertNull(drained.seek)
        assertNull(drained.navigation)
        assertEquals(2L, drained.queueJump?.songId)
    }

    @Test fun takeIsConsumeOnceAndClearEmptiesEverything() {
        val commands = DeferredPlaybackCommands()
        commands.acceptStart(lib())
        commands.capturePlayWhenReady(true)
        commands.captureSeek(PendingSeek(1, 0, 1L, 1L))
        commands.captureNavigation(PendingNavigation(NavigationIntent.NEXT, 0, 1L))
        assertFalse(commands.isEmpty)
        assertTrue(commands.take().start != null)
        assertTrue(commands.isEmpty)
        val second = commands.take()
        assertNull(second.start); assertNull(second.seek); assertNull(second.navigation); assertNull(second.playWhenReady); assertNull(second.queueJump)

        commands.acceptStart(lib())
        commands.clear()
        assertTrue(commands.isEmpty)
    }

    @Test fun memoryIsBoundedWhateverTheCommandCount() {
        val commands = DeferredPlaybackCommands()
        repeat(10_000) {
            commands.acceptStart(lib(index = it))
            commands.captureSeek(PendingSeek(1, 0, 1L, it.toLong()))
            commands.captureNavigation(PendingNavigation(NavigationIntent.NEXT, 0, 1L))
            commands.capturePlayWhenReady(it % 2 == 0)
        }
        val drained = commands.take()
        assertEquals(9_999L, drained.seek?.positionMs)
        assertEquals(9_999, drained.start?.startPlaybackIndex)
        assertTrue(commands.isEmpty)
    }

    @Test fun navigationAnchorExecutesOnlyAtTheSameOccurrence() {
        val queue = listOf(song(1), song(2), song(1), song(3)) // duplicate id 1 at 0 and 2
        val nav = capturePendingNavigation(NavigationIntent.NEXT, queue, currentPlaybackIndex = 2)!!
        assertEquals(2, nav.anchorPlaybackIndex)
        assertEquals(NavigationIntent.NEXT, resolvePendingNavigation(nav, queue, currentPlaybackIndex = 2))
        assertNull("the other occurrence of the same song is not the anchor", resolvePendingNavigation(nav, queue, currentPlaybackIndex = 0))
        assertNull("unresolvable current occurrence is never guessed", resolvePendingNavigation(nav, queue, currentPlaybackIndex = null))
        assertNull("anchor index now holds a different song", resolvePendingNavigation(nav, listOf(song(1), song(2), song(9), song(3)), currentPlaybackIndex = 2))
    }

    @Test fun navigationCaptureRefusesAnUnprovableOccurrence() {
        val queue = listOf(song(1), song(2))
        assertNull(capturePendingNavigation(NavigationIntent.NEXT, queue, currentPlaybackIndex = null))
        assertNull(capturePendingNavigation(NavigationIntent.NEXT, queue, currentPlaybackIndex = 5))
        assertNull(capturePendingNavigation(NavigationIntent.NEXT, emptyList(), currentPlaybackIndex = 0))
    }

    // ---- exact occurrence resolution

    private fun install(i: Int) = DeferredStartResolution.Install(i)

    @Test fun unchangedGenerationUsesTheRecordedOccurrenceEvenWithDuplicates() {
        val queue = listOf(song(1), song(2), song(1))
        val cmd = DeferredStart.LibraryQueue(1, startPlaybackIndex = 2, startSongId = 1L)
        assertEquals(install(2), resolveDeferredStart(cmd, recordedQueueGeneration = 5, currentQueueGeneration = 5, playbackQueue = queue))
    }

    @Test fun unchangedGenerationRejectsAnIndexThatNoLongerHoldsTheSelectedSong() {
        val cmd = DeferredStart.LibraryQueue(1, startPlaybackIndex = 1, startSongId = 1L)
        assertTrue(resolveDeferredStart(cmd, 5, 5, listOf(song(1), song(2))) is DeferredStartResolution.Reject)
        assertTrue(resolveDeferredStart(cmd, 5, 5, listOf(song(1))) is DeferredStartResolution.Reject)
    }

    @Test fun changedGenerationIsRejectedEvenWhenTheSongStillOccursExactlyOnce() {
        val cmd = DeferredStart.PreserveSearch(1, startPlaybackIndex = 3, startSongId = 4L)
        assertTrue("one matching id does not prove occurrence continuity", resolveDeferredStart(cmd, 5, 6, listOf(song(1), song(4), song(2))) is DeferredStartResolution.Reject)
        assertTrue(resolveDeferredStart(cmd, 5, 6, listOf(song(1), song(2), song(3), song(4))) is DeferredStartResolution.Reject)
    }

    @Test fun changedGenerationRejectsDuplicatesAbsentSongsAndReinsertedIds() {
        val cmd = DeferredStart.LibraryQueue(1, startPlaybackIndex = 2, startSongId = 1L)
        assertTrue(resolveDeferredStart(cmd, 5, 6, listOf(song(1), song(1))) is DeferredStartResolution.Reject)
        assertTrue(resolveDeferredStart(cmd, 5, 6, listOf(song(2), song(3))) is DeferredStartResolution.Reject)
        assertTrue("[A(first), B] after the selected A was removed", resolveDeferredStart(cmd, 5, 6, listOf(song(1), song(2))) is DeferredStartResolution.Reject)
        assertTrue("same id reinserted at the recorded index", resolveDeferredStart(cmd, 5, 6, listOf(song(2), song(3), song(1))) is DeferredStartResolution.Reject)
    }
    @Test fun aRetargetRefreshesTheRecordedGeneration() {
        val commands = DeferredPlaybackCommands()
        commands.acceptStart(3L, lib(index = 0, songId = 1L))
        assertEquals(3L, commands.startQueueGeneration)
        commands.captureQueueJump(songId = 2L, playbackIndex = 1, queueGeneration = 7L)
        val drained = commands.take()
        assertEquals(7L, drained.startQueueGeneration)
        assertEquals(1, drained.start!!.startPlaybackIndex)
    }}
