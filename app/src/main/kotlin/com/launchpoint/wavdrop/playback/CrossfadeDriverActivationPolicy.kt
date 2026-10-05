package com.launchpoint.wavdrop.playback

/** What a change of the persisted crossfade duration means for the crossfade owners (NEXT preparation driver, promotion runtime). */
internal enum class CrossfadeActivationDecision {
    /** Nothing to do (still OFF, or OFF at first observation). */
    NoOp,

    /** First observation enabled, or OFF -> enabled: start the owners (start() is idempotent). */
    Start,

    /** Enabled -> enabled: only the cached duration changes; never restarts or cancels anything. */
    UpdateOnly,

    /** Enabled -> OFF: cancel first, then stop polling. */
    Disable,
}

/**
 * Pure policy. [previousDurationMs] is null for the very first observation. Starting happens only on the first
 * enabled observation or an OFF -> enabled transition, never on a repeated enabled value, so an owner that halted
 * itself is not silently restarted.
 */
internal fun decideCrossfadeDriverActivation(previousDurationMs: Long?, newDurationMs: Long): CrossfadeActivationDecision {
    val wasEnabled = previousDurationMs?.let(CrossfadeRules::isEnabled)
    val nowEnabled = CrossfadeRules.isEnabled(newDurationMs)
    return when {
        nowEnabled && wasEnabled == true -> CrossfadeActivationDecision.UpdateOnly
        nowEnabled -> CrossfadeActivationDecision.Start
        wasEnabled == true -> CrossfadeActivationDecision.Disable
        else -> CrossfadeActivationDecision.NoOp
    }
}
