package com.launchpoint.wavdrop.playback

/**
 * Pure-function helper: decides whether a 500 ms ticker observation represents a
 * REPEAT_ONE loop boundary.
 *
 * Kept as a standalone internal object so the detection logic can be unit-tested
 * without any MediaController infrastructure.
 */
internal object LoopBoundaryDetector {

    /**
     * Position must be below this value (ms) to be considered "near the start of a
     * new loop". 1 500 ms gives comfortable margin for the 500 ms ticker interval.
     */
    const val LOOP_NEAR_START_MS = 1_500L

    /**
     * Previous observed position must be at or above this value (ms) before we treat
     * a wrap-to-near-start as a genuine loop boundary.  Prevents false detection on
     * freshly-started or just-reset sessions where position is still near zero.
     */
    const val LOOP_MIN_PREV_POS_MS = 3_000L

    /**
     * Returns true when the ticker observes that playback has wrapped back to near the
     * beginning of the same track while REPEAT_ONE is active.
     *
     * Conditions (all must hold):
     * - [repeatMode] == [RepeatMode.ONE]: only meaningful in repeat-one context.
     * - [prevPositionMs] >= [LOOP_MIN_PREV_POS_MS]: track was genuinely playing.
     * - [currentPositionMs] < [LOOP_NEAR_START_MS]: position is now near the start.
     *
     * User backward seeks are NOT counted as loop boundaries because they are guarded
     * by the [repeatMode] == ONE requirement — the app never auto-resets a different
     * repeat mode to ONE on a seek.
     */
    fun isLoopBoundary(
        prevPositionMs: Long,
        currentPositionMs: Long,
        repeatMode: RepeatMode,
    ): Boolean =
        repeatMode == RepeatMode.ONE
            && prevPositionMs >= LOOP_MIN_PREV_POS_MS
            && currentPositionMs < LOOP_NEAR_START_MS
}
