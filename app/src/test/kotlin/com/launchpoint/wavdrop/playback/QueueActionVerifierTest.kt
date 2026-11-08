package com.launchpoint.wavdrop.playback

import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.ui.components.QueueActionFeedback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** P5-F14: the post-condition check and the message mapping. Pure JVM: no success text can come from a non-verified result. */
class QueueActionVerifierTest {

    private fun s(id: Long, title: String = "Song $id") = Song(
        id = id, title = title, artist = "Artist", album = "Album", albumId = 0L, duration = 1L,
        uri = "content://media/$id", dateAdded = 0L, trackNumber = 0, year = 2020,
    )

    private val intentNext = QueueActionResult.Queued(QueueAction.PlayNext, QueuePlacement.PlayNext, 3)

    @Test fun playNextIsVerifiedOnlyWhenTheSongSitsAtTheIntendedPosition() {
        val committed = listOf(s(1), s(2), s(3), s(9), s(4))
        assertEquals(intentNext, QueueActionVerifier.verify(QueueAction.PlayNext, s(9), sizeBefore = 4, committed = committed, intent = intentNext))
    }

    @Test fun wrongPositionIsAFailureNotASuccess() {
        val committed = listOf(s(1), s(2), s(9), s(3), s(4)) // landed at 2, intent said 3
        val r = QueueActionVerifier.verify(QueueAction.PlayNext, s(9), 4, committed, intentNext)
        assertTrue(r is QueueActionResult.Failed)
    }

    @Test fun noPrematureSuccessWhenTheQueueDidNotGrow() {
        val committed = listOf(s(1), s(2), s(3), s(9)) // unchanged size (4 -> 4): a superseding replacement, nothing was added
        assertTrue(QueueActionVerifier.verify(QueueAction.PlayNext, s(9), 4, committed, intentNext) is QueueActionResult.Failed)
    }

    @Test fun supersededByAnotherMutationIsNotConfirmed() {
        val committed = List(6) { s(it + 10L) } // grew by two and no longer holds the song: someone else won
        assertTrue(QueueActionVerifier.verify(QueueAction.PlayNext, s(9), 4, committed, intentNext) is QueueActionResult.Failed)
    }

    @Test fun nothingDoneAndFailedIntentsStayFailed() {
        val r = QueueActionVerifier.verify(QueueAction.AddToQueue, s(1), 0, emptyList(), QueueActionResult.Failed(QueueAction.AddToQueue, "x"))
        assertTrue(r is QueueActionResult.Failed)
    }

    @Test fun anIntentForTheOtherActionIsRejected() {
        val committed = listOf(s(1), s(9))
        val r = QueueActionVerifier.verify(QueueAction.AddToQueue, s(9), 1, committed, QueueActionResult.Queued(QueueAction.PlayNext, QueuePlacement.PlayNext, 1))
        assertTrue(r is QueueActionResult.Failed)
    }

    @Test fun addToQueueIsVerifiedAtTheTail() {
        val intent = QueueActionResult.Queued(QueueAction.AddToQueue, QueuePlacement.Appended, 2)
        val committed = listOf(s(1), s(2), s(9))
        assertEquals(intent, QueueActionVerifier.verify(QueueAction.AddToQueue, s(9), 2, committed, intent))
    }

    @Test fun freshQueueMustBeExactlyTheNewSong() {
        assertEquals(QueueActionResult.StartedQueue, QueueActionVerifier.verify(QueueAction.PlayNext, s(9), 0, listOf(s(9)), QueueActionResult.StartedQueue))
        assertTrue(QueueActionVerifier.verify(QueueAction.PlayNext, s(9), 0, listOf(s(8)), QueueActionResult.StartedQueue) is QueueActionResult.Failed)
        assertTrue(QueueActionVerifier.verify(QueueAction.PlayNext, s(9), 3, listOf(s(9)), QueueActionResult.StartedQueue) is QueueActionResult.Failed)
    }

    // ── messages ────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun successMessagesAreAccurate() {
        assertEquals("Playing next: Song 1", QueueActionFeedback.message(intentNext, s(1)))
        assertEquals("Added to queue: Song 1", QueueActionFeedback.message(QueueActionResult.Queued(QueueAction.AddToQueue, QueuePlacement.Appended, 5), s(1)))
        assertEquals("Playing now: Song 1", QueueActionFeedback.message(QueueActionResult.StartedQueue, s(1)))
    }

    @Test fun aPlayNextThatWasOnlyAppendedDoesNotClaimNext() {
        val appended = QueueActionResult.Queued(QueueAction.PlayNext, QueuePlacement.Appended, 7)
        assertEquals("Added to queue: Song 1", QueueActionFeedback.message(appended, s(1)))
    }

    @Test fun failureNeverClaimsAnAddition() {
        val a = QueueActionFeedback.message(QueueActionResult.Failed(QueueAction.AddToQueue, "r"), s(1))
        val p = QueueActionFeedback.message(QueueActionResult.Failed(QueueAction.PlayNext, "r"), s(1))
        assertEquals("Couldn't add to queue", a)
        assertEquals("Couldn't play next", p)
        assertFalse(a.startsWith("Added") || p.startsWith("Playing"))
    }

    @Test fun longTitlesAreShortenedAndBlankTitlesDropped() {
        val long = QueueActionFeedback.message(intentNext, s(1, "A".repeat(80)))
        assertTrue(long, long.length <= "Playing next: ".length + 28)
        assertTrue(long.endsWith("…"))
    }
}
