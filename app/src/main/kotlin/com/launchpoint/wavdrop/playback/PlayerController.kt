package com.launchpoint.wavdrop.playback

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.launchpoint.wavdrop.data.model.Song
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Singleton bridge between the UI layer and PlaybackService.
 *
 * Connects to PlaybackService via MediaController asynchronously on first
 * instantiation. Any playSong() call received before the connection is
 * ready is queued and executed once the controller is available.
 */
@Singleton
class PlayerController @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private var mediaController: MediaController? = null

    // Holds a song that arrived before the controller was ready.
    private var pendingSong: Song? = null

    private val _nowPlayingState = MutableStateFlow(NowPlayingState())
    val nowPlayingState: StateFlow<NowPlayingState> = _nowPlayingState.asStateFlow()

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _nowPlayingState.update { it.copy(isPlaying = isPlaying) }
        }
    }

    init {
        val token = SessionToken(
            context,
            ComponentName(context, PlaybackService::class.java),
        )
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener(
            {
                runCatching {
                    val controller = future.get()
                    mediaController = controller
                    controller.addListener(playerListener)
                    // Flush any queued song
                    pendingSong?.also { song ->
                        pendingSong = null
                        playSong(song)
                    }
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    fun playSong(song: Song) {
        // Update UI state immediately so the bar appears before audio starts.
        _nowPlayingState.update { it.copy(song = song) }

        val controller = mediaController
        if (controller == null) {
            pendingSong = song          // deliver once connected
            return
        }
        controller.setMediaItem(MediaItem.fromUri(song.uri))
        controller.prepare()
        controller.play()
    }

    fun togglePlayPause() {
        val controller = mediaController ?: return
        if (controller.isPlaying) controller.pause() else controller.play()
    }

    fun release() {
        mediaController?.removeListener(playerListener)
        mediaController?.release()
        mediaController = null
    }
}
