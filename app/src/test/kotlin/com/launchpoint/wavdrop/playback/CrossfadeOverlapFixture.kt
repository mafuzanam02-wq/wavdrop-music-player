package com.launchpoint.wavdrop.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.launchpoint.wavdrop.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue

/** CF-2M6: a manual scheduler so a test can run, hold and replay (stale) promotion pulses. */
internal class OverlapScheduler : CrossfadeTimingScheduler {
    class Pending(val delayMs: Long, val block: () -> Unit) { var active = true }
    val all = mutableListOf<Pending>()
    val activeNow get() = all.filter { it.active }
    override fun postDelayed(delayMs: Long, block: () -> Unit) { all += Pending(delayMs, block) }
    override fun cancelAll() { all.forEach { it.active = false } }
    fun runNext() { val p = activeNow.single(); p.active = false; p.block() }
}

/**
 * CF-2M6 rig: the M5 PromotionRig (real engine + façade + focus owner over two scripted physicals, NEXT Ready for from -> from+1) plus
 * a real promotion runtime, the shared cancel sink and a service-like CURRENT observer (the same classification functions the
 * service uses). [inOverlap] drives a real promotion; afterwards logs and events are cleared so a test sees only the interruption.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal class OverlapRig(
    val titles: List<String> = listOf("A", "B", "C", "D", "E", "F"),
    val from: Int = 1,
    hook: ((PromotionStep) -> Unit)? = null,
) {
    val base = PromotionRig(titles = titles, from = from, hook = hook).playAndPrepare()
    val engine get() = base.engine
    val p1 get() = base.p1
    val p2 get() = base.p2
    val facade get() = base.f.facade
    val events get() = base.f.events.events
    val key get() = base.key
    val journal = mutableListOf<String>()
    val scheduler = OverlapScheduler()
    val logs = mutableListOf<String>()
    var now = 1_000L
    var generation = 7L
    var eq = false
    var configured = 6_000L
    var currentIndex = from
    val songs = titles.indices.map { song(it + 1L) }

    val runtime = CrossfadePromotionRuntime(
        engine = base.engine,
        snapshotProvider = {
            CrossfadeRuntimeSnapshot(
                queueGeneration = generation, playbackQueue = songs, currentPlaybackIndex = currentIndex,
                repeatMode = RepeatMode.OFF, shuffleEnabled = false, isPlaying = true, isExternalPlayback = false,
                playerQueueNeedsSync = false, controllerConnected = true, equalizerEnabled = eq,
            )
        },
        configuredDurationMsProvider = { configured },
        scheduler = scheduler,
        clock = { now },
        debugLog = { logs += it },
    )

    /** The ONE shared cancellation sink, as the service builds it (runtime only: the engine topology). */
    val sink = CrossfadeCancelSink { runtime.cancel(it) }

    init {
        base.p1.journal = journal
        base.p2.journal = journal
        // The service's CURRENT physical observer, using the production classification functions.
        engine.addCurrentPlayerListener(object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) = recoverCrossfadeFromPrimaryPlaybackError(sink)
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (isPrimaryTerminalPlaybackState(playbackState)) recoverCrossfadeFromPrimaryTerminalState(sink)
            }
        })
    }

    fun position(ms: Long) = p1.mutate { setContentPositionMs(ms) }

    /** Real promotion at the window start, then half the fade elapsed. Clears command logs and events. */
    fun inOverlap(elapsedMs: Long = 3_000L): OverlapRig {
        position(173_500L)
        runtime.evaluate()
        check(runtime.state is PromotionOverlapState.Overlap) { runtime.state }
        idleMainLooper()
        if (elapsedMs > 0) { now += elapsedMs; runtime.tick() }
        p1.commands.clear(); p2.commands.clear(); journal.clear(); events.clear(); logs.clear()
        return this
    }

    fun duck(multiplier: Float) {
        val audio = org.robolectric.RuntimeEnvironment.getApplication().getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        val listener = org.robolectric.Shadows.shadowOf(audio).lastAudioFocusRequest.listener
        listener.onAudioFocusChange(if (multiplier < 1f) android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK else android.media.AudioManager.AUDIOFOCUS_GAIN)
        idleMainLooper()
    }

    fun focus(change: Int) {
        val audio = org.robolectric.RuntimeEnvironment.getApplication().getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        org.robolectric.Shadows.shadowOf(audio).lastAudioFocusRequest.listener.onAudioFocusChange(change)
        idleMainLooper()
    }

    fun interrupted() = runtime.lastOutcome as? PromotionOutcome.Interrupted

    /** The invariants of every settled overlap: ONE authoritative player (B), A silent, stopped, emptied and recycled. */
    fun assertSettledToB(expected: PromotionInterruption? = null, bVolume: Float = 1f, queueMutated: Boolean = false) {
        idleMainLooper()
        assertEquals(PromotionOverlapState.Idle, runtime.state)
        expected?.let { assertEquals(PromotionOutcome.Interrupted(key, it, retiringRecycled = true), runtime.lastOutcome) }
        assertSame(p2, engine.currentPlayer)
        assertSame("the session still follows B", p2, facade.delegatePlayer)
        assertEquals(0, p1.mediaItemCount)
        assertFalse(p1.playWhenReady)
        assertEquals("A is silent/neutral after recycle", 1f, p1.volume, 0f)
        assertEquals(bVolume, p2.volume, 1e-6f)
        assertFalse(engine.promotionActive)
        assertTrue(engine.retiringPlayer == null)
        assertTrue("no timeline rewrite from settlement: $events", queueMutated || events.none { it.startsWith("timeline(") })
        assertTrue("never a second AUTO/B transition from settlement: $events", events.none { it == "transition(${titles[from + 1]},AUTO)" })
        assertTrue("B is never re-prepared, reloaded or re-promoted", p2.commands.none { it.contains("prepare") || (!queueMutated && it.contains("setMediaItems")) })
        assertNoLogicalCommandReachedA()
    }

    /** A only ever receives its own retirement cleanup: never a seek, navigation, queue load, prepare or repeat change. */
    fun assertNoLogicalCommandReachedA() {
        val forbidden = listOf("seek(", "prepare", "setMediaItems", "addMediaItems", "setRepeatMode", "moveMediaItems", "setShuffle")
        assertTrue("A received a logical command: ${p1.commands}", p1.commands.none { c -> val name = c.substringAfter('.'); forbidden.any { name.startsWith(it) } })
    }

    /** The retiring player's cleanup (stop) happened strictly BEFORE the first logical command reached B. */
    fun assertACutBeforeBCommand(bCommandPrefix: String) {
        val cut = journal.indexOf("P1.stop()")
        val b = journal.indexOfFirst { it.startsWith(bCommandPrefix) }
        assertTrue("journal=$journal", cut >= 0 && b >= 0)
        assertTrue("A must be cut before B receives `$bCommandPrefix`: $journal", cut < b)
    }

    /** B's commands minus volume writes (the composer legitimately restores B to full gain at settlement). */
    fun bLogical(): List<String> = p2.commands.filterNot { it.contains("setVolume") }

    fun mediaItem(title: String): MediaItem = ScriptedPlayer.mediaItem(title)

    private fun song(id: Long) = Song(
        id = id, title = "S$id", artist = "A", album = "B", albumId = 0L, duration = 180_000L,
        uri = "content://media/$id", dateAdded = 0L, trackNumber = 0, year = 2020,
    )
}
