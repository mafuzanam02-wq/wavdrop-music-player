package com.launchpoint.wavdrop.data.settings

enum class WrappedBackgroundIntensity(val displayName: String, val description: String) {
    SUBTLE(
        displayName = "Subtle",
        description = "Calmer backgrounds with stronger text contrast.",
    ),
    MEDIUM(
        displayName = "Medium",
        description = "Balanced Wrapped backgrounds.",
    ),
    BOLD(
        displayName = "Bold",
        description = "Richer backgrounds while keeping text readable.",
    ),
}
