package com.launchpoint.wavdrop.playback

import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.launchpoint.wavdrop.ui.widget.WidgetPlaybackSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * CF-2M2: the LOGICAL widget-state consumer follows the session-facing player. Attached to the façade it must produce exactly
 * the sink calls it produces when attached directly to the physical player (single delegate), and across a delegate swap it
 * follows the NEW player's current item without a pause/play edge.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class WidgetPlaybackStateListenerTest {

    private class RecordingSink : WidgetStateSink {
        val calls = mutableListOf<String>()
        override suspend fun save(snapshot: WidgetPlaybackSnapshot) {
            calls += "save(${snapshot.title},playing=${snapshot.isPlaying},active=${snapshot.hasActiveMedia})"
        }
        override suspend fun updateIsPlaying(isPlaying: Boolean) { calls += "isPlaying($isPlaying)" }
        override suspend fun clear() { calls += "clear" }
        override fun requestUpdate() { calls += "update" }
    }

    private val scope = CoroutineScope(Dispatchers.Unconfined)

    private fun abc(name: String, index: Int = 0, playing: Boolean = true) =
        ScriptedPlayer(name, titles = listOf("A", "B", "C"), index = index, playing = playing)

    private fun script(physical: ScriptedPlayer) {
        physical.mutate { setPlayWhenReady(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) }
        idleMainLooper()
        physical.mutate { setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) }
        idleMainLooper()
        physical.mutate { setCurrentMediaItemIndex(1).setContentPositionMs(0L).setPositionDiscontinuity(Player.DISCONTINUITY_REASON_AUTO_TRANSITION, 0L) }
        idleMainLooper()
        physical.mutate { setPlaybackState(Player.STATE_IDLE) }
        idleMainLooper()
    }

    @Test fun widgetStateFollowsPlayPauseCurrentItemAndIdleIdenticallyThroughTheFacade() {
        val directPhysical = abc("D")
        val directSink = RecordingSink()
        directPhysical.addListener(WidgetPlaybackStateListener(directPhysical, scope, directSink))
        script(directPhysical)

        val physical = abc("F")
        val facade = SessionFacade(physical)
        val sink = RecordingSink()
        facade.addListener(WidgetPlaybackStateListener(facade, scope, sink))
        script(physical)

        assertEquals(directSink.calls, sink.calls)
        assertEquals(
            listOf(
                "isPlaying(false)", "update",                 // pause
                "isPlaying(true)", "update",                  // play
                "save(B,playing=true,active=true)", "update", // current item B
                "clear", "update",                            // IDLE clears the widget ...
                "isPlaying(false)", "update",                 // ... then reports not playing (Media3 callback order)
            ),
            sink.calls,
        )
    }

    @Test fun afterADelegateSwapTheWidgetFollowsTheNewPlayerWithoutAPlayPauseEdge() {
        val p1 = abc("P1", index = 0)
        val p2 = abc("P2", index = 1)
        val facade = SessionFacade(p1)
        val sink = RecordingSink()
        facade.addListener(WidgetPlaybackStateListener(facade, scope, sink))
        idleMainLooper()

        facade.replaceDelegate(p2, presentAsAutoTransition = true)
        idleMainLooper()
        assertEquals(listOf("save(B,playing=true,active=true)", "update"), sink.calls)

        sink.calls.clear()
        p1.mutate { setPlayWhenReady(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) } // retiring player: no leak
        idleMainLooper()
        assertEquals(emptyList<String>(), sink.calls)
        p2.mutate { setPlayWhenReady(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) }
        idleMainLooper()
        assertEquals(listOf("isPlaying(false)", "update"), sink.calls)
    }

    @Test fun productionRolloutGateRemainsFalse() {
        assertTrue(!CrossfadeRolloutPolicy.RUNTIME_ENABLED)
    }
}
