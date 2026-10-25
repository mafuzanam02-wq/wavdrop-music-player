package com.launchpoint.wavdrop.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import com.launchpoint.wavdrop.data.model.Song

/*
 * P5-E Checkpoint B: the LOGICAL queue as the session presents it, while the physical player holds only a bounded window.
 *
 * Contract:
 *  - the presented Timeline has exactly one window and one period per LOGICAL queue entry (a 50,000-song queue is 50,000 windows),
 *    and `currentMediaItemIndex` is a logical index. A controller therefore never sees (or sends) a physical index;
 *  - nothing here is built per entry up front. Windows and periods are produced on demand from the immutable song list, so building
 *    the timeline is O(1) and a state evaluation never allocates O(N);
 *  - identity: entry i of queue generation g has the uid (g, i). It is stable for as long as the generation is, two entries of the
 *    same song are different uids, and a generation change (any logical queue mutation) invalidates every identity at once. Hashes
 *    are avalanched so Media3's XOR-hashed Pair(windowUid, periodUid) keys cannot cancel (the P4 hazard, see
 *    OccurrenceTimelineIdentity.kt): window and period uids are different kinds and hash differently;
 *  - equality is O(1): same generation, same list instance, same current index and same current-item duration. Refilling or trimming
 *    the PHYSICAL window changes none of these, so it produces no timeline event.
 */

/** What the façade needs to know about the logical queue, captured at one instant. The list is never mutated by its owner. */
internal class LogicalQueueSnapshot(
    /** The logical queue generation. A new generation means every occurrence identity of the old one is void. */
    val generation: Long,
    val songs: List<Song>,
    /** The PROVEN (or, while an operation is in flight, tracked) logical current occurrence. */
    val currentIndex: Int,
    val repeatMode: RepeatMode,
    val shuffleEnabled: Boolean,
) {
    init { require(songs.isNotEmpty() && currentIndex in songs.indices) { "snapshot needs a current entry (size=${songs.size}, index=$currentIndex)" } }
}

/** Supplies the logical queue to the façade. [snapshot] is null whenever the installed queue is NOT in bounded mode. */
internal interface LogicalQueuePresentation {
    fun snapshot(): LogicalQueueSnapshot?
}

/**
 * The explicit logical command port. The façade routes every index-bearing or queue-order-dependent external command here
 * instead of forwarding it to the physical player, whose indices mean something else.
 */
internal interface LogicalQueueCommands {
    fun skipToNext()
    fun skipToPrevious()
    /** [positionMs] == C.TIME_UNSET means the default position of the target item; anything else is an explicit time position. */
    fun seekToQueueItem(index: Int, positionMs: Long)
    fun setRepeatMode(media3RepeatMode: Int)
    fun setShuffleModeEnabled(enabled: Boolean)
}

/** Identity of one logical queue occurrence (or its single period). */
internal class LogicalOccurrenceUid(val generation: Long, val index: Int, val isPeriod: Boolean) {
    override fun equals(other: Any?): Boolean =
        other is LogicalOccurrenceUid && other.generation == generation && other.index == index && other.isPeriod == isPeriod

    override fun hashCode(): Int {
        var z = generation * -0x61c8864680b583ebL + index * 2L + (if (isPeriod) 1L else 0L)
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return (z xor (z ushr 31)).toInt()
    }

