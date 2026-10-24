package com.launchpoint.wavdrop.playback

/** How a logical queue is mirrored into Media3 (design §4.3). */
internal enum class QueueMode {
    /** Today's behaviour: the physical playlist IS the logical queue. */
    Full,

    /** A bounded [PhysicalWindow] of the logical playback order is installed. */
    Bounded,
}

/**
 * P5-A: the INACTIVE rollout gate and the pure mode-selection rule (design §12.1). A sibling of [CrossfadeRolloutPolicy]:
 * a compile-time constant, no user setting, no BuildConfig, no persistence, no second runtime configuration system.
 *
 * **Nothing in the production playback path references this object yet.** It becomes live in P5-B, where it is evaluated once
 * per queue install. Tests exercise the enabled behaviour through the [boundedEnabled] parameter of [modeFor]; the committed
 * constant stays `false`.
 */
internal object PhysicalQueuePolicy {
    /** Build-time gate. `false`: every queue is [QueueMode.Full]. */
    const val BOUNDED_WINDOW_ENABLED = false

    /**
     * Pure and O(1): reads only [queueSize]. With the gate off every size is [QueueMode.Full]; with it on, queues of
     * [fullModeMax] or fewer stay Full and larger ones are Bounded. An empty or negative size is Full.
     */
    fun modeFor(
        queueSize: Int,
        boundedEnabled: Boolean = BOUNDED_WINDOW_ENABLED,
        fullModeMax: Int = PhysicalWindowPolicy.FULL_MODE_MAX,
    ): QueueMode =
        if (boundedEnabled && queueSize > fullModeMax) QueueMode.Bounded else QueueMode.Full
}
