package com.launchpoint.wavdrop.playback

/**
 * ST-1: single, nullable, main-thread-owned slot through which [PlayerController] tells the lifecycle-scoped owner
 * (PlaybackService) that the sleep-timer terminal boundary became armed (true) or was released (false). The owner then cancels
 * any owned crossfade and toggles the physical players' pause-at-end behaviour. Holds no player or crossfade types; the owner
 * MUST clear it on teardown so a singleton PlayerController never retains a destroyed service. Setting a listener replays the
 * current state once so a recreated service re-applies an already armed boundary.
 */
internal class SleepBoundaryListenerRegistry {
    private var listener: ((Boolean) -> Unit)? = null

    fun set(listener: ((Boolean) -> Unit)?, currentlyArmed: Boolean) {
        this.listener = listener
        listener?.invoke(currentlyArmed)
    }

    fun notifyArmed(armed: Boolean) {
        listener?.invoke(armed)
    }
}