    override fun toString(): String = "Logical(g$generation#$index${if (isPeriod) "p" else ""})"
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal class LogicalQueueTimeline(
    private val songs: List<Song>,
    val generation: Long,
    private val currentIndex: Int,
    /** The physical CURRENT item's real duration once known (the only entry whose duration is the player's, not the library's). */
    private val currentDurationUs: Long,
    private val mediaItemOf: (Song) -> MediaItem = { it.toPlaybackMediaItem() },
) : Timeline() {

    override fun getWindowCount(): Int = songs.size
    override fun getPeriodCount(): Int = songs.size

    override fun getWindow(windowIndex: Int, window: Window, defaultPositionProjectionUs: Long): Window {
        LogicalQueueTimelineStats.windowReads++
        val song = songs[windowIndex]
        val durationUs = when {
            windowIndex == currentIndex && currentDurationUs != C.TIME_UNSET -> currentDurationUs
            song.duration > 0L -> song.duration * 1_000L
            else -> C.TIME_UNSET
        }
        return window.set(
            LogicalOccurrenceUid(generation, windowIndex, isPeriod = false),
            mediaItemOf(song),
            /* manifest = */ null,
            C.TIME_UNSET, C.TIME_UNSET, C.TIME_UNSET,
            /* isSeekable = */ true,
            /* isDynamic = */ false,
            /* liveConfiguration = */ null,
            /* defaultPositionUs = */ 0L,
            durationUs,
            /* firstPeriodIndex = */ windowIndex,
            /* lastPeriodIndex = */ windowIndex,
            /* positionInFirstPeriodUs = */ 0L,
        )
    }

    override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period {
        LogicalQueueTimelineStats.periodReads++
        val song = songs[periodIndex]
        val durationUs = when {
            periodIndex == currentIndex && currentDurationUs != C.TIME_UNSET -> currentDurationUs
            song.duration > 0L -> song.duration * 1_000L
            else -> C.TIME_UNSET
        }
        val uid = if (setIds) LogicalOccurrenceUid(generation, periodIndex, isPeriod = true) else null
        return period.set(uid, uid, periodIndex, durationUs, /* positionInWindowUs = */ 0L)
    }

    override fun getIndexOfPeriod(uid: Any): Int {
        if (uid !is LogicalOccurrenceUid || uid.generation != generation || !uid.isPeriod) return C.INDEX_UNSET
        return if (uid.index in songs.indices) uid.index else C.INDEX_UNSET
    }

    override fun getUidOfPeriod(periodIndex: Int): Any {
        require(periodIndex in songs.indices)
        return LogicalOccurrenceUid(generation, periodIndex, isPeriod = true)
    }

    // The queue order IS the playback order (shuffle reorders the queue itself), so there is no separate shuffle order here.
    override fun getNextWindowIndex(windowIndex: Int, repeatMode: Int, shuffleModeEnabled: Boolean): Int = when (repeatMode) {
        Player.REPEAT_MODE_ONE -> windowIndex
        else -> when {
            windowIndex + 1 < songs.size -> windowIndex + 1
            repeatMode == Player.REPEAT_MODE_ALL -> 0
            else -> C.INDEX_UNSET
        }
    }

    override fun getPreviousWindowIndex(windowIndex: Int, repeatMode: Int, shuffleModeEnabled: Boolean): Int = when (repeatMode) {
        Player.REPEAT_MODE_ONE -> windowIndex
        else -> when {
            windowIndex > 0 -> windowIndex - 1
            repeatMode == Player.REPEAT_MODE_ALL -> songs.size - 1
            else -> C.INDEX_UNSET
        }
    }

    override fun getLastWindowIndex(shuffleModeEnabled: Boolean): Int = if (songs.isEmpty()) C.INDEX_UNSET else songs.size - 1
    override fun getFirstWindowIndex(shuffleModeEnabled: Boolean): Int = if (songs.isEmpty()) C.INDEX_UNSET else 0

    // O(1) equality that deliberately IGNORES the current index and the current item's live duration. Those move on every track
    // change; if they were part of the identity every step would be a "timeline change" and the session would re-send the whole
    // N-entry queue to every controller each time (measured: ~8 window reads per entry per step at 25k). A controller learns the
    // current index from its own state field and the live duration from the session's position info, not from this timeline.
    override fun equals(other: Any?): Boolean =
        this === other || (other is LogicalQueueTimeline && other.generation == generation && other.songs === songs)

    override fun hashCode(): Int = 31 * generation.hashCode() + System.identityHashCode(songs)
}

/** Counts on-demand materializations (windows/periods actually produced). Evidence that nothing is built eagerly per entry. */
internal object LogicalQueueTimelineStats {
    @Volatile var windowReads = 0L
    @Volatile var periodReads = 0L
    fun reset() { windowReads = 0L; periodReads = 0L }
}
