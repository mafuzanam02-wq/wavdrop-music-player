package com.launchpoint.wavdrop.playback

enum class SleepTimerOption(
    val displayName: String,
    val durationMs: Long?,
) {
    OFF("Off", null),
    MINUTES_15("15 minutes", 15 * 60 * 1_000L),
    MINUTES_30("30 minutes", 30 * 60 * 1_000L),
    MINUTES_45("45 minutes", 45 * 60 * 1_000L),
    MINUTES_60("60 minutes", 60 * 60 * 1_000L),
    END_OF_CURRENT_SONG("End of current song", null),
}

data class SleepTimerState(
    val option: SleepTimerOption = SleepTimerOption.OFF,
    val startedAtMs: Long? = null,
    val endsAtMs: Long? = null,
) {
    val isActive: Boolean get() = option != SleepTimerOption.OFF
}
