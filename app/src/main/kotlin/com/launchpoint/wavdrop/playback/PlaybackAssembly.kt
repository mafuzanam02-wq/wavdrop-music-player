package com.launchpoint.wavdrop.playback

import android.content.Context
import android.media.AudioManager
import androidx.media3.common.AudioAttributes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

/**
 * CF-2M3: how many physical players exist for a given rollout-gate value, and who owns the legacy CF-2L secondary.
 *
 * Coexistence decision (option A): the gated two-slot path's NEXT physical player is the single resource future promotion
 * (CF-2M4/2M5) will use. The legacy CF-2L secondary graph stays structurally present in source (CF-2M8 deletes the losing
 * architecture) but is never constructed in EITHER topology, so CURRENT + engine NEXT + a CF-2L secondary can never coexist.
 */
internal enum class PlaybackTopology(
    val physicalPlayerCount: Int,
    val usesPlayerEngine: Boolean,
    val constructsLegacyCrossfadeGraph: Boolean,
) {
    /** Shipping (gate false): one physical ExoPlayer with its own focus + noisy handling, exactly as before CF-2M3. */
    SINGLE_PLAYER(physicalPlayerCount = 1, usesPlayerEngine = false, constructsLegacyCrossfadeGraph = false),

    /** Gated (gate true): [PlayerEngine] with two physical slots, one focus owner, one noisy owner, one session id. */
    TWO_SLOT_ENGINE(physicalPlayerCount = 2, usesPlayerEngine = true, constructsLegacyCrossfadeGraph = false),
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
    /** The physical player services read/attach effects to: the only player (shipping) or the engine's CURRENT. */
    val primaryPlayer: ExoPlayer,
    /** Non-null only in [PlaybackTopology.TWO_SLOT_ENGINE]. */
    val engine: PlayerEngine<ExoPlayer>?,
    /** What the widget listener and PreviousBehaviorPlayer wrap: the façade (engine) or the physical player (shipping). */
    val logicalPlayer: Player,
) {
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
