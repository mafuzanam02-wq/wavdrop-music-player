package com.launchpoint.wavdrop.playback

/*
 * P5-A: the pure window POLICY (docs/development/P5-BOUNDED-PHYSICAL-QUEUE-DESIGN.md §6). Data in, data out: it calls no Media3
 * API, mutates no queue, launches no coroutine and touches no Android service. Scheduling and installing the work it describes
 * belong to later slices (P5-B/C). Dormant: nothing in the playback path references it yet.
 */

/** What the policy decided about growing the window AHEAD of the current item. */
internal sealed interface AheadDecision {
    data class None(val reason: NoAheadReason) : AheadDecision

    /**
     * Append [count] logical positions starting at [firstLogical] (which may be 0 when a repeat-all ring continues past the
     * end). [priority] means ahead fell below the floor: the next explicit transport must refill synchronously first.
     */
    data class Refill(val firstLogical: Int, val count: Int, val priority: Boolean, val token: PlanToken) : AheadDecision
}

internal enum class NoAheadReason {
    /** Ahead is still above the low watermark. */
    Sufficient,

    /** No logical position follows the window (genuine end of the queue, or the ring is closed). */
    NothingToAppend,

    /** The window is already at its maximum length. */
    WindowFull,

    /** The supplied physical current index is not inside the window. */
    InvalidCurrent,
}

/** What the policy decided about the history BEHIND the current item. */
internal sealed interface HistoryDecision {
    data class None(val reason: NoHistoryReason) : HistoryDecision

    /** Drop [count] items from the front (history above the maximum, down to the target). */
    data class Trim(val count: Int, val token: PlanToken) : HistoryDecision

    /**
     * Load [count] earlier positions starting at [firstLogical] in front of the window (history below the minimum). When the
     * window has no room, [tailTrim] far-ahead items are dropped first (never below AHEAD_LOW + 1 ahead), so walking backward
     * can always recover history without exceeding W_MAX.
     */
    data class Prepend(val firstLogical: Int, val count: Int, val tailTrim: Int, val token: PlanToken) : HistoryDecision
}

internal enum class NoHistoryReason { Within, AtLogicalStart, WindowFull, InvalidCurrent }

internal enum class ReanchorReason {
    /** The navigation / jump target is not loaded. */
    TargetOutsideWindow,

    /** Repeat left "all" while the current item sits in the wrapped (post-end) part of a ring window. */
    RepeatChangedInsideWrap,

    /** The plan's inputs (current index, positions) are not provable; never guess, install fresh around the logical current. */
    Unprovable,
}

/** Where a logical navigation target lives relative to the loaded window. */
internal sealed interface Localization {
    data class InWindow(val physicalIndex: Int) : Localization
    data object OutsideWindow : Localization
    data object InvalidTarget : Localization
}

/** The combined, ordered result of [PhysicalWindowPolicy.planAdjustment]. */
internal sealed interface WindowAdjustment {
    /** Nothing to do for the window revision [token]. */
    data class NoChange(val token: PlanToken) : WindowAdjustment

    data class Reanchor(val reason: ReanchorReason) : WindowAdjustment

    /**
     * Apply, in this order and in ONE main-thread turn: [trimTail] (remove wrapped items after repeat left ALL, and any far-ahead
     * items dropped to make room for a history refill), then
     * [trimFront] OR [prependCount] (history), then [appendCount] (ahead). None of these touches the current item.
     * [resultWindow] is the window after the whole plan (one new window generation); [physicalCurrentAfter] is where the
     * current item sits in it. The plan is valid only while [token] is current (see [PlanToken.isValidFor]).
     */
    data class Adjust(
        val token: PlanToken,
        val trimTail: Int,
        val trimFront: Int,
        val prependFirstLogical: Int?,
        val prependCount: Int,
        val appendFirstLogical: Int?,
        val appendCount: Int,
        val priority: Boolean,
        val resultWindow: PhysicalWindow,
        val physicalCurrentAfter: Int,
    ) : WindowAdjustment
}

/** A step of manual or automatic navigation, classified against the genuine queue boundaries. */
internal sealed interface NavigationStep {
    data class Linear(val logicalIndex: Int) : NavigationStep

    /** Repeat-all wrapped across the queue boundary (last -> first for next, first -> last for previous). */
    data class RepeatAllWrap(val logicalIndex: Int) : NavigationStep

    /** There is no further logical position in that direction. */
    data object Edge : NavigationStep
}

