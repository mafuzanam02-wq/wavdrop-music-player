package com.launchpoint.wavdrop.playback

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.google.common.util.concurrent.Futures

/*
 * P5-E: the ONLY way bounded-window code reaches the physical playlist.
 *
 * Until P5-D the physical window was reached through the app's logical MediaController, which was acceptable while that controller
 * saw the physical timeline. Once the session façade presents LOGICAL indices, a physical index sent through the public controller
 * would be interpreted as a logical one. So everything index-bearing and everything that proves what the physical player holds goes
 * through this narrow in-process port, which fronts the physical CURRENT player and nothing else:
 *
 *  - Full mode never touches it (the Full path keeps the unchanged controller routing);
 *  - it exposes only the operations the window adapter needs, not a general second playback authority (play/pause/prepare and every
 *    other index-free transport command stay with the logical controller and the engine's logical play-when-ready owner);
 *  - [owner] names the physical player it currently fronts; it changes at promotion or replacement, which is how work started on a
 *    retired player is recognised and refused;
 *  - [verifier] observes the physical player itself, in-process and synchronously. There is no IPC and no optimistic client state in
 *    between, so there is no externally callable diagnostic command (it was removed from the session).
 */
internal interface PhysicalWindowPort {
    /** The application looper of the physical player(s); the only thread the port may be used on. */
    val looper: Looper

    /** Identity of the physical player this port fronts right now. Changes when the engine promotes or the player is replaced. */
    val owner: Any

    val mediaItemCount: Int
    val currentMediaItemIndex: Int
    val currentMediaId: String?
    val currentPosition: Long
    fun mediaIdAt(index: Int): String
    fun mediaItemAt(index: Int): MediaItem

    var repeatMode: Int
    var shuffleModeEnabled: Boolean

    fun setMediaItems(items: List<MediaItem>, startIndex: Int, startPositionMs: Long)
    fun addMediaItems(index: Int, items: List<MediaItem>)
    fun addMediaItems(items: List<MediaItem>)
    fun removeMediaItems(fromIndex: Int, toIndexExclusive: Int)
    fun seekTo(index: Int, positionMs: Long)

    /** Observes the PHYSICAL player. For [EnginePhysicalPort] listeners follow the logical CURRENT across promotion. */
    fun addListener(listener: Player.Listener)
    fun removeListener(listener: Player.Listener)

    /** Authoritative in-process observation of the physical player, or null when this port cannot provide one. */
    val verifier: PhysicalInstallVerifier?
}

/** A port over ONE physical [Player] (a bare ExoPlayer, or any wrapper that stands in for it in a test). */
internal class PhysicalPlayerPort(private val player: Player) : PhysicalWindowPort {
    override val looper: Looper get() = player.applicationLooper
    override val owner: Any get() = player
    override val mediaItemCount: Int get() = player.mediaItemCount
    override val currentMediaItemIndex: Int get() = player.currentMediaItemIndex
    override val currentMediaId: String? get() = player.currentMediaItem?.mediaId
    override val currentPosition: Long get() = player.currentPosition
    override fun mediaIdAt(index: Int): String = player.getMediaItemAt(index).mediaId
    override fun mediaItemAt(index: Int): MediaItem = player.getMediaItemAt(index)

    override var repeatMode: Int
        get() = player.repeatMode
        set(value) { player.repeatMode = value }
    override var shuffleModeEnabled: Boolean
        get() = player.shuffleModeEnabled
        set(value) { player.shuffleModeEnabled = value }

    override fun setMediaItems(items: List<MediaItem>, startIndex: Int, startPositionMs: Long) =
        player.setMediaItems(items, startIndex, startPositionMs)
    override fun addMediaItems(index: Int, items: List<MediaItem>) = player.addMediaItems(index, items)
    override fun addMediaItems(items: List<MediaItem>) = player.addMediaItems(items)
    override fun removeMediaItems(fromIndex: Int, toIndexExclusive: Int) = player.removeMediaItems(fromIndex, toIndexExclusive)
    override fun seekTo(index: Int, positionMs: Long) = player.seekTo(index, positionMs)

    override fun addListener(listener: Player.Listener) = player.addListener(listener)
    override fun removeListener(listener: Player.Listener) = player.removeListener(listener)

    override val verifier: PhysicalInstallVerifier = PhysicalInstallVerifier { token ->
        Futures.immediateFuture(PhysicalObservation.observe(player, token))
    }
}

/**
 * The production port: always fronts the engine's CURRENT physical player. After a promotion [owner] is the promoted player, so a
 * pending operation that began on the retired one is refused by the adapter. Listeners are registered through the engine, which
 * moves them with the CURRENT role.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class EnginePhysicalPort(private val engine: PlayerEngine<ExoPlayer>) : PhysicalWindowPort {
    private val current: ExoPlayer
        get() {
            check(!engine.released) { "physical engine released" }
            return engine.currentPlayer
        }

    override val looper: Looper get() = engine.currentPlayer.applicationLooper
    override val owner: Any get() = engine.currentPlayer
    override val mediaItemCount: Int get() = current.mediaItemCount
    override val currentMediaItemIndex: Int get() = current.currentMediaItemIndex
    override val currentMediaId: String? get() = current.currentMediaItem?.mediaId
    override val currentPosition: Long get() = current.currentPosition
    override fun mediaIdAt(index: Int): String = current.getMediaItemAt(index).mediaId
    override fun mediaItemAt(index: Int): MediaItem = current.getMediaItemAt(index)

    override var repeatMode: Int
        get() = current.repeatMode
        set(value) { current.repeatMode = value }
    override var shuffleModeEnabled: Boolean
        get() = current.shuffleModeEnabled
        set(value) { current.shuffleModeEnabled = value }

    override fun setMediaItems(items: List<MediaItem>, startIndex: Int, startPositionMs: Long) =
        current.setMediaItems(items, startIndex, startPositionMs)
    override fun addMediaItems(index: Int, items: List<MediaItem>) = current.addMediaItems(index, items)
    override fun addMediaItems(items: List<MediaItem>) = current.addMediaItems(items)
    override fun removeMediaItems(fromIndex: Int, toIndexExclusive: Int) = current.removeMediaItems(fromIndex, toIndexExclusive)
    override fun seekTo(index: Int, positionMs: Long) = current.seekTo(index, positionMs)

    override fun addListener(listener: Player.Listener) = engine.addCurrentPlayerListener(listener)
    override fun removeListener(listener: Player.Listener) = engine.removeCurrentPlayerListener(listener)

    override val verifier: PhysicalInstallVerifier = PhysicalInstallVerifier { token ->
        Futures.immediateFuture(PhysicalObservation.observe(current, token))
    }
}
