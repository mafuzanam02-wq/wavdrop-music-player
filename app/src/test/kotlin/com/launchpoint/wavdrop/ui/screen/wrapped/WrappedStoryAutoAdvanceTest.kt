package com.launchpoint.wavdrop.ui.screen.wrapped

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P5-WR-PLAY-01: the real [WrappedStoryAutoAdvance] effect on a bare Compose runtime with a manually driven frame
 * clock (the project has no Compose UI test dependency for JVM tests). Deterministic: animation time only moves
 * when the test calls [Harness.advance].
 *
 * The report-change tests mirror what WrappedContent does on a genuine report switch: the report key changes, then
 * the reset effect calls `progress.snapTo(0f)` and publishes the new `activeReportKey`.
 */
class WrappedStoryAutoAdvanceTest {

    private class NoopApplier : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun onClear() = Unit
    }

    private class ManualClock : MonotonicFrameClock {
        var nowNanos = 0L
        private val waiting = mutableListOf<(Long) -> Unit>()

        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R =
            suspendCancellableCoroutine { cont ->
                waiting += { t -> cont.resumeWith(runCatching { onFrame(t) }) }
            }

        fun deliverFrame() {
            val batch = waiting.toList()
            waiting.clear()
            batch.forEach { it(nowNanos) }
        }
    }

    private class Harness(val recomposer: Recomposer, val clock: ManualClock) {
        // The recomposer shares this clock, so awaitIdle() would wait for a frame nobody delivers. Instead each
        // round delivers a frame at the current (unchanged) time and yields, which lets recomposition run and
        // lets newly launched animations record their start time without moving animation time.
        suspend fun pump() {
            repeat(10) {
                Snapshot.sendApplyNotifications()
                clock.deliverFrame()
                yield()
            }
        }

        suspend fun settle() = pump()

        suspend fun advance(ms: Long) {
            clock.nowNanos += ms * 1_000_000L
            clock.deliverFrame()
            pump()
        }
    }

    private fun run(content: @Composable () -> Unit, body: suspend Harness.() -> Unit) = runBlocking {
        val clock = ManualClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val loop = launch(clock, start = CoroutineStart.UNDISPATCHED) { recomposer.runRecomposeAndApplyChanges() }
        val composition = Composition(NoopApplier(), recomposer)
        try {
            composition.setContent(content)
            Harness(recomposer, clock).apply { settle(); body() }
        } finally {
            composition.dispose()
            recomposer.cancel()
            loop.cancel()
        }
    }

    private val duration = 4_000

    @Test
    fun reportChangeWhileOnPageZeroAndPlayingStillAdvancesAfterReset() {
        val reportKey = mutableStateOf("A")
        val activeKey = mutableStateOf("A")
        val progress = Animatable(0f)
        var advances = 0
        run({
            WrappedStoryAutoAdvance(
                page = 0, playing = true, reduceMotion = false,
                reportKey = reportKey.value, activeReportKey = activeKey.value,
                progress = progress, isLastPage = false, durationMs = duration,
                onAdvance = { advances++ }, onComplete = {},
            )
        }) {
            advance(1_000)
            assertTrue(progress.value in 0.2f..0.3f)

            // Genuine report switch: key changes, reset snaps progress (cancelling the running animateTo)
            // and publishes the new active report. Page and playing are unchanged.
            reportKey.value = "B"
            pump()
            progress.snapTo(0f)
            activeKey.value = "B"
            settle()

            assertEquals(0f, progress.value, 0f)
            advance(3_999)
            assertEquals(0, advances)
            advance(1)
            assertEquals(1, advances)
        }
    }

    @Test
    fun obsoleteReportCannotAdvanceWhileResetIsPending() {
        val reportKey = mutableStateOf("A")
        val activeKey = mutableStateOf("A")
        val progress = Animatable(0f)
        var advances = 0
        run({
            WrappedStoryAutoAdvance(
                page = 0, playing = true, reduceMotion = false,
                reportKey = reportKey.value, activeReportKey = activeKey.value,
                progress = progress, isLastPage = false, durationMs = duration,
                onAdvance = { advances++ }, onComplete = {},
            )
        }) {
            advance(1_000)
            reportKey.value = "B" // reset has not published yet
            pump()
            advance(10_000)
            assertEquals(0, advances)
        }
    }

    @Test
    fun rapidReportChangesLetOnlyTheLastReportAdvanceOnce() {
        val reportKey = mutableStateOf("A")
        val activeKey = mutableStateOf("A")
        val progress = Animatable(0f)
        var advances = 0
        run({
            WrappedStoryAutoAdvance(
                page = 0, playing = true, reduceMotion = false,
                reportKey = reportKey.value, activeReportKey = activeKey.value,
                progress = progress, isLastPage = false, durationMs = duration,
                onAdvance = { advances++ }, onComplete = {},
            )
        }) {
            advance(2_000)
            reportKey.value = "B"; pump()
            advance(500)
            reportKey.value = "C"; pump()
            progress.snapTo(0f)
            activeKey.value = "C"
            settle()
            assertEquals(0, advances)

            advance(3_999)
            assertEquals(0, advances)
            advance(1)
            assertEquals(1, advances)
            advance(20_000)
            assertEquals(1, advances) // finished animation does not relaunch
        }
    }

    @Test
    fun pauseAndResumePreserveProgressAndRemainingTime() {
        val playing = mutableStateOf(true)
        val progress = Animatable(0f)
        var advances = 0
        run({
            WrappedStoryAutoAdvance(
                page = 0, playing = playing.value, reduceMotion = false,
                reportKey = "A", activeReportKey = "A",
                progress = progress, isLastPage = false, durationMs = duration,
                onAdvance = { advances++ }, onComplete = {},
            )
        }) {
            advance(2_000)
            playing.value = false
            settle()
            val paused = progress.value
            assertEquals(0.5f, paused, 0.01f)
            advance(10_000)
            assertEquals(paused, progress.value, 0f)
            assertEquals(0, advances)

            playing.value = true
            settle()
            advance(1_990)
            assertEquals(0, advances)
            advance(20)
            assertEquals(1, advances)
        }
    }

    @Test
    fun manualPageChangeAfterResetRestartsAdvanceForNewPage() {
        val page = mutableIntStateOf(0)
        val progress = Animatable(0f)
        val advancedFrom = mutableListOf<Int>()
        run({
            val p = page.intValue
            WrappedStoryAutoAdvance(
                page = p, playing = true, reduceMotion = false,
                reportKey = "A", activeReportKey = "A",
                progress = progress, isLastPage = false, durationMs = duration,
                onAdvance = { advancedFrom += p }, onComplete = {},
            )
        }) {
            advance(1_000)
            progress.snapTo(0f) // what the page-change reset effect does
            page.intValue = 3
            settle()
            advance(4_000)
            assertEquals(listOf(3), advancedFrom)
        }
    }

    @Test
    fun finalSlideCompletesInsteadOfAdvancing() {
        val progress = Animatable(0f)
        var advances = 0
        var completes = 0
        run({
            WrappedStoryAutoAdvance(
                page = 7, playing = true, reduceMotion = false,
                reportKey = "A", activeReportKey = "A",
                progress = progress, isLastPage = true, durationMs = duration,
                onAdvance = { advances++ }, onComplete = { completes++ },
            )
        }) {
            advance(4_000)
            assertEquals(0, advances)
            assertEquals(1, completes)
        }
    }

    @Test
    fun reduceMotionNeverAnimatesOrAdvances() {
        val progress = Animatable(0f)
        var advances = 0
        run({
            WrappedStoryAutoAdvance(
                page = 0, playing = true, reduceMotion = true,
                reportKey = "A", activeReportKey = "A",
                progress = progress, isLastPage = false, durationMs = duration,
                onAdvance = { advances++ }, onComplete = {},
            )
        }) {
            advance(60_000)
            assertEquals(0f, progress.value, 0f)
            assertEquals(0, advances)
        }
    }

    @Test
    fun restoredProgressResumesOnlyTheRemainingTime() {
        val progress = Animatable(0.75f)
        var advances = 0
        run({
            WrappedStoryAutoAdvance(
                page = 2, playing = true, reduceMotion = false,
                reportKey = "A", activeReportKey = "A",
                progress = progress, isLastPage = false, durationMs = duration,
                onAdvance = { advances++ }, onComplete = {},
            )
        }) {
            advance(990)
            assertEquals(0, advances)
            advance(20)
            assertEquals(1, advances)
        }
    }
}
