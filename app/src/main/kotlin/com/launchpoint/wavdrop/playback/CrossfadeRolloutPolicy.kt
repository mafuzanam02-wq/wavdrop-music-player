package com.launchpoint.wavdrop.playback

/**
 * The single authoritative crossfade rollout fact. When [RUNTIME_ENABLED] is true [PlaybackService] builds the promotion-based
 * two-slot runtime (the engine's CURRENT and NEXT players, NEXT preparation and the promotion/overlap runtime), and the Playback
 * Settings UI shows the Crossfade control; when it is false neither exists and playback is the plain single-player path. The product
 * therefore can never offer a duration the runtime cannot execute. There is deliberately no second gate anywhere else.
 *
 * Enabled after the CF-2N1 physical rollout sign-off (see QA_CHECKLIST.md section 32.12). Crossfade stays unavailable while the
 * Equalizer is on; that policy is independent of this fact.
 */
internal object CrossfadeRolloutPolicy {
    const val RUNTIME_ENABLED = true
}
