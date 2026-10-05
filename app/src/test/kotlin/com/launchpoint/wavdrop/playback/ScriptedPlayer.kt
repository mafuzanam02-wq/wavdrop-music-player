package com.launchpoint.wavdrop.playback

import android.os.Looper
import androidx.media3.common.C
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
    audioSessionId: Int = androidx.media3.common.C.AUDIO_SESSION_ID_UNSET,
) : SimpleBasePlayer(looper) {

    val commands = mutableListOf<String>()
    private var current: State = buildState(titles, index, positionMs, playing, state, audioSessionId)

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
        // A real player goes IDLE -> BUFFERING on prepare (only with something to prepare); the test then calls [becomeReady].
        if (current.playbackState == Player.STATE_IDLE && current.playlist.isNotEmpty()) {
            current = current.buildUpon().setPlayerError(null).setPlaybackState(Player.STATE_BUFFERING).build()
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        commands += "$name.stop()"
        current = current.buildUpon().setPlaybackState(Player.STATE_IDLE).build()
        return Futures.immediateVoidFuture()
    }

    // ── playlist commands (CF-2M4): stable per-item uids, so an item's physical identity survives inserts around it ──

    private fun newItemData(item: MediaItem) = MediaItemData.Builder(Any())
        .setMediaItem(item)
        .setDurationUs(180_000_000L)
        .setIsSeekable(true)
        .build()

    override fun handleSetMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        commands += "$name.setMediaItems(${mediaItems.size})"
        current = current.buildUpon()
            .setPlaylist(mediaItems.map(::newItemData))
            .setCurrentMediaItemIndex(if (startIndex == C.INDEX_UNSET) 0 else startIndex)
            .setContentPositionMs(if (startPositionMs == C.TIME_UNSET) 0L else startPositionMs)
            .build()
        return Futures.immediateVoidFuture()
    }

    /** Makes every playlist insertion throw (graft-chunk failure tests). */
    var throwOnAdd = false

    override fun handleAddMediaItems(index: Int, mediaItems: MutableList<MediaItem>): ListenableFuture<*> {
        if (throwOnAdd) throw IllegalStateException("scripted add failure")
        commands += "$name.addMediaItems($index,${mediaItems.size})"
        val playlist = current.playlist.toMutableList().also { it.addAll(index, mediaItems.map(::newItemData)) }
        // Media3 semantics modelled here (and proven on a REAL ExoPlayer in NextSlotGraftTest): inserting at or before the
        // current item shifts the current index; the current item and its position are unchanged.
        val shifted = if (index <= current.currentMediaItemIndex && current.playlist.isNotEmpty()) {
            current.currentMediaItemIndex + mediaItems.size
        } else {
            current.currentMediaItemIndex
        }
        current = current.buildUpon().setPlaylist(playlist).setCurrentMediaItemIndex(shifted).build()
        return Futures.immediateVoidFuture()
    }

    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
        commands += "$name.removeMediaItems($fromIndex,$toIndex)"
        val playlist = current.playlist.toMutableList().also { it.subList(fromIndex, toIndex).clear() }
        val removedBeforeCurrent = (minOf(toIndex, current.currentMediaItemIndex) - fromIndex).coerceAtLeast(0)
        val builder = current.buildUpon().setPlaylist(playlist)
            .setCurrentMediaItemIndex((current.currentMediaItemIndex - removedBeforeCurrent).coerceIn(0, maxOf(0, playlist.size - 1)))
        if (playlist.isEmpty()) builder.setCurrentMediaItemIndex(0).setPlaybackState(Player.STATE_IDLE).setContentPositionMs(0L)
        current = builder.build()
        return Futures.immediateVoidFuture()
    }

    /** The scripted physical player finished preparing (BUFFERING -> READY). */
    fun becomeReady() = mutate { setPlaybackState(Player.STATE_READY) }

    /** The scripted physical player reports a playback error (and goes IDLE, like ExoPlayer). */
    fun failWith(errorCode: Int = PlaybackException.ERROR_CODE_IO_UNSPECIFIED) = mutate {
        setPlayerError(PlaybackException("scripted", null, errorCode))
        setPlaybackState(Player.STATE_IDLE)
    }

    /** The mediaIds currently in the playlist, in order. */
    val mediaIds: List<String> get() = (0 until mediaItemCount).map { getMediaItemAt(it).mediaId }

    /** The physical uid of the item at [index] (identity of that physical decode). */
    fun uidAt(index: Int): Any = current.playlist[index].uid

    override fun handleRelease(): ListenableFuture<*> {
        commands += "$name.release()"
        return Futures.immediateVoidFuture()
    }

    override fun handleSetVolume(volume: Float, volumeCommand: Int): ListenableFuture<*> {
        commands += "$name.setVolume($volume)"
        current = current.buildUpon().setVolume(volume).build()
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

        fun buildState(titles: List<String>, index: Int, positionMs: Long, playing: Boolean, state: Int, audioSessionId: Int = androidx.media3.common.C.AUDIO_SESSION_ID_UNSET): SimpleBasePlayer.State {
            val items = titles.mapIndexed { position, it ->
                SimpleBasePlayer.MediaItemData.Builder("$it#$position")
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
                .setAudioSessionId(audioSessionId)
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

    override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
        events += "suppression($playbackSuppressionReason)"
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
