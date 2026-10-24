package com.launchpoint.wavdrop.playback

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import java.util.concurrent.atomic.AtomicLong

/*
 * Stable queue-OCCURRENCE identity for the timeline the session façade presents.
 *
 * WHY THIS EXISTS (measured, see the P4 report): Media3's `SimpleBasePlayer` keys its period index by
 * `android.util.Pair(windowUid, periodUid)`, and `Pair.hashCode()` is `first.hashCode() ^ second.hashCode()`. ExoPlayer's period
 * uid is itself `Pair(windowUid, childPeriodUid)`, so the window uid cancels out of the XOR and EVERY period of the queue gets the
 * same hash code (the hash of the shared child uid). The index `HashMap` then degenerates into one treeified bin of
 * non-comparable keys: building a timeline is O(N^2) and every uid lookup is O(N). The façade rebuilds that timeline on every
 * state evaluation and on every promotion, which is the multi-second stall at tens of thousands of entries.
 *
 * THE FIX is to present, at the delegate boundary, period uids whose hash is derived from an identity WE mint, never from the
 * physical player's uid composition: one [PeriodOccurrenceUid] per period of each queue occurrence.
 */

/** Source of occurrence ids. One per façade, shared by every delegate it ever wraps, so ids are never reused across a promotion. */
internal class OccurrenceIdSource {
    private val next = AtomicLong(1L)
    fun next(): Long = next.getAndIncrement()
}

/**
 * The presented period uid: "period [periodInOccurrence] of queue occurrence [occurrence]". Equality is exactly that pair, so two
 * occurrences of the same song (distinct playlist entries) never compare equal, and the same occurrence keeps its uid for as long
 * as the physical player keeps its window. The hash is a 64-bit avalanche of the pair: sequential ids spread evenly and no
 * composition of two such uids (Media3 wraps them in an XOR-hashed Pair) can cancel.
 */
internal class PeriodOccurrenceUid(val occurrence: Long, val periodInOccurrence: Int) {
    override fun equals(other: Any?): Boolean =
        other is PeriodOccurrenceUid && other.occurrence == occurrence && other.periodInOccurrence == periodInOccurrence

    override fun hashCode(): Int {
        var z = occurrence * -0x61c8864680b583ebL + periodInOccurrence
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return (z xor (z ushr 31)).toInt()
    }

    override fun toString(): String = "PeriodOccurrenceUid($occurrence#$periodInOccurrence)"
}

/**
 * A read-only view of [base] whose period uids are [PeriodOccurrenceUid]s. Windows are untouched (their uid, and every other
 * field, is the physical player's). Built once per physical timeline instance in O(windows + periods); every method afterwards is
 * O(1). [occurrenceOfWindow] holds the minted occurrence id of each window, in window order.
 */
internal class OccurrenceTimeline(
    private val base: Timeline,
    private val occurrenceOfWindow: LongArray,
) : Timeline() {
    private val windowOfPeriod: IntArray
    private val firstPeriodOfWindow: IntArray
    private val periodsInWindow: IntArray
    private val windowOfOccurrence = HashMap<Long, Int>(occurrenceOfWindow.size * 2)

    init {
        val window = Window()
        val windowCount = base.windowCount
        windowOfPeriod = IntArray(base.periodCount)
        firstPeriodOfWindow = IntArray(windowCount)
        periodsInWindow = IntArray(windowCount)
        for (w in 0 until windowCount) {
            base.getWindow(w, window)
            firstPeriodOfWindow[w] = window.firstPeriodIndex
            periodsInWindow[w] = window.lastPeriodIndex - window.firstPeriodIndex + 1
            for (p in window.firstPeriodIndex..window.lastPeriodIndex) windowOfPeriod[p] = w
            windowOfOccurrence[occurrenceOfWindow[w]] = w
        }
    }

    private fun uidOf(periodIndex: Int): PeriodOccurrenceUid {
        val w = windowOfPeriod[periodIndex]
        return PeriodOccurrenceUid(occurrenceOfWindow[w], periodIndex - firstPeriodOfWindow[w])
    }

    override fun getWindowCount(): Int = base.windowCount
    override fun getNextWindowIndex(windowIndex: Int, repeatMode: Int, shuffleModeEnabled: Boolean): Int =
        base.getNextWindowIndex(windowIndex, repeatMode, shuffleModeEnabled)
    override fun getPreviousWindowIndex(windowIndex: Int, repeatMode: Int, shuffleModeEnabled: Boolean): Int =
        base.getPreviousWindowIndex(windowIndex, repeatMode, shuffleModeEnabled)
    override fun getLastWindowIndex(shuffleModeEnabled: Boolean): Int = base.getLastWindowIndex(shuffleModeEnabled)
    override fun getFirstWindowIndex(shuffleModeEnabled: Boolean): Int = base.getFirstWindowIndex(shuffleModeEnabled)

    override fun getWindow(windowIndex: Int, window: Window, defaultPositionProjectionUs: Long): Window =
        base.getWindow(windowIndex, window, defaultPositionProjectionUs)

    override fun getPeriodCount(): Int = base.periodCount

    override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period {
        base.getPeriod(periodIndex, period, setIds)
        if (setIds) {
            // The physical id is player-specific too (it would make two mirrors of one queue compare unequal); the occurrence is the id.
            val uid = uidOf(periodIndex)
            period.uid = uid
            period.id = uid
        }
        return period
    }

    override fun getIndexOfPeriod(uid: Any): Int {
        if (uid !is PeriodOccurrenceUid) return C_INDEX_UNSET
        val w = windowOfOccurrence[uid.occurrence] ?: return C_INDEX_UNSET
        if (uid.periodInOccurrence !in 0 until periodsInWindow[w]) return C_INDEX_UNSET
        return firstPeriodOfWindow[w] + uid.periodInOccurrence
    }

    override fun getUidOfPeriod(periodIndex: Int): Any = uidOf(periodIndex)

    private companion object {
        const val C_INDEX_UNSET = androidx.media3.common.C.INDEX_UNSET
    }
}

