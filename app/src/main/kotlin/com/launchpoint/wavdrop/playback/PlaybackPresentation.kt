package com.launchpoint.wavdrop.playback

import androidx.media3.common.Player

/**
 * What the Play/Pause control SHOWS, derived from the one authoritative Media3 snapshot (never a second state machine, never a timer).
 *
 * Media3's `isPlaying` is `playWhenReady && READY && not suppressed`, so it is briefly false while a selected or seeked item
 * prepares even though the listener asked to play. Rendering it directly makes a normal preparation look like Pause -> Play.
 * Presentation therefore follows the playback INTENT while the player is merely preparing, and follows the truth otherwise:
 *  - truly playing                                         -> playing
 *  - a playback error                                      -> not playing (a failure is never hidden behind an intent)
 *  - audio-focus loss / route suppression                  -> not playing (distinguishable from buffering, and from an armed intent)
 *  - an accepted selection whose bounded window is still being verified ([armedIntent]) -> playing (the intent), until the
 *    verification succeeds (it then really plays) or fails/is superseded (the intent is withdrawn and this turns false)
 *  - playWhenReady while IDLE/BUFFERING                    -> playing (preparing), ENDED -> not playing
 *  - everything else (a Pause, or an intentionally paused session) -> not playing
 */
internal object PlaybackPresentation {
    fun presentedAsPlaying(
        isPlaying: Boolean,
        playWhenReady: Boolean,
        playbackState: Int,
        suppressed: Boolean,
        hasError: Boolean,
        armedIntent: Boolean,
    ): Boolean {
        if (isPlaying) return true
        if (hasError) return false
        if (suppressed) return false // focus/route suppression is shown as not playing even behind an armed intent
        if (armedIntent) return true
        return playWhenReady && playbackState != Player.STATE_ENDED
    }

    /** The toggle acts on what the control SHOWS: a tap on Pause pauses, a tap on Play plays. */
    fun toggleShouldPause(shownAsPlaying: Boolean): Boolean = shownAsPlaying
}
