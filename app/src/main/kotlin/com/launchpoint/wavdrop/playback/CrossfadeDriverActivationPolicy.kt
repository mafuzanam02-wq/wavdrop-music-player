package com.launchpoint.wavdrop.playback

/** What a change of the persisted crossfade duration means for the timing driver lifecycle. */
internal enum class CrossfadeActivationDecision {
    /** Nothing to do (still OFF, or OFF at first observation). */
    NoOp,

    /** First observation enabled, or OFF -> enabled: start the driver (start() is idempotent). */
    Start,

    /** Enabled -> enabled: only the cached duration changes; never restarts or cancels anything. */
    UpdateOnly,

    /** Enabled -> OFF: cancel the runtime first, then stop the driver. */
    Disable,
}

/**
 * Pure policy. [previousDurationMs] is null for the very first observation. Starting happens only on the first
 * enabled observation or an OFF -> enabled transition, never on a repeated enabled value, so a driver that halted
 * itself after a handoff failure is not silently restarted.
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

/**
 * Applies the decision to the service-owned graph (either part may be null: the hard gate is false in shipping, so
 * nothing exists). Disable cancels the runtime with [CrossfadeCancelReason.ConfigurationDisabled] BEFORE stopping
 * the driver, so the runtime's own cleanup (restore primary gain, abandon secondary, Idle) runs first and the stop
 * then invalidates any pending/stale callback. A partial graph never starts. Service teardown does not use this.
 */
internal fun applyCrossfadeConfiguredDurationChange(
    previousDurationMs: Long?,
    newDurationMs: Long,
    runtime: CrossfadePreparationRuntime?,
    driver: CrossfadeTimingDriver?,
): CrossfadeActivationDecision {
    val decision = decideCrossfadeDriverActivation(previousDurationMs, newDurationMs)
    when (decision) {
        CrossfadeActivationDecision.Start -> if (runtime != null && driver != null) driver.start()
        CrossfadeActivationDecision.Disable -> {
            runtime?.cancel(CrossfadeCancelReason.ConfigurationDisabled)
            driver?.stop()
        }
        CrossfadeActivationDecision.NoOp,
        CrossfadeActivationDecision.UpdateOnly -> Unit
    }
    return decision
}
