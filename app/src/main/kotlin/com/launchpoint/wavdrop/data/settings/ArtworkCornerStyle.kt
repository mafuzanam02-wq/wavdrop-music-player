package com.launchpoint.wavdrop.data.settings

enum class ArtworkCornerStyle(val displayName: String, val description: String) {
    SOFT(
        displayName = "Soft",
        description = "Slightly rounded corners.",
    ),
    ROUNDED(
        displayName = "Rounded",
        description = "Standard rounded corners.",
    ),
    SQUARE(
        displayName = "Square",
        description = "Sharp square corners.",
    ),
}
