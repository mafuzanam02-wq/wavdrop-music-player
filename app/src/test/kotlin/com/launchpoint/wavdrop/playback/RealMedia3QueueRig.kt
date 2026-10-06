package com.launchpoint.wavdrop.playback

import android.content.Context
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import com.launchpoint.wavdrop.data.model.Song
import java.util.concurrent.TimeUnit
import org.robolectric.RuntimeEnvironment

/**
 * Real Media3 for the large-queue tests: a genuine ExoPlayer (trackless test source) behind a genuine MediaSession, driven
 * through a genuine MediaController: the same process-local controller path PlayerController uses, including the
 * controller's own optimistic masking of timeline mutations. No fake reproduces any index behaviour here.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
/** Records which Media3 mutations/commands actually reach the session player (server side), to prove what was NOT called. */
internal class RecordingPlayer(player: androidx.media3.common.Player) : androidx.media3.common.ForwardingPlayer(player) {
    val calls = mutableListOf<String>()
    override fun setMediaItems(mediaItems: MutableList<MediaItem>) { calls += "setMediaItems(${mediaItems.size})"; super.setMediaItems(mediaItems) }
    override fun setMediaItems(mediaItems: MutableList<MediaItem>, resetPosition: Boolean) { calls += "setMediaItems(${mediaItems.size})"; super.setMediaItems(mediaItems, resetPosition) }
    override fun setMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long) { calls += "setMediaItems(${mediaItems.size})"; super.setMediaItems(mediaItems, startIndex, startPositionMs) }
    override fun replaceMediaItems(fromIndex: Int, toIndex: Int, mediaItems: MutableList<MediaItem>) { calls += "replace($fromIndex,$toIndex,${mediaItems.size})"; super.replaceMediaItems(fromIndex, toIndex, mediaItems) }
    override fun removeMediaItems(fromIndex: Int, toIndex: Int) { calls += "remove($fromIndex,$toIndex)"; super.removeMediaItems(fromIndex, toIndex) }
    override fun addMediaItems(index: Int, mediaItems: MutableList<MediaItem>) { calls += "add($index,${mediaItems.size})"; super.addMediaItems(index, mediaItems) }
    override fun addMediaItems(mediaItems: MutableList<MediaItem>) { calls += "append(${mediaItems.size})"; super.addMediaItems(mediaItems) }
    override fun prepare() { calls += "prepare"; super.prepare() }
    override fun play() { calls += "play"; super.play() }
    override fun pause() { calls += "pause"; super.pause() }
    override fun seekTo(mediaItemIndex: Int, positionMs: Long) { calls += "seekTo($mediaItemIndex,$positionMs)"; super.seekTo(mediaItemIndex, positionMs) }
    override fun seekTo(positionMs: Long) { calls += "seekTo($positionMs)"; super.seekTo(positionMs) }
    fun count(prefix: String) = calls.count { it.startsWith(prefix) }
}

internal class RealMedia3QueueRig(
    val songs: List<Song>,
    startIndex: Int,
    startPositionMs: Long = 0L,
    playing: Boolean = false,
    sessionName: String = "lq",
    loadInitialQueue: Boolean = true,
) {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    val player: ExoPlayer = TestMediaSource.newPlayer(context)
    val recording = RecordingPlayer(player)
    val session: MediaSession = MediaSession.Builder(context, recording).setId("$sessionName-${counter++}").build()
    val controller: MediaController

    init {
        val future = MediaController.Builder(context, session.token).setConnectionHints(Bundle()).buildAsync()
        var guard = 0
        while (!future.isDone && guard++ < 100) idleMainLooper()
        controller = future.get(5, TimeUnit.SECONDS)
        idleMainLooper()
        if (loadInitialQueue) {
            controller.setMediaItems(songs.map { it.toPlaybackMediaItem() }, startIndex, startPositionMs)
            controller.prepare()
            if (playing) controller.play() else controller.pause()
            TestMediaSource.settle(150)
        }
    }

    /** The controller-side view (what PlayerController reads) captured at one instant. */
    data class Snapshot(
        val index: Int,
        val mediaId: String?,
        val positionMs: Long,
        val playWhenReady: Boolean,
        val count: Int,
        val ids: List<String>,
    )

    fun snapshot(): Snapshot = Snapshot(
        index = controller.currentMediaItemIndex,
        mediaId = controller.currentMediaItem?.mediaId,
        positionMs = controller.currentPosition,
        playWhenReady = controller.playWhenReady,
        count = controller.mediaItemCount,
        ids = (0 until controller.mediaItemCount).map { controller.getMediaItemAt(it).mediaId },
    )

    /** The server-side (real ExoPlayer) truth, for comparing against the controller's masked view. */
    fun serverIds(): List<String> = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }

    fun settle(ms: Long = 200) = TestMediaSource.settle(ms)

    fun releaseAll() {
        runCatching { controller.release() }
        runCatching { session.release() }
        runCatching { player.release() }
    }

    /** Records the callbacks a repair-style mutation provokes. */
    class Callbacks : Player.Listener {
        val events = mutableListOf<String>()
        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) { events += "timeline($reason)" }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { events += "transition(${mediaItem?.mediaId},$reason)" }
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) { events += "discontinuity($reason,${oldPosition.mediaItemIndex}->${newPosition.mediaItemIndex})" }
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { events += "playWhenReady($playWhenReady)" }
        override fun onPlaybackStateChanged(playbackState: Int) { events += "state($playbackState)" }
    }

    companion object {
        private var counter = 0

        fun song(id: Long) = Song(
            id = id, title = "Song $id", artist = "Artist", album = "Album", albumId = 0L, duration = 180_000L,
            uri = "content://media/$id", dateAdded = 0L, trackNumber = 0, year = 2020,
        )

        fun songs(vararg ids: Long) = ids.map(::song)
    }
}
