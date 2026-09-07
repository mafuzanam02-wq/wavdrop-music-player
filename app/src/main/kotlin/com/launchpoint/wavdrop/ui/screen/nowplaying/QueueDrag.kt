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

// ── Phase B: interaction-polish geometry (all pure, all unit-tested) ────────────────────────────

/**
 * Progressive edge-scroll velocity in **pixels per second** for a pointer at [pointerY] within a
 * viewport of height [viewportHeightPx]. Zero in the middle band; ramps quadratically from
 * [minSpeedPxPerSec] at the inner edge of the [edgeZonePx] band to [maxSpeedPxPerSec] at the very
 * viewport edge. Negative near the top (scroll up), positive near the bottom (scroll down). The
 * caller converts this to a per-frame delta using real elapsed time, so behaviour is frame-rate
 * independent. The zone is clamped to half the viewport so the two bands never overlap on short
 * viewports, and depth is clamped so a pointer dragged past the edge stays at [maxSpeedPxPerSec].
 */
internal fun edgeScrollVelocityPxPerSec(
    pointerY: Float,
    viewportHeightPx: Float,
    edgeZonePx: Float,
    minSpeedPxPerSec: Float,
    maxSpeedPxPerSec: Float,
): Float {
    if (viewportHeightPx <= 0f || edgeZonePx <= 0f) return 0f
    val zone = edgeZonePx.coerceAtMost(viewportHeightPx / 2f)
    if (pointerY < zone) {
        val depth = ((zone - pointerY) / zone).coerceIn(0f, 1f)
        return -speedForEdgeDepth(depth, minSpeedPxPerSec, maxSpeedPxPerSec)
    }
    val bottomThreshold = viewportHeightPx - zone
    if (pointerY > bottomThreshold) {
        val depth = ((pointerY - bottomThreshold) / zone).coerceIn(0f, 1f)
        return speedForEdgeDepth(depth, minSpeedPxPerSec, maxSpeedPxPerSec)
    }
    return 0f
}

private fun speedForEdgeDepth(depth: Float, minSpeed: Float, maxSpeed: Float): Float =
    minSpeed + (maxSpeed - minSpeed) * depth * depth

/** Which edge of a target row the drop-insertion marker sits on. */
internal enum class QueueInsertionEdge { Top, Bottom }

/**
 * The insertion-marker edge to draw on the row at [rowPlaybackIndex], or null if that row is not the
 * current drop target (or there is no move). This mirrors [planQueueDragEnd]'s move-to-index commit
 * exactly (`removeAt(from); add(to)`):
 * - moving **down** ([targetPlaybackIndex] > [sourcePlaybackIndex]) the dragged item lands just after
 *   the row currently at the target → marker on that row's **bottom**;
 * - moving **up** the item lands just before it → marker on the row's **top**.
 * The marker never points at the source row (target == source is a no-op).
 */
internal fun queueInsertionEdgeFor(
    rowPlaybackIndex: Int,
    sourcePlaybackIndex: Int,
    targetPlaybackIndex: Int,
): QueueInsertionEdge? {
    if (sourcePlaybackIndex == targetPlaybackIndex) return null
    if (rowPlaybackIndex != targetPlaybackIndex) return null
    return if (targetPlaybackIndex > sourcePlaybackIndex) QueueInsertionEdge.Bottom else QueueInsertionEdge.Top
}

/**
 * Compact "N of M" label for the drop destination's 1-based position within Up Next, or null if the
 * target is outside Up Next. Purely index arithmetic — duplicate song ids cannot affect it.
 */
internal fun queueMovePositionLabel(
    targetPlaybackIndex: Int,
    upNextStartIndex: Int,
    upNextCount: Int,
): String? {
    if (upNextCount <= 0) return null
    val position = targetPlaybackIndex - upNextStartIndex + 1
    if (position < 1 || position > upNextCount) return null
    return "$position of $upNextCount"
}

// ── Phase C: "Move to…" destination target math (pure, unit-tested) ──────────────────────────────
// All three return a *playback index* in the active playbackQueue for the caller to feed straight
// into the same authoritative move path (planQueueDragEnd → onMoveItemTo → moveQueueItemTo). None of
// them touch libraryQueue, song-id lookup, or shuffle. "Already there" and "target == source" become
// a NoOp downstream in planQueueDragEnd / PlayerController, so they are not special-cased here.

/**
 * Converts a **1-based position within Up Next** to an absolute playback index, or null if the
 * position is out of range (blank/zero/negative/too-large are all rejected by the caller producing
 * an out-of-range Int, and an empty Up Next yields null). Up Next starts at `currentIndex + 1`.
 *
 * e.g. currentIndex=20, queueSize=121 (Up Next count 100): position 1 → 21, position 25 → 45,
 * position 100 → 120 (the last item).
 */
internal fun upNextPositionToPlaybackIndex(
    position1Based: Int,
    currentIndex: Int,
    queueSize: Int,
): Int? {
    val upNextStart = currentIndex + 1
    val upNextCount = queueSize - upNextStart
    if (upNextCount <= 0) return null
    if (position1Based < 1 || position1Based > upNextCount) return null
    return upNextStart + (position1Based - 1)
}

/** Playback index of the first Up Next slot (immediately after current). */
internal fun topOfUpNextPlaybackIndex(currentIndex: Int): Int = currentIndex + 1

/** Playback index of the last queue slot. */
internal fun endOfQueuePlaybackIndex(queueSize: Int): Int = queueSize - 1

// ── Phase B.1: post-drop confirmation flash (pure, unit-tested) ──────────────────────────────────

/**
 * Short-lived token identifying the row to flash after a successful reorder. A move-to-index commit
 * (`removeAt(from); add(to)`) places the moved item at *exactly* [expectedPlaybackIndex], which is a
 * single list slot holding precisely that item — so index + [songId] uniquely identify the moved
 * occurrence at its new position even when duplicates exist. [songId] is only a guard against a stale
 * or not-yet-applied async queue state; it is never used to *search* for the row.
 */
internal data class QueueDropConfirmation(
    val songId: Long,
    val expectedPlaybackIndex: Int,
)

/**
 * True once the live [queue] reflects the committed move: the destination slot now holds the moved
 * song. Used to wait out the asynchronous queue-state update before flashing, and to discard a
 * confirmation whose destination was overwritten by an unrelated mutation.
 */
internal fun queueDropConfirmationMatches(queue: List<Song>, confirmation: QueueDropConfirmation): Boolean =
    queue.getOrNull(confirmation.expectedPlaybackIndex)?.id == confirmation.songId

/**
 * Whether the row at [rowPlaybackIndex] holding [rowSongId] is the confirmed drop target. Requires
 * both the exact index and a matching song id, so a coincidental duplicate elsewhere never flashes
 * and a stale index whose song changed is rejected.
 */
internal fun shouldFlashDroppedRow(
    confirmation: QueueDropConfirmation?,
    rowPlaybackIndex: Int,
    rowSongId: Long,
): Boolean =
    confirmation != null &&
        rowPlaybackIndex == confirmation.expectedPlaybackIndex &&
        rowSongId == confirmation.songId

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
