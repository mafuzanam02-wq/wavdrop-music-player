package com.launchpoint.wavdrop.playback

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the pure [PlaybackCallbackOwnership] policy that PlayerController's Media3 listener consults.
 * They do not drive a real MediaController; they pin the ownership table the callbacks apply.
 */
class PlaybackCallbackOwnershipTest {

    private fun discontinuity(reason: Int) = PlaybackCallbackOwnership.forPositionDiscontinuity(reason)

    @Test fun removeDiscontinuityDoesNotOwnStatsTransition() =
        assertFalse(discontinuity(Player.DISCONTINUITY_REASON_REMOVE).notifiesStats)

    @Test fun removeDiscontinuityDoesNotOwnPersistence() =
        assertFalse(discontinuity(Player.DISCONTINUITY_REASON_REMOVE).persistsSession)

    @Test fun removeDiscontinuityStillSynchronizesState() {
        // The listener always calls syncNowPlayingState for REMOVE (with notifyStats = false); the policy
        // only withholds stats and persistence, so the REMOVE branch must not be an early return.
        val own = discontinuity(Player.DISCONTINUITY_REASON_REMOVE)
        assertEquals(CallbackOwnership(notifiesStats = false, persistsSession = false), own)
    }

    @Test fun playlistChangedMediaItemTransitionOwnsStats() =
        assertTrue(
            PlaybackCallbackOwnership
                .forMediaItemTransition(Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED).notifiesStats,
        )

    @Test fun playlistChangedMediaItemTransitionOwnsPersistence() =
        assertTrue(
            PlaybackCallbackOwnership
                .forMediaItemTransition(Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED).persistsSession,
        )

    @Test fun otherMediaItemTransitionReasonsKeepFullOwnership() {
        for (reason in listOf(
            Player.MEDIA_ITEM_TRANSITION_REASON_AUTO,
            Player.MEDIA_ITEM_TRANSITION_REASON_SEEK,
            Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT,
        )) {
            assertEquals(CallbackOwnership(true, true), PlaybackCallbackOwnership.forMediaItemTransition(reason))
        }
    }

    @Test fun autoDiscontinuityPolicyIsUnchanged() {
        // Previously: syncNowPlayingState(notifyStats = false) then saveSessionAsync().
        assertEquals(
            CallbackOwnership(notifiesStats = false, persistsSession = true),
            discontinuity(Player.DISCONTINUITY_REASON_AUTO_TRANSITION),
        )
    }

    @Test fun seekAndOtherDiscontinuitiesAreUnchanged() {
        // Previously: syncNowPlayingState(notifyStats = true) then saveSessionAsync().
        for (reason in listOf(
            Player.DISCONTINUITY_REASON_SEEK,
            Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT,
            Player.DISCONTINUITY_REASON_SKIP,
            Player.DISCONTINUITY_REASON_INTERNAL,
            Player.DISCONTINUITY_REASON_SILENCE_SKIP,
        )) {
            assertEquals(CallbackOwnership(true, true), discontinuity(reason))
        }
    }
}
