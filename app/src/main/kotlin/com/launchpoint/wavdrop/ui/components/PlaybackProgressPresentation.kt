package com.launchpoint.wavdrop.ui.components

internal data class PlaybackProgressPresentation(
    val positionMs: Long,
    val durationMs: Long,
    val fraction: Float,
    val displayElapsedSeconds: Long,
    val displayRemainingSeconds: Long,
    val displayDurationSeconds: Long,
)

internal fun playbackProgressPresentation(
    playerPositionMs: Long,
    playerDurationMs: Long,
    dragPositionMs: Long? = null,
): PlaybackProgressPresentation {
    val durationMs = playerDurationMs.coerceAtLeast(0L)
    val positionMs = (dragPositionMs ?: playerPositionMs).coerceAtLeast(0L).let { position ->
        if (durationMs > 0L) position.coerceAtMost(durationMs) else position
    }
    val displayDurationSeconds = durationMs / 1_000L
    val displayElapsedSeconds = (positionMs / 1_000L).let { seconds ->
        if (durationMs > 0L) seconds.coerceAtMost(displayDurationSeconds) else seconds
    }
    return PlaybackProgressPresentation(
        positionMs = positionMs,
        durationMs = durationMs,
        fraction = if (durationMs > 0L) {
            (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
        } else {
            0f
        },
        displayElapsedSeconds = displayElapsedSeconds,
        displayRemainingSeconds = if (durationMs > 0L) displayDurationSeconds - displayElapsedSeconds else 0L,
        displayDurationSeconds = displayDurationSeconds,
    )
}
