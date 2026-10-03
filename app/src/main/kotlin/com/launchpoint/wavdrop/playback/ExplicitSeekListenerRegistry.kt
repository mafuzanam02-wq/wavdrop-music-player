package com.launchpoint.wavdrop.playback

/**
 * CF-2G2: single, nullable, main-thread-owned listener slot for an explicit user position seek. Holds no crossfade
 * types: the lifecycle-scoped owner (PlaybackService) registers a callback and MUST clear it on teardown so a
 * singleton PlayerController never retains a destroyed service. Replacing or clearing takes effect immediately.
 */
internal class ExplicitSeekListenerRegistry {
    private var listener: (() -> Unit)? = null

    fun set(listener: (() -> Unit)?) {
        this.listener = listener
    }

    /** Invokes the current listener (if any) exactly once. */
    fun notifyExplicitSeek() {
        listener?.invoke()
    }
}
