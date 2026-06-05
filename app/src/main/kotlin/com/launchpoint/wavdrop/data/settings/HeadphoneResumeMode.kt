package com.launchpoint.wavdrop.data.settings

enum class HeadphoneResumeMode(val displayName: String, val description: String) {
    OFF(
        displayName = "Off",
        description = "Never start playback automatically.",
    ),
    RESUME_IF_INTERRUPTED(
        displayName = "Resume if interrupted",
        description = "Resume only when playback was stopped by disconnect.",
    ),
    ALWAYS_RESUME(
        displayName = "Always resume",
        description = "Start playback whenever this device reconnects and Wavdrop has something to play.",
    ),
    ;

    fun shouldResume(wasInterrupted: Boolean): Boolean = when (this) {
        OFF                   -> false
        RESUME_IF_INTERRUPTED -> wasInterrupted
        ALWAYS_RESUME         -> true
    }
}
