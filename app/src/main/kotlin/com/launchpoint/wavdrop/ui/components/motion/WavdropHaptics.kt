package com.launchpoint.wavdrop.ui.components.motion

import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType

/**
 * Semantic haptic vocabulary for Wavdrop (WU-02). Not a global manager and not wired into every
 * button — just a thin, documented mapping so later phases stay consistent and restrained.
 *
 * Audit: today only QueueSheet uses haptics — [HapticFeedbackType.LongPress] on drag pickup. That
 * is exactly the intended pattern.
 *
 * Rules:
 * - [pickup] — deliberate drag/reorder acquisition (long-press to grab). The only "heavy" cue.
 * - [confirm] — a SIGNIFICANT state change only (e.g. a destructive or committing action).
 * - No haptic on ordinary taps / navigation / scrolling. No per-tap spam.
 *
 * `TextHandleMove` (a light tick) may be used for fine incremental feedback where it genuinely helps
 * and is available, but is intentionally not exposed as a blanket helper to discourage overuse.
 */
object WavdropHaptics {

    /** Deliberate drag/reorder pickup. */
    fun pickup(haptics: HapticFeedback) {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    /** Confirmation for a significant state change — use sparingly. */
    fun confirm(haptics: HapticFeedback) {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }
}
