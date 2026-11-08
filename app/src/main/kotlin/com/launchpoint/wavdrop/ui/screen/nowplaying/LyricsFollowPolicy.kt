package com.launchpoint.wavdrop.ui.screen.nowplaying

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity

/** How long after the last manual scroll the lyrics start following the active line again. */
internal const val LYRICS_FOLLOW_RESUME_DELAY_MS = 5_000L

/**
 * Follow state for the synchronized-lyrics list: automatic centering is suspended while the user scrolls and resumes
 * [resumeDelayMs] after the last manual scroll settles. Pure state, no coroutines or clocks: callers pass the time, and
 * the Compose side owns the single cancellable delay keyed on [resumeAtMs]. Highlighting is not part of this.
 */
internal class LyricsFollowPolicy(
    private val resumeDelayMs: Long = LYRICS_FOLLOW_RESUME_DELAY_MS,
    initialSeekEpoch: Int = 0,
) {
    /** True while the list should keep the active line centered. */
    var following by mutableStateOf(true)
        private set

    /** When following resumes if no further gesture happens; null while a gesture is active or not suspended. */
    var resumeAtMs by mutableStateOf<Long?>(null)
        private set

    private var userScrolling = false
    private var seenSeekEpoch = initialSeekEpoch

    /** A user-driven drag or fling frame arrived. */
    fun onUserScrollActive() {
        userScrolling = true
        following = false
        resumeAtMs = null
    }

    /** The user's gesture (including its fling) is over; start the inactivity period. */
    fun onUserScrollIdle(nowMs: Long) {
        if (following) return
        userScrolling = false
        resumeAtMs = nowMs + resumeDelayMs
    }

    /** The list stopped moving for any reason; only meaningful if a user gesture was in progress. */
    fun onScrollSettled(nowMs: Long) {
        if (userScrolling) onUserScrollIdle(nowMs)
    }

    /** The inactivity deadline was reached. Returns true when following was resumed (recenter once). */
    fun onResumeDue(nowMs: Long): Boolean {
        val due = resumeAtMs ?: return false
        if (userScrolling || nowMs < due) return false
        resumeAtMs = null
        following = true
        return true
    }

    /**
     * The user sought through the app's own seek control ([LyricsSeekSignal]). A new epoch restores following so the
     * line at the sought position is centered; re-delivery of the same epoch (recomposition) does nothing.
     */
    fun onSeekEpoch(epoch: Int) {
        if (epoch == seenSeekEpoch) return
        seenSeekEpoch = epoch
        reset()
    }

    /** New track / initial state. */
    fun reset() {
        userScrolling = false
        following = true
        resumeAtMs = null
    }
}

/**
 * Reports direct user gestures on the lyrics list. Programmatic `scrollToItem` / `scrollBy` calls bypass nested
 * scrolling, so only real drags arrive here as [NestedScrollSource.UserInput]; the end of a fling is
 * `onPostFling`, which runs after the fling has finished moving.
 */
internal class LyricsUserScrollConnection(
    private val policy: LyricsFollowPolicy,
    private val nowMs: () -> Long,
) : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (source == NestedScrollSource.UserInput) policy.onUserScrollActive()
        return Offset.Zero
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        policy.onUserScrollIdle(nowMs())
        return Velocity.Zero
    }
}

/**
 * Explicit "the user sought" signal from the app's own seek control. Bumping [epoch] is the only thing that restores
 * lyric following early; seeks made elsewhere (notification, other controllers) and position jumps the user did not
 * make are intentionally not guessed from lyric-index changes.
 */
internal class LyricsSeekSignal {
    var epoch by mutableIntStateOf(0)
        private set

    fun notifySeek() {
        epoch += 1
    }
}

internal val LocalLyricsSeekSignal = compositionLocalOf<LyricsSeekSignal?> { null }

/**
 * The follow policy for one song's lyrics overlay. Keyed on the stable [songId], never on the lyric content, so two
 * songs with identical lyrics never share suspended state; leaving composition (overlay closed) discards it.
 */
@Composable
internal fun rememberLyricsFollowPolicy(songId: Long, seekEpoch: Int): LyricsFollowPolicy {
    val policy = remember(songId) { LyricsFollowPolicy(initialSeekEpoch = seekEpoch) }
    SideEffect { policy.onSeekEpoch(seekEpoch) }
    return policy
}
