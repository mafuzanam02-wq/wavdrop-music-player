package com.launchpoint.wavdrop.playback

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import org.robolectric.RuntimeEnvironment

internal const val SHARED_SESSION_ID = 4242

/**
 * CF-2M3 test fixture: a real [PlayerEngine] over two hand-written physical players. P1 is the initial CURRENT (a ready,
 * paused two-item queue); P2 is NEXT (empty, idle, paused). Release is observed through the engine's injected release seam so
 * "released once" is countable; the engine, façade and focus manager are all real.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal class PlayerEngineFixture(
    noisy: Boolean = true,
    context: Context = RuntimeEnvironment.getApplication(),
) {
    val p1 = ScriptedPlayer("P1", titles = listOf("A", "B"), playing = false, state = Player.STATE_READY, audioSessionId = SHARED_SESSION_ID)
    val p2 = ScriptedPlayer("P2", titles = emptyList(), playing = false, state = Player.STATE_IDLE, audioSessionId = SHARED_SESSION_ID)
    val releases = mutableListOf<String>()

    val engine = PlayerEngine(
        context = context,
        first = p1,
        second = p2,
        audioSessionId = SHARED_SESSION_ID,
        audioAttributes = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
        handleAudioBecomingNoisy = noisy,
        releasePlayer = { releases += it.name },
    )
    val facade: SessionFacade get() = engine.facade

    val events = EventRecorder().also { engine.facade.addListener(it) }

    init { idleMainLooper() }
}
