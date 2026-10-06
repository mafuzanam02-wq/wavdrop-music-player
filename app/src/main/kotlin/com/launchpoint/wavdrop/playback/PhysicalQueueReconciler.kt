package com.launchpoint.wavdrop.playback

import com.launchpoint.wavdrop.data.model.Song

/**
 * The slice of the Media3 timeline the reconciler needs. Items are identified by the mediaId (song id) the
 * PlaybackMediaItem carries; occurrence identity is POSITIONAL (index), never "the first item with this id".
 * `replace` is a single Media3 mutation (`replaceMediaItems`); an empty list removes, `from == to` inserts.
 */
internal interface PhysicalQueueTimeline {
    val itemCount: Int
    val currentIndex: Int
    fun songIdAt(index: Int): Long?
    fun replace(fromIndex: Int, toIndexExclusive: Int, songs: List<Song>)
}

/** Where a background pass resumes (logical indices). Valid only for the [current] occurrence it was created for. */
internal data class ReconcileCursor(
    val current: Int,
    val suffixNext: Int,
    val prefixNext: Int,
)

internal sealed interface QueueReconcileOutcome {
    /** Physical ids equal the logical queue at every index: the dirty flag may be cleared. */
    data object Completed : QueueReconcileOutcome

    /**
     * Bounded progress. Physical index = logical index + [physicalOffset] (always <= 0: surplus items before the current
     * one are removed immediately, a shortfall is filled in chunks). [verified] are LOGICAL indices (always including the
     * current one) whose physical item equals the logical item: the only places the controller index may be trusted
     * while the queue is still dirty.
     */
    data class InProgress(
        val verified: Set<Int>,
        val physicalOffset: Int,
        val cursor: ReconcileCursor,
    ) : QueueReconcileOutcome

    /** The current occurrence or the timeline cannot be trusted; the caller must fall back conservatively. */
    data class Unsafe(val reason: String) : QueueReconcileOutcome
}

/**
 * THE single authority for repairing a physical Media3 timeline that diverged from the logical playbackQueue
 * (large-queue hardening). It never touches the CURRENT item (no restart, no play/pause/seek/prepare, no transition
 * callbacks) and only issues `replace` mutations of bounded size. It works from the facts it reads each call (there is no
 * stale plan to race with): it removes surplus items before the current one, repairs a priority window first (what
 * Next/Previous/jump/natural boundary need), then repairs the rest in chunks outward from the current item.
 *
 * Command priority: an explicit transport command calls [reconcile] with `budget = 0` and the target in
 * `priorityIndices` (synchronous, bounded by the window, never waiting for the background pass); queue mutations and
 * navigation restart the background pass, which is re-derived from the live facts.
 */