/**
 * Wraps one physical [Player] so that its `currentTimeline` carries occurrence-minted period uids. Everything else forwards
 * untouched. The presented timeline is cached per physical Timeline instance, so repeated reads between physical timeline changes
 * cost nothing; a new physical timeline costs one O(N) pass. Occurrence ids are keyed by the physical WINDOW uid and survive every
 * physical timeline refresh (ExoPlayer keeps a window's uid for the life of its playlist entry), and entries of removed windows
 * are dropped at each pass, so memory is bounded by the current queue.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal class OccurrenceIdentityPlayer(
    wrapped: Player,
    private val ids: OccurrenceIdSource,
) : ForwardingPlayer(wrapped) {
    private var cachedBase: Timeline? = null
    private var cachedPresented: Timeline = Timeline.EMPTY
    private var occurrenceByWindowUid: HashMap<Any, Long> = HashMap()

    override fun getCurrentTimeline(): Timeline {
        val base = wrappedPlayer.currentTimeline
        if (base === cachedBase) return cachedPresented
        val presented = if (base.isEmpty) {
            occurrenceByWindowUid = HashMap()
            Timeline.EMPTY
        } else {
            val window = Timeline.Window()
            val next = HashMap<Any, Long>(base.windowCount * 2)
            val perWindow = LongArray(base.windowCount)
            for (w in 0 until base.windowCount) {
                val uid = base.getWindow(w, window).uid
                // A duplicate uid inside one timeline would mean one physical entry listed twice: each listing still gets its
                // own occurrence so presented period uids remain unique per window.
                val id = if (next.containsKey(uid)) ids.next() else occurrenceByWindowUid[uid] ?: ids.next()
                if (!next.containsKey(uid)) next[uid] = id
                perWindow[w] = id
            }
            occurrenceByWindowUid = next
            OccurrenceTimeline(base, perWindow)
        }
        cachedBase = base
        cachedPresented = presented
        return presented
    }
}

/**
 * A [Timeline] view that re-labels uids: a window uid found in [windowAlias] is presented as its value, a period uid found in
 * [periodAlias] likewise. It is how the façade keeps the identity it already presented when a promoted delegate mirrors the same
 * logical queue (the new physical player's own uids are mapped onto the ones controllers already hold). Everything else is
 * [base]'s. Both directions are O(1) hash lookups on occurrence uids, whose hashes do not cancel.
 */
internal class AliasedTimeline(
    private val base: Timeline,
    private val windowAlias: Map<Any, Any>,
    periodAlias: Map<Any, Any>,
) : Timeline() {
    private val periodAlias: Map<Any, Any> = periodAlias
    private val periodUnalias: Map<Any, Any> = HashMap<Any, Any>(periodAlias.size * 2).also { reverse ->
        for ((from, to) in periodAlias) reverse[to] = from
    }

    override fun getWindowCount(): Int = base.windowCount
    override fun getNextWindowIndex(windowIndex: Int, repeatMode: Int, shuffleModeEnabled: Boolean): Int =
        base.getNextWindowIndex(windowIndex, repeatMode, shuffleModeEnabled)
    override fun getPreviousWindowIndex(windowIndex: Int, repeatMode: Int, shuffleModeEnabled: Boolean): Int =
        base.getPreviousWindowIndex(windowIndex, repeatMode, shuffleModeEnabled)
    override fun getLastWindowIndex(shuffleModeEnabled: Boolean): Int = base.getLastWindowIndex(shuffleModeEnabled)
    override fun getFirstWindowIndex(shuffleModeEnabled: Boolean): Int = base.getFirstWindowIndex(shuffleModeEnabled)

    override fun getWindow(windowIndex: Int, window: Window, defaultPositionProjectionUs: Long): Window {
        base.getWindow(windowIndex, window, defaultPositionProjectionUs)
        windowAlias[window.uid]?.let { window.uid = it }
        return window
    }

    override fun getPeriodCount(): Int = base.periodCount

    override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period {
        base.getPeriod(periodIndex, period, setIds)
        if (setIds) periodAlias[period.uid]?.let { period.uid = it; period.id = it }
        return period
    }

    override fun getIndexOfPeriod(uid: Any): Int = base.getIndexOfPeriod(periodUnalias[uid] ?: uid)

    override fun getUidOfPeriod(periodIndex: Int): Any {
        val raw = base.getUidOfPeriod(periodIndex)
        return periodAlias[raw] ?: raw
    }
}
