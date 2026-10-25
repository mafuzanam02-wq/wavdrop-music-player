package com.launchpoint.wavdrop.playback

import com.launchpoint.wavdrop.data.model.Song

/*
 * P5-D: the pure classification of a LOGICAL queue mutation against a VERIFIED physical window
 * (docs/development/P5-BOUNDED-PHYSICAL-QUEUE-DESIGN.md §7.2). Data in, data out: no Media3, no PlayerController, no side effects.
 *
 * The logical queue is already mutated when this runs; the question is what the bounded physical window must do about it. The
 * answer is derived from ONE comparison - the ids the physical player holds now against the ids the window over the NEW queue
 * should hold - so every mutation (insert, remove, clear, move, swap, shuffle, deletion) is classified by the same rules and none
 * can need an O(queue) physical operation:
 *
 *  - LogicalOnly: the two id sequences are identical. No Media3 playlist operation; the window is only re-expressed over the new
 *    queue (new logical positions, new generation).
 *  - BoundedEdit: the current item survives and the two sides of it differ. A minimal script of removals and insertions (an LCS per
 *    side, at most window-sized), the current item never touched. Removals run first (descending), then insertions (ascending in
 *    final coordinates), so the playlist never exceeds the larger of the old and new window.
 *  - Reanchor: the current occurrence changes (it was removed) or cannot be located: a new bounded window around the exact
 *    intended logical current.
 *  - Recover: the edit cannot be proven safe (no current to anchor on): fail closed, keep the logical queue, recover through the
 *    existing bounded mechanisms.
 */

/** One bounded Media3 playlist operation, applied in list order. Insertions take the songs at the RESULTING window's positions. */
internal sealed interface WindowOp {
    /** `removeMediaItems(fromPhysical, toPhysicalExclusive)` in the evolving playlist's coordinates. */
    data class Remove(val fromPhysical: Int, val toPhysicalExclusive: Int) : WindowOp

    /** `addMediaItems(atPhysical, songs at resulting-window positions [atPhysical, atPhysical + count))`. */
    data class Insert(val atPhysical: Int, val count: Int) : WindowOp
}

internal sealed interface MutationEffect {
    /** Identical physical contents: re-express the window over the new queue, issue no Media3 playlist operation. */
    data class LogicalOnly(val window: PhysicalWindow, val ids: LongArray) : MutationEffect

    /** [ops] around the untouched current item; [window] is the resulting window, the current item sits at [expectedCurrent]. */
    data class BoundedEdit(
        val window: PhysicalWindow,
        val ids: LongArray,
        val ops: List<WindowOp>,
        val expectedCurrent: Int,
    ) : MutationEffect

    /** A new bounded window around the exact logical [current]. */
    data class Reanchor(val current: Int, val reason: String) : MutationEffect

    data class Recover(val reason: String) : MutationEffect
}

internal class WindowMutationPlanner(private val policy: PhysicalWindowPolicy = PhysicalWindowPolicy.DEFAULT) {