internal class PhysicalQueueReconciler(
    private val chunkSize: Int = DEFAULT_CHUNK_SIZE,
    private val windowAhead: Int = DEFAULT_WINDOW_AHEAD,
    private val maxPriorityGap: Int = DEFAULT_MAX_PRIORITY_GAP,
) {
    fun reconcile(
        timeline: PhysicalQueueTimeline,
        queue: List<Song>,
        logicalCurrent: Int,
        priorityIndices: Collection<Int> = emptyList(),
        budget: Int = 0,
        cursor: ReconcileCursor? = null,
    ): QueueReconcileOutcome {
        val n = queue.size
        if (logicalCurrent !in 0 until n) return QueueReconcileOutcome.Unsafe("logical_current_out_of_range")
        val startPhysical = timeline.currentIndex
        if (startPhysical !in 0 until timeline.itemCount) return QueueReconcileOutcome.Unsafe("physical_current_missing")
        if (timeline.songIdAt(startPhysical) != queue[logicalCurrent].id) {
            return QueueReconcileOutcome.Unsafe("physical_current_mismatch")
        }

        // 1. Surplus items before the current one are removed (no materialization). A shortfall is handled lazily below.
        if (startPhysical > logicalCurrent) timeline.replace(0, startPhysical - logicalCurrent, emptyList())
        fun offset(): Int = timeline.currentIndex - logicalCurrent
        if (offset() > 0) return QueueReconcileOutcome.Unsafe("current_index_not_aligned")
        // Surplus items after the logical end carry no information.
        if (timeline.itemCount > n + offset()) timeline.replace(n + offset(), timeline.itemCount, emptyList())

        // 2. Priority window: previous, the next [windowAhead] items and any caller-named targets (jump / wrap).
        val priority = sortedSetOf<Int>()
        fun addWindow(center: Int) {
            for (i in -1..windowAhead) {
                val l = center + i
                if (l in 0 until n && l != logicalCurrent) priority += l
            }
        }
        addWindow(logicalCurrent)
        priorityIndices.forEach { if (it in 0 until n && it != logicalCurrent) addWindow(it) }
        for (l in priority) {
            if (!ensureLogical(timeline, queue, logicalCurrent, l)) {
                return QueueReconcileOutcome.Unsafe("priority_gap_too_large")
            }
        }
        val verified = HashSet<Int>(priority.size + 1)
        verified += logicalCurrent
        verified.addAll(priority)

        // 3. Background chunks, outward from the current item. `budget` bounds the positions EXAMINED per call (so id reads
        //    and writes are both bounded); every replace is at most [chunkSize] items.
        val resume = cursor?.takeIf { it.current == logicalCurrent }
        var suffixNext = resume?.suffixNext ?: (logicalCurrent + windowAhead + 1)
        var prefixNext = resume?.prefixNext ?: (logicalCurrent - 2)
        var examined = 0
        while (examined < budget && suffixNext < n) {
            val end = minOf(n, suffixNext + minOf(budget - examined, chunkSize))
            val verifiedTo = repairSuffix(timeline, queue, logicalCurrent, suffixNext, end)
            examined += maxOf(1, verifiedTo - suffixNext)
            suffixNext = verifiedTo // may move back when the timeline was short: missing items are appended contiguously
        }
        while (examined < budget && prefixNext >= 0) {
            val off = offset()
            if (prefixNext + off < 0) {
                // The physical prefix is shorter than the logical one: add the missing items right before the oldest
                // physical item (they are contiguous with it), at most one chunk per step.
                val boundary = -off // logical index mapped to physical 0
                val first = maxOf(0, boundary - minOf(budget - examined, chunkSize))
                timeline.replace(0, 0, queue.subList(first, boundary))
                examined += boundary - first
                prefixNext = first - 1
            } else {
                val lowest = maxOf(0, -off, prefixNext - minOf(budget - examined, chunkSize) + 1)
                repairRange(timeline, queue, lowest + off, prefixNext + 1 + off, off)
                examined += prefixNext + 1 - lowest
                prefixNext = lowest - 1
            }
        }

        if (suffixNext >= n && prefixNext < 0 && offset() == 0 && timeline.itemCount == n) {
            return QueueReconcileOutcome.Completed
        }
        return QueueReconcileOutcome.InProgress(
            verified = verified,
            physicalOffset = offset(),
            cursor = ReconcileCursor(logicalCurrent, suffixNext, prefixNext),
        )
    }

    /**
     * Ensures the physical item for logical index [l] equals the logical one, filling a shortfall before the oldest
     * physical item or after the newest one when the gap is within the cap. Returns false when the gap is over the cap.
     */
    private fun ensureLogical(timeline: PhysicalQueueTimeline, queue: List<Song>, logicalCurrent: Int, l: Int): Boolean {
        var physical = l + (timeline.currentIndex - logicalCurrent)
        if (physical < 0) {
            val missing = -physical
            if (missing > maxPriorityGap) return false
            timeline.replace(0, 0, queue.subList(l, l + missing)) // logical [l, l + missing) precede the oldest physical item
            physical = l + (timeline.currentIndex - logicalCurrent)
        }
        val count = timeline.itemCount
        if (physical >= count) {
            val needed = physical + 1 - count
            if (needed > maxPriorityGap) return false
            val firstLogical = count - (timeline.currentIndex - logicalCurrent)
            timeline.replace(count, count, queue.subList(firstLogical, l + 1))
            return true
        }
        if (physical == timeline.currentIndex) return true
        if (timeline.songIdAt(physical) != queue[l].id) timeline.replace(physical, physical + 1, listOf(queue[l]))
        return true
    }

    /**
     * Repairs logical [from, end) of the suffix, appending when the timeline is short of it (an append is capped at one
     * chunk). Returns the logical position up to which the suffix is now verified.
     */
    private fun repairSuffix(
        timeline: PhysicalQueueTimeline,
        queue: List<Song>,
        logicalCurrent: Int,
        from: Int,
        end: Int,
    ): Int {
        val off = timeline.currentIndex - logicalCurrent
        val count = timeline.itemCount
        if (count >= end + off) {
            repairRange(timeline, queue, from + off, end + off, off)
            return end
        }
        repairRange(timeline, queue, from + off, count, off)
        val firstMissing = timeline.itemCount - off // logical index of the next physical slot
        val appendEnd = minOf(end, firstMissing + chunkSize)
        timeline.replace(timeline.itemCount, timeline.itemCount, queue.subList(firstMissing, appendEnd))
        return appendEnd
    }

    /** Replaces each maximal mismatching run inside physical [from, toExclusive) with the logical items (logical = physical - offset). */
    private fun repairRange(timeline: PhysicalQueueTimeline, queue: List<Song>, from: Int, toExclusive: Int, offset: Int) {
        var i = from
        val end = minOf(toExclusive, timeline.itemCount, queue.size + offset)
        while (i < end) {
            if (i == timeline.currentIndex || timeline.songIdAt(i) == queue[i - offset].id) {
                i++
                continue
            }
            var j = i + 1
            while (j < end && j != timeline.currentIndex && timeline.songIdAt(j) != queue[j - offset].id) j++
            timeline.replace(i, j, queue.subList(i - offset, j - offset))
            i = j
        }
    }

    companion object {
        const val DEFAULT_CHUNK_SIZE = 256
        const val DEFAULT_WINDOW_AHEAD = 4
        const val DEFAULT_MAX_PRIORITY_GAP = 1024
    }
}

