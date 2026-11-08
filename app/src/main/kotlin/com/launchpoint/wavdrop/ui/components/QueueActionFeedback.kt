package com.launchpoint.wavdrop.ui.components

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.playback.QueueAction
import com.launchpoint.wavdrop.playback.QueueActionResult
import com.launchpoint.wavdrop.playback.QueuePlacement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * P5-F14: the one confirmation pattern for Play Next / Add to Queue on Home, Songs and Global Search.
 *
 * A short snackbar, shown only from a [QueueActionResult] the controller verified against its committed queue, so success is never
 * claimed prematurely and a failure never says an item was added. Snackbars are announced by TalkBack by default and carry no
 * custom animation, so reduced-motion needs nothing extra; there is deliberately NO haptic (an ordinary tap is not a "significant
 * state change" under [com.launchpoint.wavdrop.ui.components.motion.WavdropHaptics]).
 */
object QueueActionFeedback {

    private const val MAX_TITLE_CHARS = 28

    /** The text for [result], or never a success text for a [QueueActionResult.Failed]. [song] adds its (shortened) title. */
    fun message(result: QueueActionResult, song: Song): String {
        val title = shortTitle(song)
        val suffix = if (title.isEmpty()) "" else ": $title"
        return when (result) {
            is QueueActionResult.Queued -> when {
                // A Play Next that could only be appended must not claim "next".
                result.action == QueueAction.PlayNext && result.placement == QueuePlacement.PlayNext -> "Playing next$suffix"
                else -> "Added to queue$suffix"
            }
            QueueActionResult.StartedQueue -> "Playing now$suffix"
            is QueueActionResult.Failed -> when (result.action) {
                QueueAction.PlayNext -> "Couldn't play next"
                QueueAction.AddToQueue -> "Couldn't add to queue"
            }
        }
    }

    internal fun shortTitle(song: Song): String {
        val raw = song.displayTitle.trim()
        return if (raw.length <= MAX_TITLE_CHARS) raw else raw.take(MAX_TITLE_CHARS - 1).trimEnd() + "…"
    }
}

/**
 * Shows the confirmation for [result]. A newer action REPLACES the one still on screen instead of queuing behind it, so repeated
 * taps never build a backlog of stale snackbars. The coroutine belongs to the calling screen's scope, so leaving the screen
 * cancels a pending confirmation (the queue change itself is synchronous and already committed by then).
 */
fun CoroutineScope.announceQueueAction(host: SnackbarHostState, song: Song, result: QueueActionResult) {
    launch {
        host.currentSnackbarData?.dismiss()
        host.showSnackbar(QueueActionFeedback.message(result, song), duration = SnackbarDuration.Short)
    }
}
