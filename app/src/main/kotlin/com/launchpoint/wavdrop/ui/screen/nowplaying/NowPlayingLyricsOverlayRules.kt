package com.launchpoint.wavdrop.ui.screen.nowplaying

object NowPlayingLyricsOverlayRules {
    const val NO_LYRICS_HELP_TEXT: String =
        "Long-press to add custom lyrics, or add embedded/sidecar lyrics locally."

    fun showSearchOnlineAction(onSearchOnline: (() -> Unit)?): Boolean =
        onSearchOnline != null
}
