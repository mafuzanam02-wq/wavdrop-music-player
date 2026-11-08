package com.launchpoint.wavdrop.ui.screen.nowplaying

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P5-F18: the Compose lifecycle of the follow policy, exercised through the real [rememberLyricsFollowPolicy] that
 * the lyrics list uses, on a bare Compose runtime (Recomposer + frame clock; the project has no Compose UI test
 * dependency for JVM tests). Deterministic: the frame clock is immediate and every step waits for the recomposer
 * to go idle.
 */
class LyricsFollowLifecycleTest {

    private class NoopApplier : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun onClear() = Unit
    }

    private class Harness(val recomposer: Recomposer, val composition: Composition) {
        suspend fun settle() {
            Snapshot.sendApplyNotifications()
            recomposer.awaitIdle()
        }
    }

    private fun runComposition(content: @Composable () -> Unit, body: suspend Harness.() -> Unit) = runBlocking {
        val clock = object : MonotonicFrameClock {
            override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R = onFrame(0L)
        }
        val recomposer = Recomposer(coroutineContext + clock)
        val loop = launch(clock, start = CoroutineStart.UNDISPATCHED) { recomposer.runRecomposeAndApplyChanges() }
        val composition = Composition(NoopApplier(), recomposer)
        try {
            composition.setContent(content)
            Harness(recomposer, composition).apply { settle(); body() }
        } finally {
            composition.dispose()
            recomposer.cancel()
            loop.cancel()
        }
    }

    // Different songs with identical lyrics must not share suspended state.
    @Test fun differentTracksWithIdenticalLyricsDoNotShareSuspendedFollowState() {
        var songId by mutableLongStateOf(1L)
        var policy: LyricsFollowPolicy? = null
        runComposition({ policy = rememberLyricsFollowPolicy(songId, seekEpoch = 0) }) {
            val first = policy!!
            first.onUserScrollActive()
            first.onUserScrollIdle(nowMs = 0)
            assertFalse(first.following)

            songId = 2L // different song, same lyric list (nothing about lyrics is even an input)
            settle()

            assertNotSame(first, policy)
            assertTrue(policy!!.following)
            assertEquals(null, policy!!.resumeAtMs)
        }
    }

    // Recomposition of the same track keeps the state.
    @Test fun recompositionOfTheSameTrackPreservesFollowState() {
        val songId = 7L
        var unrelated by mutableIntStateOf(0)
        var policy: LyricsFollowPolicy? = null
        runComposition({
            @Suppress("UNUSED_EXPRESSION") unrelated // read so a change recomposes this scope
            policy = rememberLyricsFollowPolicy(songId, seekEpoch = 0)
        }) {
            val first = policy!!
            first.onUserScrollActive()
            first.onUserScrollIdle(nowMs = 100)
            repeat(5) {
                unrelated += 1
                settle()
            }
            assertSame(first, policy)
            assertFalse(policy!!.following)
            assertEquals(5_100L, policy!!.resumeAtMs)
        }
    }

    // Opening and closing the overlay resets following.
    @Test fun closingAndReopeningTheOverlayResetsFollowing() {
        var visible by mutableStateOf(true)
        var policy: LyricsFollowPolicy? = null
        runComposition({ if (visible) policy = rememberLyricsFollowPolicy(7L, seekEpoch = 0) }) {
            val first = policy!!
            first.onUserScrollActive()
            assertFalse(first.following)

            visible = false
            settle()
            visible = true
            settle()

            assertNotSame(first, policy)
            assertTrue(policy!!.following)
        }
    }

    // An explicit seek (epoch bump) restores following without replacing the policy.
    @Test fun aSeekEpochChangeRestoresFollowingForTheSameTrack() {
        var epoch by mutableIntStateOf(0)
        var policy: LyricsFollowPolicy? = null
        runComposition({ policy = rememberLyricsFollowPolicy(7L, seekEpoch = epoch) }) {
            val first = policy!!
            first.onUserScrollActive()
            first.onUserScrollIdle(nowMs = 0)
            assertFalse(first.following)

            epoch = 1
            settle()

            assertSame(first, policy)
            assertTrue(first.following)
            assertEquals(null, first.resumeAtMs)
        }
    }

    // A policy created while the epoch is already non-zero must not treat that epoch as a new seek.
    @Test fun aPolicyCreatedAfterEarlierSeeksStartsFollowingAndDoesNotReactToThePastEpoch() {
        var songId by mutableLongStateOf(1L)
        var policy: LyricsFollowPolicy? = null
        runComposition({ policy = rememberLyricsFollowPolicy(songId, seekEpoch = 4) }) {
            policy!!.onUserScrollActive()
            settle()
            assertFalse(policy!!.following) // epoch 4 is not "new" for this policy
            songId = 2L
            settle()
            assertTrue(policy!!.following)
        }
    }
}
