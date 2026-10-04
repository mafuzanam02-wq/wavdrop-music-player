package com.launchpoint.wavdrop.ui.screen.settings

import com.launchpoint.wavdrop.playback.CrossfadeRules

/** A fixed, user-facing crossfade choice. Canonical storage stays milliseconds. */
internal data class CrossfadeDurationOption(
    val durationMs: Long,
    val label: String,
)

/** Product choices (not every duration CF-1 technically allows): Off, then 2..12 seconds in 2-second steps. */
internal val CROSSFADE_DURATION_OPTIONS: List<CrossfadeDurationOption> =
    listOf(0L, 2_000L, 4_000L, 6_000L, 8_000L, 10_000L, 12_000L)
        .map { CrossfadeDurationOption(it, formatCrossfadeDurationMs(it)) }

/**
 * Locale-independent display of a stored duration. Normalizes first (negative -> Off, over-max -> 12 seconds).
 * Whole seconds read "N second(s)"; other values keep their exact fraction without trailing zeros (6500 -> "6.5 seconds").
 */
internal fun formatCrossfadeDurationMs(durationMs: Long): String {
    val ms = CrossfadeRules.normalizeDurationMs(durationMs)
    if (ms == CrossfadeRules.OFF_MS) return "Off"
    val whole = ms / 1_000L
    val fraction = (ms % 1_000L).toString().padStart(3, '0').trimEnd('0')
    val amount = if (fraction.isEmpty()) "$whole" else "$whole.$fraction"
    return if (ms == 1_000L) "1 second" else "$amount seconds"
}

/**
 * The product option to show as selected, or null when the stored value is not exactly a product choice
 * (e.g. 6500 ms). Never rounds: opening the dialog must not imply or rewrite a different value.
 */
internal fun selectedCrossfadeOption(configuredDurationMs: Long): CrossfadeDurationOption? {
    val ms = CrossfadeRules.normalizeDurationMs(configuredDurationMs)
    return CROSSFADE_DURATION_OPTIONS.firstOrNull { it.durationMs == ms }
}

internal const val CROSSFADE_EQ_UNAVAILABLE_SUMMARY = "Unavailable while Equalizer is on"

/** Whether the Crossfade row exists at all: only once the runtime rollout is available. */
internal fun shouldShowCrossfadeSetting(runtimeEnabled: Boolean): Boolean = runtimeEnabled

internal data class CrossfadeSettingUiState(
    val visible: Boolean,
    val enabled: Boolean,
    val summary: String,
)

/**
 * Presentation policy. Runtime unavailable -> hidden. Available with EQ off -> enabled, showing the saved duration.
 * Available with EQ on -> visible but disabled with an explanation; the saved duration is never changed or cleared so it
 * resumes automatically when EQ is turned off. Only the EQ enabled flag matters (never the preset type).
 */
internal fun buildCrossfadeSettingUiState(
    runtimeAvailable: Boolean,
    equalizerEnabled: Boolean,
    configuredDurationMs: Long,
): CrossfadeSettingUiState = when {
    !shouldShowCrossfadeSetting(runtimeAvailable) -> CrossfadeSettingUiState(false, false, "")
    equalizerEnabled -> CrossfadeSettingUiState(true, false, CROSSFADE_EQ_UNAVAILABLE_SUMMARY)
    else -> CrossfadeSettingUiState(true, true, formatCrossfadeDurationMs(configuredDurationMs))
}
