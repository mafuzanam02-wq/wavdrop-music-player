package com.launchpoint.wavdrop.playback

/**
 * Immutable state of a single bad-media recovery episode (Phase 8).
 *
 * A "recovery episode" spans one or more *consecutive* unplayable tracks with no successful
 * playback in between. It exists to make automatic bad-media recovery deterministic and, above
 * all, BOUNDED: without it, preserving the queue (never removing a bad item) would allow an
 * all-broken queue under REPEAT_ALL to loop forever.
 *
 * Occurrence identity
 * -------------------
 * A failure belongs to a queue OCCURRENCE, identified by (queueGeneration, playbackIndex) — never
 * by song id. Duplicate songs therefore recover independently: failing the first `A` in
 * `[A, B, A, C]` never excludes the later `A`. [queueGeneration] is bumped by the controller
 * whenever the queue is replaced or structurally mutated, which invalidates a stale episode.
 *
 * @property queueGeneration the queue generation this episode was opened against.
 * @property attemptedOccurrences playback indices already tried (and failed) in this episode.
 * @property wasPlaying the ORIGINAL play intent captured when the episode opened — recovery
 *   resumes playback only if the user was actually playing when the first failure occurred.
 */
data class BadMediaRecoveryState(
    val queueGeneration: Long,
    val attemptedOccurrences: Set<Int>,
    val wasPlaying: Boolean,
)

/** Outcome of a single [BadMediaRecoveryPlanner.onPlaybackError] decision. */
sealed interface BadMediaRecoveryStep {
    /** The updated episode state to retain until the next decision. */
    val state: BadMediaRecoveryState

    /** True when this call opened a fresh episode (first failure, or generation changed). */
    val isNewEpisode: Boolean

    /** Try [targetIndex] next — a not-yet-attempted, eligible queue occurrence. */
    data class Advance(
        val targetIndex: Int,
        override val state: BadMediaRecoveryState,
        override val isNewEpisode: Boolean,
    ) : BadMediaRecoveryStep

    /** No eligible occurrence remains; stop cleanly and keep the queue visible. */
    data class Exhausted(
        override val state: BadMediaRecoveryState,
        override val isNewEpisode: Boolean,
    ) : BadMediaRecoveryStep
}

/**
 * Pure decision logic for recovering from a Media3 playback failure. Knows nothing about
 * MediaController, the UI, or session persistence — the controller executes its output.
 *
 * Guarantees:
 *  - **Bounded:** a single episode inspects at most `queueSize` occurrences, so there is no
 *    infinite skip/retry loop under repeat-all, repeat-one, shuffle, duplicate songs, or an
 *    entirely broken queue.
 *  - **Occurrence-safe:** operates on playback indices into the *current* (already shuffled)
 *    playback queue, so it follows the active order and never reshuffles or reorders.
 *  - **Repeat-one bypass for the failed item only:** it uses [QueueNavigator.nextIndex]
 *    (advance) rather than the repeat-one "stay" semantics, so a broken current item under
 *    REPEAT_ONE is not retried forever; normal repeat-one resumes once a good item plays.
 */
object BadMediaRecoveryPlanner {

    /**
     * Decides how to react to a failure of the occurrence at [failingIndex].
     *
     * @param previous the current episode, or null if none is open.
     * @param queueGeneration the current queue generation.
     * @param queueSize size of the current playback queue.
     * @param failingIndex playback index that just failed.
     * @param repeatMode the active repeat mode.
     * @param wasPlaying whether the user was actively playing when the failure occurred (only used
     *   when opening a fresh episode; a continuing episode keeps its original intent).
     */
    fun onPlaybackError(
        previous: BadMediaRecoveryState?,
        queueGeneration: Long,
        queueSize: Int,
        failingIndex: Int,
        repeatMode: RepeatMode,
        wasPlaying: Boolean,
    ): BadMediaRecoveryStep {
        val isNewEpisode = previous == null || previous.queueGeneration != queueGeneration
        val base = if (isNewEpisode) {
            BadMediaRecoveryState(
                queueGeneration = queueGeneration,
                attemptedOccurrences = emptySet(),
                wasPlaying = wasPlaying,
            )
        } else {
            previous
        }

        // Unresolvable position: cannot reason about the queue, so stop rather than risk a bad
        // seek. The queue itself is preserved by the controller (Exhausted does not clear it).
        if (queueSize <= 0 || failingIndex !in 0 until queueSize) {
            return BadMediaRecoveryStep.Exhausted(state = base, isNewEpisode = isNewEpisode)
        }

        val attempted = base.attemptedOccurrences + failingIndex
        val newState = base.copy(attemptedOccurrences = attempted)
        val target = nextEligibleOccurrence(queueSize, failingIndex, repeatMode, attempted)
        return if (target == null) {
            BadMediaRecoveryStep.Exhausted(state = newState, isNewEpisode = isNewEpisode)
        } else {
            BadMediaRecoveryStep.Advance(targetIndex = target, state = newState, isNewEpisode = isNewEpisode)
        }
    }

    /**
     * Finds the next playback occurrence, following repeat-aware forward traversal, that has NOT
     * already been attempted in this episode. Bounded to at most [queueSize] candidates so it
     * always terminates — even when every occurrence is broken or REPEAT_ALL would otherwise wrap
     * endlessly.
     */
    private fun nextEligibleOccurrence(
        queueSize: Int,
        failingIndex: Int,
        repeatMode: RepeatMode,
        attempted: Set<Int>,
    ): Int? {
        var cursor = failingIndex
        repeat(queueSize) {
            // nextIndex (not automaticNextIndex) so REPEAT_ONE advances past the failed occurrence.
            val next = QueueNavigator.nextIndex(
                queueSize = queueSize,
                currentIndex = cursor,
                repeatMode = repeatMode,
            ) ?: return null
            if (next !in attempted) return next
            if (next == cursor) return null
            cursor = next
        }
        return null
    }
}
