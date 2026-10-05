package com.launchpoint.wavdrop.playback

import android.content.Context
import android.media.AudioManager
import androidx.media3.common.AudioAttributes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

/**
 * How many physical players exist for a given rollout-gate value.
 *
 * The gated two-slot path owns exactly two physical players (CURRENT + NEXT); there is no other crossfade player.
 */
internal enum class PlaybackTopology(
    val physicalPlayerCount: Int,
    val usesPlayerEngine: Boolean,
) {
    /** Shipping (gate false): one physical ExoPlayer with its own focus + noisy handling, exactly as before CF-2M3. */
    SINGLE_PLAYER(physicalPlayerCount = 1, usesPlayerEngine = false),

    /** Gated (gate true): [PlayerEngine] with two physical slots, one focus owner, one noisy owner, one session id. */
    TWO_SLOT_ENGINE(physicalPlayerCount = 2, usesPlayerEngine = true),
    ;

    companion object {
        fun forGate(rolloutEnabled: Boolean): PlaybackTopology = if (rolloutEnabled) TWO_SLOT_ENGINE else SINGLE_PLAYER
    }
}

/** Constructs one physical ExoPlayer. Seam so tests can count constructions and the shipping path stays a plain builder. */
internal fun interface PhysicalPlayerFactory {
    fun create(audioAttributes: AudioAttributes, handleAudioFocus: Boolean, handleAudioBecomingNoisy: Boolean): ExoPlayer
}

/** Allocates one Android audio-session id. Seam so JVM tests are deterministic. */
internal fun interface AudioSessionIdProvider {
    fun generate(): Int
}

internal class PlaybackAssembly(
    val topology: PlaybackTopology,
    /** The INITIAL physical player (shipping: the only player; engine: the first CURRENT). Not role-aware: see [currentPlayer]. */
    val primaryPlayer: ExoPlayer,
    /** Non-null only in [PlaybackTopology.TWO_SLOT_ENGINE]. */
    val engine: PlayerEngine<ExoPlayer>?,
    /** What the widget listener and PreviousBehaviorPlayer wrap: the façade (engine) or the physical player (shipping). */
    val logicalPlayer: Player,
) {
    /**
     * The physical player that is the LOGICAL CURRENT right now. In the engine topology this changes at promotion, so anything
     * that means "the current player" must read this (or the façade), never the initial [primaryPlayer].
     */
    val currentPlayer: ExoPlayer get() = engine?.currentPlayer ?: primaryPlayer

    /** Registers a PHYSICAL observer that follows the logical CURRENT across promotions (a retiring player never reaches it). */
    fun addCurrentPlayerListener(listener: Player.Listener) {
        if (engine != null) engine.addCurrentPlayerListener(listener) else primaryPlayer.addListener(listener)
    }

    /** The user's "pause on audio disconnect" preference, routed to the single noisy owner of this topology. */
    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    fun setHandleAudioBecomingNoisy(enabled: Boolean) {
        if (engine != null) engine.setHandleAudioBecomingNoisy(enabled) else primaryPlayer.setHandleAudioBecomingNoisy(enabled)
    }
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal fun assemblePlayback(
    context: Context,
    rolloutEnabled: Boolean,
    audioAttributes: AudioAttributes,
    playerFactory: PhysicalPlayerFactory = defaultPhysicalPlayerFactory(context),
    sessionIdProvider: AudioSessionIdProvider = defaultAudioSessionIdProvider(context),
): PlaybackAssembly {
    val topology = PlaybackTopology.forGate(rolloutEnabled)
    if (!topology.usesPlayerEngine) {
        val player = playerFactory.create(audioAttributes, handleAudioFocus = true, handleAudioBecomingNoisy = true)
        return PlaybackAssembly(topology, player, engine = null, logicalPlayer = player)
    }
    // Engine path: neither physical handles focus or noisy; ONE id is assigned to BOTH before either is prepared.
    val sessionId = sessionIdProvider.generate()
    val first = playerFactory.create(audioAttributes, handleAudioFocus = false, handleAudioBecomingNoisy = false)
    val second = playerFactory.create(audioAttributes, handleAudioFocus = false, handleAudioBecomingNoisy = false)
    first.audioSessionId = sessionId
    second.audioSessionId = sessionId
    val engine = PlayerEngine(context, first, second, sessionId, audioAttributes)
    return PlaybackAssembly(topology, engine.currentPlayer, engine, engine.facade)
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal fun defaultPhysicalPlayerFactory(context: Context) = PhysicalPlayerFactory { attributes, handleFocus, handleNoisy ->
    ExoPlayer.Builder(context)
        .setAudioAttributes(attributes, handleFocus)
        .setHandleAudioBecomingNoisy(handleNoisy)
        .build()
}

internal fun defaultAudioSessionIdProvider(context: Context) = AudioSessionIdProvider {
    (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).generateAudioSessionId()
}