/**
 * The window policy. Constants are CANDIDATES for later device tuning (design §6.1), kept in one place; every other file must
 * read them from here (or from a [Config]) instead of re-declaring them.
 *
 * Assumptions where the design left a decision open (each chosen conservatively and deterministically):
 *  - **A1 refill trigger.** Ahead refills when `ahead <= AHEAD_LOW` (the design: "refill starts while 30 remain"), and is
 *    *priority* (synchronous before the next transport) when `ahead < AHEAD_FLOOR`.
 *  - **A2 ahead never exceeds AHEAD_TARGET and the whole window never exceeds W_MAX.** History may grow to HISTORY_MAX (40), and
 *    40 + 1 + 79 > W_MAX (100), so the ahead refill is additionally capped by the room left in the window.
 *  - **A3 history is trimmed, never refilled, past the logical start.** Trim/prepend never wrap backwards behind logical 0.
 *  - **A4 only AHEAD wraps.** A ring window is built only for repeat-all, and only forward past the end; it never contains
 *    history from before logical 0 and never reaches its own start.
 *  - **A5 initial window.** `history = min(current, HISTORY_TARGET)`, `ahead = min(AHEAD_TARGET, room, available)`; near the
 *    queue end the window is simply shorter (no history back-fill), so it is deterministic and never larger than needed.
 *  - **A6 repeat ONE is shaped like OFF** (the physical player's native repeat-one handles the loop).
 */
internal class PhysicalWindowPolicy(val config: Config = Config.DEFAULT) {

    /** The tunable numbers. Validated so every relationship the planner relies on holds by construction. */
    data class Config(
        val wMax: Int = W_MAX,
        val historyTarget: Int = HISTORY_TARGET,
        val historyMax: Int = HISTORY_MAX,
        val historyMin: Int = HISTORY_MIN,
        val aheadTarget: Int = AHEAD_TARGET,
        val aheadLow: Int = AHEAD_LOW,
        val aheadFloor: Int = AHEAD_FLOOR,
        val refillBatch: Int = REFILL_BATCH,
        val fullModeMax: Int = FULL_MODE_MAX,
    ) {
        init {
            require(wMax >= 1) { "wMax=$wMax" }
            require(historyMin >= 0 && historyMin <= historyTarget && historyTarget <= historyMax) {
                "history watermarks must satisfy 0 <= min($historyMin) <= target($historyTarget) <= max($historyMax)"
            }
            require(aheadFloor >= 1 && aheadFloor < aheadLow && aheadLow < aheadTarget) {
                "ahead watermarks must satisfy 1 <= floor($aheadFloor) < low($aheadLow) < target($aheadTarget)"
            }
            require(refillBatch >= 1) { "refillBatch=$refillBatch" }
            require(historyTarget.toLong() + 1L + aheadTarget <= wMax) {
                "a freshly trimmed window (history target + current + ahead target) must fit in wMax"
            }
            require(fullModeMax.toLong() >= 2L * wMax) {
                "fullModeMax=$fullModeMax must be >= 2 * wMax so a ring window can never hold an occurrence twice"
            }
        }

        companion object {
            val DEFAULT = Config()
        }
    }

    // ── window construction ─────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * The first window for a queue of [queueSize] with [logicalCurrent] playing. Null when the inputs are not a legal
     * position (empty queue, current outside it, negative generation): the caller must not install anything.
     */
    fun initialWindow(
        queueSize: Int,
        logicalCurrent: Int,
        repeatMode: RepeatMode,
        logicalGeneration: Long,
        windowGeneration: Long,
    ): PhysicalWindow? {
        if (queueSize < 1 || logicalCurrent !in 0 until queueSize) return null
        if (logicalGeneration < 0L || windowGeneration < 0L) return null
        val history = minOf(logicalCurrent, config.historyTarget)
        val start = logicalCurrent - history
        val room = config.wMax - 1 - history // >= aheadTarget by Config validation
        val forwardAvailable = (queueSize - 1 - logicalCurrent).toLong() + (if (repeatMode == RepeatMode.ALL) start else 0)
        val ahead = minOf(config.aheadTarget.toLong(), room.toLong(), forwardAvailable).toInt()
        val length = history + 1 + ahead // <= queueSize: history + ahead cannot exceed the other positions of the ring
        return PhysicalWindow(
            logicalGeneration, windowGeneration, queueSize, start, length,
            wraps = start.toLong() + length > queueSize, maxLength = config.wMax,
        )
    }

