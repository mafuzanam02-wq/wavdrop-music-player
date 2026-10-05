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
    graftScheduler: NextSlotGraftScheduler? = null,
    p1Titles: List<String> = listOf("A", "B"),
    p1Index: Int = 0,
    promotionHook: ((PromotionStep) -> Unit)? = null,
) {
    val p1 = ScriptedPlayer("P1", titles = p1Titles, index = p1Index, playing = false, state = Player.STATE_READY, audioSessionId = SHARED_SESSION_ID)
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
        nextSlotGraftScheduler = graftScheduler,
        promotionStepHook = promotionHook,
    )
    val facade: SessionFacade get() = engine.facade

    val events = EventRecorder().also { engine.facade.addListener(it) }

    init { idleMainLooper() }
}

/**
 * CF-2M4 correction: a manual looper-turn scheduler so a test can step the chunked graft one turn at a time, observe progress
 * between turns, invalidate at an exact point, and then deliberately run a STALE turn that survived (see [snapshot]).
 */
internal class ManualGraftScheduler : NextSlotGraftScheduler {
    private val turns = ArrayDeque<() -> Unit>()
    var cancelCalls = 0
        private set

    val pending: Int get() = turns.size

    override fun post(block: () -> Unit) { turns.addLast(block) }

    override fun cancelAll() { cancelCalls++; turns.clear() }

    /** Runs exactly one pending turn; false when none. */
    fun runNext(): Boolean {
        val turn = turns.removeFirstOrNull() ?: return false
        turn()
        return true
    }

    fun runAll(maxTurns: Int = 100_000) {
        var n = 0
        while (runNext()) check(++n < maxTurns) { "graft did not terminate" }
    }

    /** Copies the pending turns so a test can run them AFTER a cancellation (the token guard must make them inert). */
    fun snapshot(): List<() -> Unit> = turns.toList()
}
