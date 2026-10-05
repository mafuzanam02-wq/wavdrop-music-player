package com.launchpoint.wavdrop.playback

import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.launchpoint.wavdrop.BuildConfig
import com.launchpoint.wavdrop.ui.widget.WidgetPlaybackSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Where widget state goes. Production wires the DataStore-backed store and the updater; tests record. */
internal interface WidgetStateSink {
    suspend fun save(snapshot: WidgetPlaybackSnapshot)
    suspend fun updateIsPlaying(isPlaying: Boolean)
    suspend fun clear()
    fun requestUpdate()
}

/**
 * CF-2M2: the LOGICAL widget-state consumer, extracted verbatim from `PlaybackService` so it is testable and so it is
 * unambiguous which player it observes: the session-facing player ([player], the façade when the gate is true). It is a pure
 * logical consumer (play/pause, IDLE, current item); it must never be attached to a physical player once promotion exists.
 * The behaviour is unchanged: play/pause updates `isPlaying`, IDLE clears, a media-item transition saves a snapshot of the
 * current item (or clears for a null item), and every path requests a widget update.
 */
internal class WidgetPlaybackStateListener(
    private val player: Player,
    private val scope: CoroutineScope,
    private val sink: WidgetStateSink,
) : Player.Listener {

    private fun buildSnapshot(isPlaying: Boolean): WidgetPlaybackSnapshot {
        val item = player.currentMediaItem
        return WidgetPlaybackSnapshot(
            title          = item?.mediaMetadata?.title?.toString()?.takeIf { it.isNotBlank() } ?: "Wavdrop",
            artist         = item?.mediaMetadata?.artist?.toString()?.takeIf { it.isNotBlank() } ?: "",
            albumId        = item?.mediaMetadata?.extras?.getLong("wavdrop_album_id", 0L) ?: 0L,
            isPlaying      = isPlaying,
            hasActiveMedia = item != null,
            updatedAt      = System.currentTimeMillis(),
        )
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (BuildConfig.DEBUG) Log.d(WIDGET_TAG, "[service] onIsPlayingChanged=$isPlaying")
        scope.launch {
            try {
                sink.updateIsPlaying(isPlaying)
                sink.requestUpdate()
            } catch (e: Throwable) {
                Log.e(WIDGET_TAG, "[service] onIsPlayingChanged: EXCEPTION ${e::class.simpleName} ${e.message}", e)
            }
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (BuildConfig.DEBUG) Log.d(WIDGET_TAG, "[service] onPlaybackStateChanged=$playbackState")
        if (playbackState == Player.STATE_IDLE) {
            scope.launch {
                try {
                    sink.clear()
                    sink.requestUpdate()
                } catch (e: Throwable) {
                    Log.e(WIDGET_TAG, "[service] onPlaybackStateChanged: EXCEPTION ${e::class.simpleName} ${e.message}", e)
                }
            }
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (BuildConfig.DEBUG) Log.d(WIDGET_TAG, "[service] onMediaItemTransition reason=$reason title=${mediaItem?.mediaMetadata?.title}")
        scope.launch {
            try {
                if (mediaItem == null) sink.clear() else sink.save(buildSnapshot(player.isPlaying))
                sink.requestUpdate()
            } catch (e: Throwable) {
                Log.e(WIDGET_TAG, "[service] onMediaItemTransition: EXCEPTION ${e::class.simpleName} ${e.message}", e)
            }
        }
    }

    private companion object {
        const val WIDGET_TAG = "WavdropWidget"
    }
}
