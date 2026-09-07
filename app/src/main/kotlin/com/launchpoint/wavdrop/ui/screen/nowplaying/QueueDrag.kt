package com.launchpoint.wavdrop.ui.screen.nowplaying

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.launchpoint.wavdrop.data.model.Song

/**
 * Bounds of a currently-composed Up Next drag handle, reported by the row and consumed by the
 * stable parent-level pointer detector. [bounds] is in the queue viewport Box's local coordinate
 * space (the same space the parent pointerInput reports pointer positions in) — see the coordinate
 * audit in QueueSheet. This is ephemeral UI-only state: it is never persisted to the ViewModel or
 * PlayerController, and it does NOT participate in Phase A drag identity — once a [QueueDragSession]
 * is created, the session survives this target being unregistered (LazyColumn virtualization).
 */
internal data class QueueDragHandleTarget(
    val playbackIndex: Int,
    val songId: Long,
    val bounds: Rect,
)

/**
 * Returns the handle whose bounds contain [position], or null if the pointer-down was not on any
 * registered handle. Selection is purely by exact hit region, never by song id — so two rows sharing
 * a song id resolve to the specific occurrence the user actually touched.
 */
internal fun findDragHandleAt(
    targets: Collection<QueueDragHandleTarget>,
    position: Offset,
): QueueDragHandleTarget? = targets.firstOrNull { it.bounds.contains(position) }

/**
 * Immutable identity of an in-progress queue drag. A playback index alone is not a stable identity
 * (the queue can mutate during a long drag), and a song id alone is ambiguous once duplicate song
 * ids are legal. The pair (song id + occurrence ordinal) uniquely identifies *which* occurrence the
 * user grabbed without introducing persistent queue-entry ids.
 *
 * [sourceOccurrenceOrdinal] is the 0-based position of the grabbed row among all rows with the same
 * [sourceSongId] at drag start. [startSourcePlaybackIndex] is retained only for logging/telemetry;
 * commit resolution goes through [resolveDraggedOccurrenceIndex] against the live queue.
 */
internal data class QueueDragSession(
    val sourceSongId: Long,
    val sourceOccurrenceOrdinal: Int,
    val startSourcePlaybackIndex: Int,
)

/** Outcome of a completed (or cancelled) queue drag gesture. */
internal sealed interface QueueDragEndDecision {
    /** Commit exactly one reorder through PlayerController. */
    data class Commit(val fromPlaybackIndex: Int, val toPlaybackIndex: Int) : QueueDragEndDecision

    /** Do nothing: cancelled, stale, overtaken, out of Up Next, or target unchanged. */
    data object NoOp : QueueDragEndDecision
}

/** 0-based ordinal of the row at [playbackIndex] among all rows sharing its song id. */
internal fun queueOccurrenceOrdinal(queue: List<Song>, playbackIndex: Int): Int {
    if (playbackIndex !in queue.indices) return 0
    val id = queue[playbackIndex].id
    return (0 until playbackIndex).count { queue[it].id == id }
}

/**
 * Resolves the current playback index of the grabbed occurrence in [queue], or null if that exact
 * occurrence no longer exists (e.g. it or an earlier duplicate was removed). Never falls back to
 * indexOfFirst — a duplicate must not be silently substituted.
 */
internal fun resolveDraggedOccurrenceIndex(queue: List<Song>, session: QueueDragSession): Int? {
    val occurrences = queue.indices.filter { queue[it].id == session.sourceSongId }
    return occurrences.getOrNull(session.sourceOccurrenceOrdinal)
}

/**
 * True while the grabbed occurrence is still a valid, moveable Up Next item: it still exists and is
 * strictly after [currentIndex] (playback has not overtaken it). Used to cancel a long drag the
 * moment it becomes structurally unsafe.
 */
internal fun isDraggedOccurrenceStillValid(
    queue: List<Song>,
    currentIndex: Int,
    session: QueueDragSession,
): Boolean {
    val source = resolveDraggedOccurrenceIndex(queue, session) ?: return false
    return source > currentIndex
}

/**
 * Decides what a finished drag gesture should commit. Correctness invariants (Phase A):
 * - a cancelled gesture never commits;
 * - the grabbed occurrence is re-resolved against the live queue (never a stale start index);
 * - source and target must both be strictly in Up Next (> [currentIndex]);
 * - only a genuine change (target != source) commits.
 */
internal fun planQueueDragEnd(
    queue: List<Song>,
    currentIndex: Int,
    session: QueueDragSession,
    targetPlaybackIndex: Int?,
    cancelled: Boolean,
): QueueDragEndDecision {
    if (cancelled) return QueueDragEndDecision.NoOp
    if (targetPlaybackIndex == null) return QueueDragEndDecision.NoOp
    // Re-resolve the grabbed occurrence against the live queue — never the stale start index.
    val source = resolveDraggedOccurrenceIndex(queue, session) ?: return QueueDragEndDecision.NoOp
    // Source and target must both remain strictly in Up Next (playback has not overtaken; never
    // move a played/current/history entry).
    if (source <= currentIndex) return QueueDragEndDecision.NoOp
    if (targetPlaybackIndex <= currentIndex || targetPlaybackIndex !in queue.indices) {
        return QueueDragEndDecision.NoOp
    }
    if (targetPlaybackIndex == source) return QueueDragEndDecision.NoOp
    return QueueDragEndDecision.Commit(fromPlaybackIndex = source, toPlaybackIndex = targetPlaybackIndex)
}

/**
 * Whether the "Playing now" auto-scroll should run for the current [currentIndex] change. It must
 * be suppressed while a drag is active so a track change (including auto-advance) does not yank the
 * viewport out from under the gesture.
 */
internal fun shouldAutoScrollToPlayingNow(currentIndex: Int, isDragActive: Boolean): Boolean =
    currentIndex >= 0 && !isDragActive
