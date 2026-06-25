package com.launchpoint.wavdrop.data.settings

enum class PreviousButtonBehavior(val displayName: String) {
    RESTART_CURRENT("Restart current track"),
    PREVIOUS_TRACK("Always go to previous track"),
    ;

    companion object {
        val DEFAULT: PreviousButtonBehavior = RESTART_CURRENT

        fun fromStoredName(value: String?): PreviousButtonBehavior? =
            value?.let { stored -> entries.firstOrNull { it.name == stored } }

        fun fromStoredNameOrDefault(value: String?): PreviousButtonBehavior =
            fromStoredName(value) ?: DEFAULT
    }
}