    /** Whether a queue of [queueSize] is bounded or full is decided by [PhysicalQueuePolicy.modeFor], not here. */
    val fullModeMax: Int get() = config.fullModeMax

    // ── ahead ───────────────────────────────────────────────────────────────────────────────────────────────────────────

    /** How many logical positions could still be appended after [window], and the first of them. (0, ignored) when none. */
    fun appendable(window: PhysicalWindow, repeatMode: RepeatMode): Pair<Int, Int> {
        val n = window.queueSize
        val repeatAll = repeatMode == RepeatMode.ALL
        val end = window.start.toLong() + window.length
        if (!window.wraps) {
            return when {
                end < n -> end.toInt() to ((n - end) + (if (repeatAll) window.start else 0)).toInt()
                repeatAll && window.start > 0 -> 0 to window.start // ring continues at 0 and stops before the window start
                else -> 0 to 0
            }
        }
        if (!repeatAll) return 0 to 0 // a ring window outside repeat-all is reconciled by [planAdjustment], never extended
        val wrappedEnd = (end - n).toInt()
        return wrappedEnd to (window.start - wrappedEnd)
    }

    fun refillAhead(window: PhysicalWindow, physicalCurrent: Int, repeatMode: RepeatMode): AheadDecision {
        val ahead = window.aheadCount(physicalCurrent) ?: return AheadDecision.None(NoAheadReason.InvalidCurrent)
        if (ahead > config.aheadLow) return AheadDecision.None(NoAheadReason.Sufficient)
        val (first, available) = appendable(window, repeatMode)
        if (available <= 0) return AheadDecision.None(NoAheadReason.NothingToAppend)
        val roomInWindow = config.wMax - window.length
        if (roomInWindow <= 0) return AheadDecision.None(NoAheadReason.WindowFull)
        val count = minOf(config.refillBatch, config.aheadTarget - ahead, available, roomInWindow)
        if (count <= 0) return AheadDecision.None(NoAheadReason.Sufficient)
        return AheadDecision.Refill(
            firstLogical = first,
            count = count,
            priority = ahead < config.aheadFloor,
            token = window.token,
        )
    }

    // ── history ─────────────────────────────────────────────────────────────────────────────────────────────────────────

    fun historyDecision(window: PhysicalWindow, physicalCurrent: Int): HistoryDecision {
        val history = window.historyCount(physicalCurrent) ?: return HistoryDecision.None(NoHistoryReason.InvalidCurrent)
        if (history > config.historyMax) {
            return HistoryDecision.Trim(count = history - config.historyTarget, token = window.token)
        }
        if (history >= config.historyMin) return HistoryDecision.None(NoHistoryReason.Within)
        if (window.start == 0) return HistoryDecision.None(NoHistoryReason.AtLogicalStart)
        val want = minOf(config.refillBatch, config.historyTarget - history, window.start)
        // Also bounded by the positions NOT yet in the window, so a ring window can never take a position twice.
        val room = minOf(config.wMax - window.length, window.queueSize - window.length).coerceAtLeast(0)
        var tailTrim = 0
        if (room < want) {
            val ahead = window.length - 1 - physicalCurrent
            tailTrim = minOf(want - room, (ahead - (config.aheadLow + 1)).coerceAtLeast(0))
        }
        val count = minOf(want, room + tailTrim)
        if (count <= 0) return HistoryDecision.None(NoHistoryReason.WindowFull)
        return HistoryDecision.Prepend(firstLogical = window.start - count, count = count, tailTrim = tailTrim, token = window.token)
    }

    // ── navigation ──────────────────────────────────────────────────────────────────────────────────────────────────────

    /** Whether [logicalTarget] can be reached by a plain physical seek, or the window must be re-anchored around it. */
    fun localize(window: PhysicalWindow, logicalTarget: Int): Localization {
        if (logicalTarget < 0 || logicalTarget >= window.queueSize) return Localization.InvalidTarget
        val physical = window.logicalToPhysical(logicalTarget) ?: return Localization.OutsideWindow
        return Localization.InWindow(physical)
    }

    /** Manual/automatic NEXT, classified against the genuine queue boundaries (repeat ONE behaves like OFF for manual next). */
    fun nextStep(queueSize: Int, logicalCurrent: Int, repeatMode: RepeatMode): NavigationStep {
        val next = QueueNavigator.nextIndex(queueSize, logicalCurrent, repeatMode) ?: return NavigationStep.Edge
        return if (next == logicalCurrent + 1) NavigationStep.Linear(next) else NavigationStep.RepeatAllWrap(next)
    }

