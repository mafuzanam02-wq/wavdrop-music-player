package com.launchpoint.wavdrop.data.settings

enum class WrappedFallbackTheme(val displayName: String, val description: String) {
    AUTO(
        displayName = "Auto",
        description = "Use Wavdrop's default visual mood for each Wrapped card.",
    ),
    OBSIDIAN(
        displayName = "Obsidian",
        description = "Black and charcoal with a soft neutral glow.",
    ),
    OCEAN(
        displayName = "Ocean",
        description = "Deep blue and cyan atmosphere.",
    ),
    DEEP_TEAL(
        displayName = "Deep Teal",
        description = "Dark green and teal atmosphere.",
    ),
    MIDNIGHT_VIOLET(
        displayName = "Midnight Violet",
        description = "Dark violet and purple atmosphere.",
    ),
    SUNSET_ORANGE(
        displayName = "Sunset Orange",
        description = "Warm sunset tones.",
    ),
    CLEAN_PURPLE(
        displayName = "Clean Purple",
        description = "Clean purple and blue atmosphere.",
    ),
}