    /**
     * @param base the VERIFIED window the physical player was just observed to hold.
     * @param physicalCurrent the physical current index observed in it.
     * @param newCurrent the logical position, in [newQueue], of the occurrence that is physically current; null when that occurrence
     *   no longer exists.
     * @param continuation where to continue when the current occurrence no longer exists (the deletion-continuation choice).
     */
    fun plan(
        base: InstalledWindow,
        physicalCurrent: Int,
        newCurrent: Int?,
        continuation: Int?,
        newQueue: List<Song>,
        repeatMode: RepeatMode,
        logicalGeneration: Long,
        windowGeneration: Long,
    ): MutationEffect {
        val n = newQueue.size
        if (n < 1) return MutationEffect.Recover("empty_queue")
        if (newCurrent == null) {
            val target = continuation?.takeIf { it in 0 until n } ?: return MutationEffect.Recover("current_removed_without_continuation")
            return MutationEffect.Reanchor(target, "current_occurrence_removed")
        }
        if (newCurrent !in 0 until n) return MutationEffect.Recover("current_outside_new_queue")
        val oldLength = base.installedCount
        if (physicalCurrent !in 0 until oldLength) return MutationEffect.Recover("physical_current_outside_window")

        // Keep the shape of the verified window around the current item: the same history and ahead counts, clamped by what the
        // new queue can supply. Later upkeep (the P5-C policy) restores the watermarks; this only keeps the swap minimal.
        val maxLength = policy.config.wMax
        val history = minOf(physicalCurrent, newCurrent)
        val start = newCurrent - history
        val forwardAvailable = (n - 1 - newCurrent).toLong() + (if (repeatMode == RepeatMode.ALL) start else 0)
        val ahead = minOf((oldLength - 1 - physicalCurrent).toLong(), (maxLength - 1 - history).toLong(), forwardAvailable).toInt()
        val length = history + 1 + ahead
        if (length > n) return MutationEffect.Recover("window_larger_than_queue")
        val window = PhysicalWindow(
            logicalGeneration, windowGeneration, n, start, length,
            wraps = start.toLong() + length > n, maxLength = maxLength,
        )
        val target = LongArray(length) { p -> newQueue[window.physicalToLogical(p) ?: error("unmapped $p")].id }
        val old = LongArray(oldLength) { p -> base.expectedSongIdAt(p) ?: error("no id at $p") }

        if (length == oldLength && history == physicalCurrent && target.contentEquals(old)) {
            return MutationEffect.LogicalOnly(window, target)
        }

        // The current item is the pivot: each side is aligned on its own, so the pivot can never be removed or moved.
        val removals = ArrayList<IntRange>()
        val insertions = ArrayList<IntRange>()
        align(old, 0, physicalCurrent, target, 0, history, removals, insertions)
        align(old, physicalCurrent + 1, oldLength, target, history + 1, length, removals, insertions)
        val ops = ArrayList<WindowOp>(removals.size + insertions.size)
        // Removals first, descending, so earlier indices stay valid; insertions after, ascending in FINAL coordinates, so each lands
        // where every earlier entry is already final.
        removals.sortedByDescending { it.first }.forEach { ops += WindowOp.Remove(it.first, it.last + 1) }
        insertions.sortedBy { it.first }.forEach { ops += WindowOp.Insert(it.first, it.last - it.first + 1) }
        if (ops.any { (it is WindowOp.Insert && it.count > maxLength - 1) || (it is WindowOp.Remove && it.toPhysicalExclusive - it.fromPhysical > maxLength - 1) }) {
            return MutationEffect.Recover("unbounded_operation")
        }
        return MutationEffect.BoundedEdit(window, target, ops, expectedCurrent = history)
    }

    /**
     * Longest-common-subsequence alignment of old `[oFrom, oTo)` against new `[nFrom, nTo)` (both at most a window long). Unmatched
     * old runs are appended to [removals] (old coordinates), unmatched new runs to [insertions] (final coordinates).
     */
    private fun align(
        old: LongArray, oFrom: Int, oTo: Int,
        new: LongArray, nFrom: Int, nTo: Int,
        removals: MutableList<IntRange>, insertions: MutableList<IntRange>,
    ) {
        val m = oTo - oFrom
        val k = nTo - nFrom
        // lcs[i][j]: length of the LCS of old[oFrom+i..] and new[nFrom+j..]
        val lcs = Array(m + 1) { IntArray(k + 1) }
        for (i in m - 1 downTo 0) {
            for (j in k - 1 downTo 0) {
                lcs[i][j] = if (old[oFrom + i] == new[nFrom + j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
            }
        }
        var i = 0
        var j = 0
        var removeStart = -1
        var insertStart = -1
        fun closeRemoval(at: Int) { if (removeStart >= 0) { removals += removeStart until at; removeStart = -1 } }
        fun closeInsertion(at: Int) { if (insertStart >= 0) { insertions += insertStart until at; insertStart = -1 } }
        while (i < m || j < k) {
            when {
                i < m && j < k && old[oFrom + i] == new[nFrom + j] -> {
                    closeRemoval(oFrom + i); closeInsertion(nFrom + j)
                    i++; j++
                }
                j < k && (i == m || lcs[i][j + 1] >= lcs[i + 1][j]) -> {
                    if (insertStart < 0) insertStart = nFrom + j
                    j++
                }
                else -> {
                    if (removeStart < 0) removeStart = oFrom + i
                    i++
                }
            }
        }
        closeRemoval(oFrom + m); closeInsertion(nFrom + k)
    }
}
