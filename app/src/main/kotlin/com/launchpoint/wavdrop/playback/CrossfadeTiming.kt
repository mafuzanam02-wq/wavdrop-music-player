package com.launchpoint.wavdrop.playback

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** Monotonic time source for fade timing. Never wall-clock. */
internal fun interface CrossfadeMonotonicClock {
    fun nowMs(): Long
}

/** Production monotonic clock. */
internal object ElapsedRealtimeCrossfadeClock : CrossfadeMonotonicClock {
    override fun nowMs(): Long = SystemClock.elapsedRealtime()
}

/**
 * Narrow main-thread scheduling seam used by the NEXT-slot preparation driver and the promotion runtime. Each owner keeps at
 * most one callback pending; [cancelAll] must drop every callback this scheduler posted (and only those).
 */
internal interface CrossfadeTimingScheduler {
    fun postDelayed(delayMs: Long, block: () -> Unit)
    fun cancelAll()
}

/** Main-looper scheduler. Removes only the runnables it posted, never unrelated work on the handler. */
internal class MainLooperCrossfadeTimingScheduler(
    private val handler: Handler = Handler(Looper.getMainLooper()),
) : CrossfadeTimingScheduler {
    private val pending = mutableSetOf<Runnable>()

    override fun postDelayed(delayMs: Long, block: () -> Unit) {
        lateinit var runnable: Runnable
        runnable = Runnable {
            pending.remove(runnable)
            block()
        }
        pending += runnable
        handler.postDelayed(runnable, delayMs.coerceAtLeast(0L))
    }

    override fun cancelAll() {
        pending.forEach { handler.removeCallbacks(it) }
        pending.clear()
    }
}

/** Polling cadence of the crossfade owners. */
internal object CrossfadeCadence {
    /** Before a fade: how often the preparation driver and the promotion window check are evaluated. */
    const val PRE_FADE_POLL_INTERVAL_MS = 250L

    /** During an overlap: how often the equal-power gains are advanced. */
    const val FADE_TICK_INTERVAL_MS = 50L
}
