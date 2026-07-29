package com.launchpoint.wavdrop.ui.widget

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.launchpoint.wavdrop.playback.PlaybackService

/**
 * Non-exported receiver that turns Wavdrop home-screen widget button taps into
 * Media3 session commands (WD-03).
 *
 * The widget's control PendingIntents target this receiver, never the exported
 * [PlaybackService] directly. Because the receiver is `android:exported="false"`,
 * a co-installed app can no longer drive playback by starting the service with a
 * custom widget action string — that raw action surface has been removed from
 * the service entirely.
 *
 * Playback is actuated only through a [MediaController] bound to our own session,
 * i.e. the same command surface Media3 already governs. Routing through the
 * session (rather than the raw player) preserves:
 *  - cold-start hydration: widget PLAY on an empty player reaches the session
 *    player's `play()` override, which hydrates the persisted session; and
 *  - the custom previous-button threshold behavior (seekToPrevious on the
 *    session player).
 */
class WidgetActionReceiver : BroadcastReceiver() {

    internal enum class WidgetCommand { PLAY_PAUSE, NEXT, PREVIOUS }

    override fun onReceive(context: Context, intent: Intent) {
        val command = commandFor(intent.action) ?: return
        val appContext = context.applicationContext
        val pending = goAsync()

        val token = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
        val future = MediaController.Builder(appContext, token).buildAsync()
        future.addListener({
            val controller = runCatching { future.get() }.getOrNull()
            if (controller == null) {
                pending.finish()
                return@addListener
            }

            // Cold explicit-play: the session player is empty, so play() kicks off
            // asynchronous session-hydration on the player side. Keep the controller
            // connected briefly so the MediaSessionService is not torn down before
            // playback starts, then release. Well within goAsync's ~10s budget.
            val cold = command == WidgetCommand.PLAY_PAUSE &&
                controller.currentMediaItem == null &&
                controller.mediaItemCount == 0

            dispatch(controller, command)

            if (cold) {
                Handler(Looper.getMainLooper()).postDelayed({
                    controller.release()
                    pending.finish()
                }, COLD_RELEASE_DELAY_MS)
            } else {
                controller.release()
                pending.finish()
            }
        }, ContextCompat.getMainExecutor(appContext))
    }

    private fun dispatch(controller: MediaController, command: WidgetCommand) {
        when (command) {
            WidgetCommand.PLAY_PAUSE ->
                if (controller.isPlaying) controller.pause() else controller.play()
            WidgetCommand.NEXT -> controller.seekToNext()
            WidgetCommand.PREVIOUS -> controller.seekToPrevious()
        }
    }

    companion object {
        const val ACTION_WIDGET_PLAY_PAUSE = "com.launchpoint.wavdrop.widget.PLAY_PAUSE"
        const val ACTION_WIDGET_NEXT = "com.launchpoint.wavdrop.widget.NEXT"
        const val ACTION_WIDGET_PREVIOUS = "com.launchpoint.wavdrop.widget.PREVIOUS"

        private const val COLD_RELEASE_DELAY_MS = 3_000L

        /** Pure action→command mapping. Unknown/null actions map to null (ignored). */
        internal fun commandFor(action: String?): WidgetCommand? = when (action) {
            ACTION_WIDGET_PLAY_PAUSE -> WidgetCommand.PLAY_PAUSE
            ACTION_WIDGET_NEXT -> WidgetCommand.NEXT
            ACTION_WIDGET_PREVIOUS -> WidgetCommand.PREVIOUS
            else -> null
        }
    }
}
