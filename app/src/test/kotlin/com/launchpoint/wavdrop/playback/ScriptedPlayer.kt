package com.launchpoint.wavdrop.playback

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.robolectric.Shadows.shadowOf

/**
 * Hand-written deterministic physical-player stand-in for CF-2M2. It is a real Media3 [SimpleBasePlayer] driven by an
 * explicit state, so its listener/event semantics are the genuine Player contract (no mocking framework). Every command that
 * reaches it is recorded in [commands]; tests change its physical state with [mutate].
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal class ScriptedPlayer(
    val name: String,
    looper: Looper = Looper.getMainLooper(),
    titles: List<String> = listOf("A"),
    index: Int = 0,
    positionMs: Long = 0L,
    playing: Boolean = true,
    state: Int = Player.STATE_READY,
    // false only for a player that lives on a different looper (its first access must then happen on that thread).
    eagerInit: Boolean = true,
) : SimpleBasePlayer(looper) {

    val commands = mutableListOf<String>()
    private var current: State = buildState(titles, index, positionMs, playing, state)

    // SimpleBasePlayer snapshots its state lazily on first access; force it NOW so later mutate() calls produce real diffs.
    init { if (eagerInit) playbackState }

    override fun getState(): State = current

    /** Physical change made by the "engine" (not a façade command). Applies and notifies listeners. */
    fun mutate(block: State.Builder.() -> Unit) {
        current = current.buildUpon().apply(block).build()
        invalidateState()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        commands += "$name.setPlayWhenReady($playWhenReady)"
        current = current.buildUpon().setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST).build()
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        commands += "$name.prepare()"
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        commands += "$name.stop()"
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        commands += "$name.release()"
        return Futures.immediateVoidFuture()
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        commands += "$name.setRepeatMode($repeatMode)"
        current = current.buildUpon().setRepeatMode(repeatMode).build()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        commands += "$name.seek($mediaItemIndex,$positionMs)"
        current = current.buildUpon().setCurrentMediaItemIndex(mediaItemIndex).setContentPositionMs(positionMs).build()
        return Futures.immediateVoidFuture()
    }

    companion object {
        fun mediaItem(title: String): MediaItem = MediaItem.Builder()
            .setMediaId(title)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
            .build()

        fun buildState(titles: List<String>, index: Int, positionMs: Long, playing: Boolean, state: Int): SimpleBasePlayer.State {
            val items = titles.map {
                SimpleBasePlayer.MediaItemData.Builder(it)
                    .setMediaItem(mediaItem(it))
                    .setDurationUs(180_000_000L)
                    .setIsSeekable(true)
                    .build()
            }
            return SimpleBasePlayer.State.Builder()
                .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
                .setPlaylist(items)
                .setCurrentMediaItemIndex(index)
                .setPlaybackState(state)
                .setPlayWhenReady(playing, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                .setContentPositionMs(positionMs)
                .build()
        }
    }
}

/** Records the logical event stream of any [Player] as short stable strings. */
internal class EventRecorder : Player.Listener {
    val events = mutableListOf<String>()

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        events += "transition(${mediaItem?.mediaId},${transitionName(reason)})"
    }

    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        events += "discontinuity(${oldPosition.mediaItemIndex}@${oldPosition.positionMs}->${newPosition.mediaItemIndex}@${newPosition.positionMs},${discontinuityName(reason)})"
    }

    override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
        events += "timeline(count=${timeline.windowCount},${if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) "PLAYLIST_CHANGED" else "SOURCE_UPDATE"})"
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        events += "state($playbackState)"
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        events += "isPlaying($isPlaying)"
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        events += "playWhenReady($playWhenReady,$reason)"
    }

    override fun onRepeatModeChanged(repeatMode: Int) {
        events += "repeat($repeatMode)"
    }

    override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
        events += "metadata(${mediaMetadata.title})"
    }

    override fun onPlayerError(error: PlaybackException) {
        events += "error(${error.errorCode})"
    }

    private fun transitionName(reason: Int) = when (reason) {
        Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> "AUTO"
        Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> "SEEK"
        Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> "PLAYLIST_CHANGED"
        Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> "REPEAT"
        else -> "?$reason"
    }

    private fun discontinuityName(reason: Int) = when (reason) {
        Player.DISCONTINUITY_REASON_AUTO_TRANSITION -> "AUTO_TRANSITION"
        Player.DISCONTINUITY_REASON_SEEK -> "SEEK"
        Player.DISCONTINUITY_REASON_REMOVE -> "REMOVE"
        Player.DISCONTINUITY_REASON_SKIP -> "SKIP"
        Player.DISCONTINUITY_REASON_INTERNAL -> "INTERNAL"
        Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT -> "SEEK_ADJUSTMENT"
        else -> "?$reason"
    }
}

internal fun idleMainLooper() = shadowOf(Looper.getMainLooper()).idle()
