package com.launchpoint.wavdrop.ui.screen.nowplaying

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import com.launchpoint.wavdrop.data.lyrics.SyncedLyricsLine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P5-F18: the manual-scroll follow-suspension policy. All timing is explicit (`nowMs` arguments), i.e. virtual time:
 * no sleeps, no coroutines, no real clock.
 */
class LyricsFollowPolicyTest {

    private val lines = listOf(
        SyncedLyricsLine(10_000L, "one"),
        SyncedLyricsLine(20_000L, "two"),
        SyncedLyricsLine(30_000L, "three"),
    )

    // 1
    @Test fun followingIsInitiallyEnabled() {
        val policy = LyricsFollowPolicy()
        assertTrue(policy.following)
        assertNull(policy.resumeAtMs)
    }

    // 2
    @Test fun firstUserGestureSuspendsFollowing() {
        val policy = LyricsFollowPolicy()
        policy.onUserScrollActive()
        assertFalse(policy.following)
    }

    // 3 + 4: while suspended the policy says "do not move", and the highlight is a pure function of position.
    @Test fun indexChangesWhileSuspendedDoNotRequestFollowing_andHighlightKeepsTracking() {
        val policy = LyricsFollowPolicy()
        policy.onUserScrollActive()
        policy.onUserScrollIdle(nowMs = 1_000)
        // active-line changes are not an input to the policy at all (no index API), so they cannot resume following
        assertFalse(policy.following) // list must not be moved
        // highlighting is independent of the follow state and still follows the position
        assertEquals(1, activeSyncedLyricsIndex(lines, 20_100L))
        assertEquals(2, activeSyncedLyricsIndex(lines, 30_100L))
    }

    // 5
    @Test fun repeatedGesturesRestartTheInactivityPeriod() {
        val policy = LyricsFollowPolicy(resumeDelayMs = 5_000)
        policy.onUserScrollActive()
        policy.onUserScrollIdle(nowMs = 1_000)
        assertEquals(6_000L, policy.resumeAtMs)
        policy.onUserScrollActive() // new gesture at 4 s
        assertNull(policy.resumeAtMs)
        assertFalse(policy.onResumeDue(nowMs = 6_000))
        policy.onUserScrollIdle(nowMs = 4_500)
        assertEquals(9_500L, policy.resumeAtMs)
        assertFalse(policy.onResumeDue(nowMs = 6_000))
        assertFalse(policy.following)
    }

    // 6
    @Test fun followingResumesAfterTheInactivityPeriod() {
        val policy = LyricsFollowPolicy(resumeDelayMs = 5_000)
        policy.onUserScrollActive()
        policy.onUserScrollIdle(nowMs = 1_000)
        assertFalse(policy.onResumeDue(nowMs = 5_999))
        assertFalse(policy.following)
        assertTrue(policy.onResumeDue(nowMs = 6_000))
        assertTrue(policy.following)
        assertNull(policy.resumeAtMs)
        // resuming is one-shot
        assertFalse(policy.onResumeDue(nowMs = 7_000))
    }

    // 7: a fling keeps the gesture active, so no resume deadline exists until it settles.
    @Test fun anActiveFlingPreventsPrematureRecentering() {
        val policy = LyricsFollowPolicy(resumeDelayMs = 5_000)
        policy.onUserScrollActive() // finger drag
        // finger lifted, fling still moving: the scroll is not idle yet
        policy.onUserScrollActive() // fling frames are still user-driven scroll
        assertNull(policy.resumeAtMs)
        assertFalse(policy.onResumeDue(nowMs = 60_000))
        assertFalse(policy.following)
        policy.onScrollSettled(nowMs = 2_000) // fling finished
        assertEquals(7_000L, policy.resumeAtMs)
    }

    @Test fun settlingWithoutAUserGestureDoesNothing() {
        val policy = LyricsFollowPolicy()
        policy.onScrollSettled(nowMs = 1_000)
        assertTrue(policy.following)
        assertNull(policy.resumeAtMs)
    }

    // 8: programmatic scrolling (scrollToItem / scrollBy) never reaches the nested-scroll connection; and an
    // explicitly non-user source must not count either.
    @Test fun programmaticOrSideEffectScrollDoesNotCountAsAUserGesture() {
        val policy = LyricsFollowPolicy()
        val connection = LyricsUserScrollConnection(policy) { 0L }
        connection.onPreScroll(Offset(0f, 40f), NestedScrollSource.SideEffect)
        assertTrue(policy.following)
        connection.onPreScroll(Offset(0f, 40f), NestedScrollSource.UserInput)
        assertFalse(policy.following)
    }