    /** PREVIOUS (move to the previous item, not "restart current"), classified against the boundaries. */
    fun previousStep(queueSize: Int, logicalCurrent: Int, repeatMode: RepeatMode): NavigationStep = when {
        queueSize < 1 || logicalCurrent !in 0 until queueSize -> NavigationStep.Edge
        logicalCurrent > 0 -> NavigationStep.Linear(logicalCurrent - 1)
        repeatMode == RepeatMode.ALL && queueSize > 1 -> NavigationStep.RepeatAllWrap(queueSize - 1)
        else -> NavigationStep.Edge
    }

    fun isLogicalStart(logicalCurrent: Int): Boolean = logicalCurrent == 0

    fun isLogicalEnd(queueSize: Int, logicalCurrent: Int): Boolean = queueSize >= 1 && logicalCurrent == queueSize - 1

    // ── combined plan ───────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * One deterministic adjustment of [window] given where the current item sits ([physicalCurrent]) and the repeat mode.
     * Order: (1) a ring window outside repeat-all loses its wrapped tail, (2) history is trimmed or refilled, (3) ahead is
     * refilled against the window as it stands AFTER (1) and (2). The current item is never removed or replaced.
     */
    fun planAdjustment(window: PhysicalWindow, physicalCurrent: Int, repeatMode: RepeatMode): WindowAdjustment {
        if (physicalCurrent !in 0 until window.length) return WindowAdjustment.Reanchor(ReanchorReason.Unprovable)
        var w = window
        var current = physicalCurrent
        var trimTail = 0
        var trimFront = 0
        var prependFirst: Int? = null
        var prependCount = 0

        if (w.wraps && repeatMode != RepeatMode.ALL) {
            val wrapped = (w.start.toLong() + w.length - w.queueSize).toInt()
            if (current >= w.length - wrapped) return WindowAdjustment.Reanchor(ReanchorReason.RepeatChangedInsideWrap)
            trimTail = wrapped
            w = w.trimTail(wrapped, config.wMax)
        }

        when (val history = historyDecision(w, current)) {
            is HistoryDecision.Trim -> {
                trimFront = history.count
                w = w.trimFront(history.count, config.wMax)
                current -= history.count
            }
            is HistoryDecision.Prepend -> {
                if (history.tailTrim > 0) {
                    trimTail += history.tailTrim
                    w = w.trimTail(history.tailTrim, config.wMax)
                }
                prependFirst = history.firstLogical
                prependCount = history.count
                w = w.prepend(history.count, config.wMax)
                current += history.count
            }
            is HistoryDecision.None -> Unit
        }

        var appendFirst: Int? = null
        var appendCount = 0
        var priority = false
        when (val ahead = refillAhead(w, current, repeatMode)) {
            is AheadDecision.Refill -> {
                appendFirst = ahead.firstLogical
                appendCount = ahead.count
                priority = ahead.priority
                w = w.append(ahead.count, config.wMax)
            }
            is AheadDecision.None -> Unit
        }

        if (trimTail == 0 && trimFront == 0 && prependCount == 0 && appendCount == 0) {
            return WindowAdjustment.NoChange(window.token)
        }
        return WindowAdjustment.Adjust(
            token = window.token,
            trimTail = trimTail,
            trimFront = trimFront,
            prependFirstLogical = prependFirst,
            prependCount = prependCount,
            appendFirstLogical = appendFirst,
            appendCount = appendCount,
            priority = priority,
            resultWindow = w.withNextWindowGeneration(config.wMax),
            physicalCurrentAfter = current,
        )
    }

    companion object {
        /** Maximum loaded items in a window (design §6.1: initial candidate 100). */
        const val W_MAX = 100
        const val HISTORY_TARGET = 20
        const val HISTORY_MAX = 40
        const val HISTORY_MIN = 10
        const val AHEAD_TARGET = 79
        const val AHEAD_LOW = 30
        const val AHEAD_FLOOR = 8
        const val REFILL_BATCH = 25

        /** Queues up to this size never use a bounded window. Must be >= 2 * W_MAX. */
        const val FULL_MODE_MAX = 300

        val DEFAULT = PhysicalWindowPolicy()
    }
}
