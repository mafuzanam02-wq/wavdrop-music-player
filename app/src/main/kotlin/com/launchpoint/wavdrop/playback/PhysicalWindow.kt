package com.launchpoint.wavdrop.playback

/*
 * P5-A: the pure mapping between LOGICAL playback positions and the PHYSICAL positions of a bounded Media3 window
 * (docs/development/P5-BOUNDED-PHYSICAL-QUEUE-DESIGN.md §4.2, §5). Dormant: nothing in the playback path references this
 * file yet; the physical player still receives the full `playbackQueue`.
 */

/**
 * Identity of one planned piece of window work. A plan is valid only while BOTH numbers still match the live state:
 * - [logicalGeneration] is the revision of the logical queue (`PlayerController.queueGeneration`). A logical edit moves it and
 *   invalidates every plan prepared from the old queue.
 * - [windowGeneration] is the revision of one physical window. Any shift, trim, refill or re-anchor moves it and invalidates a
 *   plan prepared from the previous window, even though the logical queue did not change.
 *
 * The two are deliberately separate: a window shift never mutates the logical queue, and a logical edit does not by itself say
 * anything about which physical items exist.
 */
internal data class PlanToken(val logicalGeneration: Long, val windowGeneration: Long) {

    /**
     * True only when this token was issued for exactly [window] AND the logical queue is still at [currentLogicalGeneration].
     * Negative (invalid) generations are never valid, so a corrupted or wrapped counter cannot accidentally accept stale work.
     */
    fun isValidFor(window: PhysicalWindow, currentLogicalGeneration: Long): Boolean =
        logicalGeneration >= 0L &&
            windowGeneration >= 0L &&
            currentLogicalGeneration >= 0L &&
            logicalGeneration == currentLogicalGeneration &&
            logicalGeneration == window.logicalGeneration &&
            windowGeneration == window.windowGeneration
}

/**
 * An immutable contiguous run of positions of the logical PLAYBACK ORDER, optionally continuing past the last position back at
 * position 0 (a "ring" window, only for repeat-all). Physical index `p` (0 until [length]) is the item Media3 holds at index `p`.
 *
 * The mapping uses occurrence POSITIONS only. A song id, a `MediaItem.mediaId` or a shuffle order never enters it: shuffle is
 * already encoded in the playback order that these positions index. It is O(1), allocates nothing and does not iterate the queue.
 *
 * Illegal states are rejected on construction (see [validate]):
 *  - [queueSize] >= 1, [start] in `[0, queueSize)`;
 *  - 1 <= [length] <= min([queueSize], the configured maximum), so a position can never appear twice;
 *  - [wraps] is true EXACTLY when the run crosses the end of the queue (once). A non-wrapping window never crosses the end and
 *    never uses modulo arithmetic; a wrapping one crosses it exactly once, e.g. `start = 99, queueSize = 100, length = 2` maps
 *    physical 0 -> 99 and physical 1 -> 0;
 *  - both generations are non-negative.
 *
 * Whether a wrapping window is ALLOWED (repeat-all only) is a policy decision made by [PhysicalWindowPolicy]; this type only
 * guarantees that whatever it represents is unambiguous.
 *
 * @param maxLength the configured maximum window length used for validation at construction (not stored).
 */