    @Test fun userFlingEndStartsTheInactivityPeriod() = runBlocking {
        val policy = LyricsFollowPolicy(resumeDelayMs = 5_000)
        var now = 1_000L
        val connection = LyricsUserScrollConnection(policy) { now }
        connection.onPreScroll(Offset(0f, 40f), NestedScrollSource.UserInput)
        now = 1_800L
        connection.onPostFling(Velocity.Zero, Velocity(0f, 900f))
        assertEquals(6_800L, policy.resumeAtMs)
    }

    // 9
    @Test fun aNewTrackResetsFollowing() {
        val policy = LyricsFollowPolicy()
        policy.onUserScrollActive()
        policy.onUserScrollIdle(nowMs = 1_000)
        policy.reset() // what a new `lines` (track) does
        assertTrue(policy.following)
        assertNull(policy.resumeAtMs)
        assertFalse(policy.onResumeDue(nowMs = 99_000)) // no stale deadline from the previous track
    }

    // 10: explicit seek signal (see also LyricsFollowLifecycleTest for the Compose wiring)
    @Test fun aSeekWithinTheSameLyricWhileSuspendedRestoresFollowing() {
        val policy = LyricsFollowPolicy()
        policy.onUserScrollActive()
        policy.onUserScrollIdle(nowMs = 1_000)
        policy.onSeekEpoch(1) // no active-line change involved
        assertTrue(policy.following)
        assertNull(policy.resumeAtMs)
    }

    @Test fun forwardOneLineAndBackwardSeeksBothRestoreFollowing() {
        val policy = LyricsFollowPolicy()
        policy.onUserScrollActive()
        policy.onSeekEpoch(1) // forward by one line: indistinguishable from playback by index, explicit here
        assertTrue(policy.following)
        policy.onUserScrollActive()
        policy.onSeekEpoch(2) // backward
        assertTrue(policy.following)
    }

    @Test fun aSeekWhileFollowingIsAlreadyEnabledChangesNothing() {
        val policy = LyricsFollowPolicy()
        policy.onSeekEpoch(1)
        assertTrue(policy.following)
        assertNull(policy.resumeAtMs)
    }

    @Test fun withoutASeekSignalNothingButTheTimerResumesFollowing() {
        // Normal consecutive line changes and a large position jump the user did not make (repeat wrap, another
        // controller) deliver no seek epoch, so the suspension stands.
        val policy = LyricsFollowPolicy()
        policy.onUserScrollActive()
        policy.onUserScrollIdle(nowMs = 0)
        policy.onSeekEpoch(0) // same epoch re-delivered (recomposition)
        assertFalse(policy.following)
        assertEquals(5_000L, policy.resumeAtMs)
    }

    @Test fun aSeekClearsAPendingResumeDeadline() {
        val policy = LyricsFollowPolicy()
        policy.onUserScrollActive()
        policy.onUserScrollIdle(nowMs = 0)
        policy.onSeekEpoch(1)
        assertNull(policy.resumeAtMs)
        assertFalse(policy.onResumeDue(nowMs = 99_000))
    }

    // 11: closing/reopening the lyrics view disposes the composable state; a fresh policy is the initial one.
    @Test fun aFreshPolicyAfterReopeningIsInitial() {
        val old = LyricsFollowPolicy()
        old.onUserScrollActive()
        val reopened = LyricsFollowPolicy()
        assertTrue(reopened.following)
        assertNull(reopened.resumeAtMs)
        assertFalse(old.following) // the old one is untouched garbage: nothing shared between instances
    }

    // 12: the policy owns no jobs/threads; repeated cycles and re-reads (recomposition) never leak or reset it.
    @Test fun repeatedGestureCyclesLeaveNoPendingStateAndRecompositionDoesNotReset() {
        val policy = LyricsFollowPolicy(resumeDelayMs = 5_000)
        repeat(1_000) { i ->
            policy.onUserScrollActive()
            policy.onUserScrollIdle(nowMs = i * 10L)
        }
        // "recomposition" = reading state repeatedly: must not change it
        repeat(10) { assertFalse(policy.following) }
        assertEquals(9_990L + 5_000L, policy.resumeAtMs)
        assertTrue(policy.onResumeDue(nowMs = 15_000))
        assertNull(policy.resumeAtMs)
        policy.reset()
        assertNull(policy.resumeAtMs)
        assertTrue(policy.following)
    }

    @Test fun resumeIsRefusedWhileAGestureIsStillActive() {
        val policy = LyricsFollowPolicy(resumeDelayMs = 1_000)
        policy.onUserScrollActive()
        policy.onUserScrollIdle(nowMs = 0)
        policy.onUserScrollActive()
        assertFalse(policy.onResumeDue(nowMs = 5_000))
        assertFalse(policy.following)
    }
}