/**
 * A natural advance (Media3 moved on by itself) is pure native gapless while the queue is still dirty only if it landed on a
 * LOGICAL index the reconciler verified (physical = logical + [physicalOffset]) whose media id equals the logical song.
 */
internal fun isVerifiedPhysicalLanding(
    queue: List<Song>,
    controllerIndex: Int,
    controllerSongId: Long?,
    physicalOffset: Int,
    verifiedLogicalIndices: Set<Int>,
): Boolean {
    if (controllerSongId == null) return false
    val logical = controllerIndex - physicalOffset
    return logical in verifiedLogicalIndices && queue.getOrNull(logical)?.id == controllerSongId
}

/**
 * The production adapter between the reconciler and any Media3 [Player] (the app's MediaController, or a real ExoPlayer in
 * tests): the same reads PlayerController uses, with the mutation supplied by the caller (measured, materializing).
 */
internal class PlayerQueueTimeline(
    private val player: androidx.media3.common.Player,
    private val replaceItems: (fromIndex: Int, toIndexExclusive: Int, songs: List<Song>) -> Unit,
) : PhysicalQueueTimeline {
    override val itemCount: Int get() = player.mediaItemCount
    override val currentIndex: Int get() = player.currentMediaItemIndex
    override fun songIdAt(index: Int): Long? = player.getMediaItemAt(index).mediaId.toLongOrNull()
    override fun replace(fromIndex: Int, toIndexExclusive: Int, songs: List<Song>) = replaceItems(fromIndex, toIndexExclusive, songs)
}
