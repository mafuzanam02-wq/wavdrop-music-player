package com.launchpoint.wavdrop.ui.components

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackProgressPresentationTest {

    @Test fun unknownDurationHasNoProgressFraction() {
        val progress = playbackProgressPresentation(7_000L, C.TIME_UNSET)
        assertEquals(7_000L, progress.positionMs)
        assertEquals(0L, progress.durationMs)
        assertEquals(0f, progress.fraction, 0f)
        assertEquals(7L, progress.displayElapsedSeconds)
        assertEquals(0L, progress.displayDurationSeconds)
        assertEquals(0L, progress.displayRemainingSeconds)
        assertEquals(0L, playbackProgressPresentation(-1L, 0L).displayElapsedSeconds)
    }

    @Test fun dragOverridesPlayerOnlyWhilePresent() {
        val dragging = playbackProgressPresentation(20_000L, 100_000L, 75_000L)
        val released = playbackProgressPresentation(20_000L, 100_000L)
        assertEquals(75_000L, dragging.positionMs)
        assertEquals(0.75f, dragging.fraction, 0f)
        assertEquals(75L, dragging.displayElapsedSeconds)
        assertEquals(25L, dragging.displayRemainingSeconds)
        assertEquals(20_000L, released.positionMs)
        assertEquals(0.2f, released.fraction, 0f)
        assertEquals(20L, released.displayElapsedSeconds)
        assertEquals(80L, released.displayRemainingSeconds)
    }

    @Test fun elapsedAndRemainingChangeTogetherAtSecondBoundary() {
        val start = playbackProgressPresentation(90_000L, 240_000L)
        val before = playbackProgressPresentation(90_999L, 240_000L)
        val after = playbackProgressPresentation(91_000L, 240_000L)
        assertEquals(90L, start.displayElapsedSeconds)
        assertEquals(150L, start.displayRemainingSeconds)
        assertEquals(90L, before.displayElapsedSeconds)
        assertEquals(150L, before.displayRemainingSeconds)
        assertEquals(91L, after.displayElapsedSeconds)
        assertEquals(149L, after.displayRemainingSeconds)
    }

    @Test fun nonExactDurationUsesWholeDisplayedSeconds() {
        val progress = playbackProgressPresentation(90_500L, 243_500L)
        assertEquals(243L, progress.displayDurationSeconds)
        assertEquals(90L, progress.displayElapsedSeconds)
        assertEquals(153L, progress.displayRemainingSeconds)
    }

    @Test fun nearEndAndOverrunClampToKnownDuration() {
        val nearEnd = playbackProgressPresentation(239_999L, 240_000L)
        val pastEnd = playbackProgressPresentation(250_000L, 240_000L)
        assertEquals(239L, nearEnd.displayElapsedSeconds)
        assertEquals(1L, nearEnd.displayRemainingSeconds)
        assertEquals(240_000L, pastEnd.positionMs)
        assertEquals(240L, pastEnd.displayElapsedSeconds)
        assertEquals(0L, pastEnd.displayRemainingSeconds)
        assertEquals(1f, pastEnd.fraction, 0f)
    }

    @Test fun dragTargetDrivesBothDisplayedTimes() {
        val dragging = playbackProgressPresentation(90_000L, 240_000L, 130_000L)
        assertEquals(130L, dragging.displayElapsedSeconds)
        assertEquals(110L, dragging.displayRemainingSeconds)
    }

    @Test fun knownDurationAlwaysPreservesVisibleSecondInvariant() {
        listOf(0L, 1L, 90_000L, 90_999L, 91_000L, 239_999L, 240_000L, 250_000L).forEach { position ->
            val progress = playbackProgressPresentation(position, 240_000L)
            assertEquals(240L, progress.displayElapsedSeconds + progress.displayRemainingSeconds)
        }
    }

    @Test fun nextTrackUsesItsOwnPositionAndDuration() {
        val next = playbackProgressPresentation(1_000L, 120_000L)
        assertEquals(1_000L, next.positionMs)
        assertEquals(1L, next.displayElapsedSeconds)
        assertEquals(119L, next.displayRemainingSeconds)
        assertEquals(1_000f / 120_000f, next.fraction, 0f)
    }

    @Test fun repeatOneWrapResetsElapsedAndRemainingTogether() {
        val restarted = playbackProgressPresentation(0L, 240_000L)
        assertEquals(0L, restarted.displayElapsedSeconds)
        assertEquals(240L, restarted.displayRemainingSeconds)
    }
}
