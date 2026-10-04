package com.launchpoint.wavdrop.playback

/**
 * CF-2J1: the single authoritative crossfade rollout fact. [PlaybackService] constructs the secondary runtime only when
 * [RUNTIME_ENABLED] and the Playback Settings UI shows the Crossfade control only when it is true, so the product can
 * never offer a duration the runtime cannot execute. There is deliberately no second gate anywhere else.
 */
internal object CrossfadeRolloutPolicy {
    const val RUNTIME_ENABLED = false
}
