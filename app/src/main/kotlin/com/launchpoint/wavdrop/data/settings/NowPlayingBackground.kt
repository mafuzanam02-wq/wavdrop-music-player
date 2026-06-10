package com.launchpoint.wavdrop.data.settings

enum class NowPlayingBackground(val displayName: String, val description: String) {
    ARTWORK(
        displayName = "Artwork",
        description = "Display album artwork in Now Playing.",
    ),
    MINIMAL(
        displayName = "Minimal",
        description = "Show a clean placeholder instead of artwork.",
    ),
}
