package com.launchpoint.wavdrop.ui.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import com.launchpoint.wavdrop.playback.PlayerController
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges Now Playing state changes to the home-screen widget.
 *
 * Observes [PlayerController.nowPlayingState] and re-renders the widget
 * whenever the current song or play/pause state changes. Position ticks
 * are filtered out so the widget is not redrawn twice a second.
 *
 * Started once from WavdropApp.onCreate, so it is active in any process
 * launch that can also render the widget.
 */
@Singleton
class WavdropWidgetUpdater @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playerController: PlayerController,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false

    private data class WidgetKey(
        val songId: Long?,
        val title: String?,
        val artist: String?,
        val isPlaying: Boolean,
    )

    fun start() {
        if (started) return
        started = true
        scope.launch {
            playerController.nowPlayingState
                .map { state ->
                    WidgetKey(
                        songId    = state.song?.id,
                        title     = state.song?.title,
                        artist    = state.song?.artist,
                        isPlaying = state.isPlaying,
                    )
                }
                .distinctUntilChanged()
                .collect { requestUpdate(context) }
        }
    }

    companion object {
        /** Fire-and-forget widget refresh; safe to call from any thread. */
        fun requestUpdate(context: Context) {
            CoroutineScope(Dispatchers.Default).launch {
                runCatching { WavdropWidget().updateAll(context.applicationContext) }
            }
        }
    }
}