internal class PhysicalWindow(
    val logicalGeneration: Long,
    val windowGeneration: Long,
    val queueSize: Int,
    val start: Int,
    val length: Int,
    val wraps: Boolean,
    maxLength: Int = PhysicalWindowPolicy.W_MAX,
) {
    init {
        validate(logicalGeneration, windowGeneration, queueSize, start, length, wraps, maxLength)?.let {
            throw IllegalArgumentException(it)
        }
    }

    /** The token that names this exact window revision. */
    val token: PlanToken get() = PlanToken(logicalGeneration, windowGeneration)

    /** Logical position held at physical index 0. */
    val firstLogical: Int get() = start

    /** Logical position held at the last physical index. */
    val lastLogical: Int get() = physicalToLogical(length - 1) ?: error("unreachable: length >= 1")

    /** True when physical 0 is logical 0 and the window does not wrap: there is no logical history behind it. */
    val beginsAtLogicalStart: Boolean get() = !wraps && start == 0

    /** True when the last physical item is the last logical item and the window does not wrap: nothing follows it. */
    val endsAtLogicalEnd: Boolean get() = !wraps && start + length == queueSize // start + length <= queueSize here: no overflow

    /**
     * The logical position for [physicalIndex], or null when it is outside `[0, length)`. Never wraps an invalid index onto
     * another item.
     */
    fun physicalToLogical(physicalIndex: Int): Int? {
        if (physicalIndex < 0 || physicalIndex >= length) return null
        val untilEnd = queueSize - start // >= 1
        // A non-wrapping window has length <= untilEnd, so the second branch is unreachable for it (no modulo for it).
        return if (physicalIndex < untilEnd) start + physicalIndex else physicalIndex - untilEnd
    }

    /** The physical index of logical position [logicalIndex], or null when it is outside the queue or not loaded. */
    fun logicalToPhysical(logicalIndex: Int): Int? {
        if (logicalIndex < 0 || logicalIndex >= queueSize) return null
        return if (logicalIndex >= start) {
            val physical = logicalIndex - start
            if (physical < length) physical else null
        } else {
            if (!wraps) return null
            val physical = (queueSize - start) + logicalIndex // < queueSize: no overflow
            if (physical < length) physical else null
        }
    }

    fun containsLogical(logicalIndex: Int): Boolean = logicalToPhysical(logicalIndex) != null

    /** Items loaded BEFORE the item at [physicalCurrent] (its history), or null when [physicalCurrent] is not in the window. */
    fun historyCount(physicalCurrent: Int): Int? = physicalCurrent.takeIf { it in 0 until length }

    /** Items loaded AFTER the item at [physicalCurrent], or null when [physicalCurrent] is not in the window. */
    fun aheadCount(physicalCurrent: Int): Int? = physicalCurrent.takeIf { it in 0 until length }?.let { length - 1 - it }

    // ── pure geometry edits (no generation change; the caller finalizes with [withNextWindowGeneration]) ────────────────────

    /** Drops [count] items from the FRONT of the window (history), `0 < count < length`. */
    fun trimFront(count: Int, maxLength: Int = PhysicalWindowPolicy.W_MAX): PhysicalWindow {
        require(count in 1 until length) { "trimFront count=$count must be in [1, $length)" }
        var newStart = start.toLong() + count
        if (newStart >= queueSize) newStart -= queueSize
        return geometry(newStart.toInt(), length - count, maxLength)
    }

    /** Drops [count] items from the END of the window, `0 < count < length`. */
    fun trimTail(count: Int, maxLength: Int = PhysicalWindowPolicy.W_MAX): PhysicalWindow {
        require(count in 1 until length) { "trimTail count=$count must be in [1, $length)" }
        return geometry(start, length - count, maxLength)
    }

    /** Adds [count] earlier logical positions in front (history refill). Never wraps backwards past logical 0. */
    fun prepend(count: Int, maxLength: Int = PhysicalWindowPolicy.W_MAX): PhysicalWindow {
        require(count >= 1) { "prepend count=$count must be positive" }
        require(count <= start) { "prepend count=$count exceeds the $start logical positions behind the window" }
        return geometry(start - count, length + count, maxLength)
    }

    /** Adds [count] following logical positions at the end (ahead refill); crossing the queue end makes the window wrap. */
    fun append(count: Int, maxLength: Int = PhysicalWindowPolicy.W_MAX): PhysicalWindow {
        require(count >= 1) { "append count=$count must be positive" }
        return geometry(start, length + count, maxLength)
    }

    /** The same geometry as a new window revision. Throws instead of wrapping when the counter is exhausted. */
    fun withNextWindowGeneration(maxLength: Int = PhysicalWindowPolicy.W_MAX): PhysicalWindow =
        PhysicalWindow(logicalGeneration, nextWindowGeneration(), queueSize, start, length, wraps, maxLength)

    /** The generation a successor window must carry. Never wraps: an exhausted counter is an error, not a reset. */
    fun nextWindowGeneration(): Long {
        check(windowGeneration < Long.MAX_VALUE) { "window generation exhausted" }
        return windowGeneration + 1L
    }

    private fun geometry(newStart: Int, newLength: Int, maxLength: Int): PhysicalWindow =
        PhysicalWindow(
            logicalGeneration, windowGeneration, queueSize, newStart, newLength,
            wraps = newStart.toLong() + newLength > queueSize, maxLength = maxLength,
        )

    override fun equals(other: Any?): Boolean =
        other is PhysicalWindow &&
            other.logicalGeneration == logicalGeneration && other.windowGeneration == windowGeneration &&
            other.queueSize == queueSize && other.start == start && other.length == length && other.wraps == wraps

    override fun hashCode(): Int {
        var h = logicalGeneration.hashCode()
        h = 31 * h + windowGeneration.hashCode()
        h = 31 * h + queueSize
        h = 31 * h + start
        h = 31 * h + length
        h = 31 * h + wraps.hashCode()
        return h
    }

    override fun toString(): String =
        "PhysicalWindow(gen=$logicalGeneration/$windowGeneration, queue=$queueSize, start=$start, length=$length, wraps=$wraps)"

    companion object {
        /** The reason [PhysicalWindow] would reject these arguments, or null when they describe a legal window. */
        fun validate(
            logicalGeneration: Long,
            windowGeneration: Long,
            queueSize: Int,
            start: Int,
            length: Int,
            wraps: Boolean,
            maxLength: Int = PhysicalWindowPolicy.W_MAX,
        ): String? = when {
            logicalGeneration < 0L -> "logicalGeneration=$logicalGeneration must be >= 0"
            windowGeneration < 0L -> "windowGeneration=$windowGeneration must be >= 0"
            maxLength < 1 -> "maxLength=$maxLength must be >= 1"
            queueSize < 1 -> "a window needs a non-empty logical queue (queueSize=$queueSize)"
            start < 0 || start >= queueSize -> "start=$start must be in [0, $queueSize)"
            length < 1 -> "length=$length must be >= 1"
            length > queueSize -> "length=$length exceeds queueSize=$queueSize (an occurrence would appear twice)"
            length > maxLength -> "length=$length exceeds the maximum window length $maxLength"
            wraps != (start.toLong() + length > queueSize) ->
                "wraps=$wraps contradicts start=$start length=$length queueSize=$queueSize " +
                    "(wraps must be true exactly when the window crosses the queue end)"
            else -> null
        }
    }
}
