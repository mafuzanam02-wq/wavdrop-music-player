package com.launchpoint.wavdrop.data.model

/**
 * Per-song engagement summary derived from native Wavdrop playback events only.
 *
 * [nativePlays] — PLAY events (threshold crossed: min(30s, 50% of duration)).
 * [nativeSkips] — SKIP events (abandoned before threshold, with any engagement).
 * [validCompletionPlays] — PLAY events where durationMs > 0, used for [avgCompletion].
 * [avgCompletion] — average listenedMs/durationMs ratio over valid plays, capped at 1.0 per event.
 *                   0.0 when [validCompletionPlays] == 0 (no usable duration data).
 *
 * BlackPlayer imports and manual restores are excluded — they never write native events.
 */
data class SongCompletionSummary(
    val songId: Long,
    val nativePlays: Int,
    val nativeSkips: Int,
    val validCompletionPlays: Int,
    val avgCompletion: Float,
)
