package com.launchpoint.wavdrop.playback

import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import com.launchpoint.wavdrop.data.model.Song
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Real-ExoPlayer checks for the tracer's physical-player attribution: stable per-player serials, the production factory
 * wiring (every physical player gets its own listener in DEBUG), media-id extraction from a genuine timeline, the ORDER in
 * which a player delivers its playlist callback relative to `setMediaItems`, and end-to-end owner binding from a real
 * player's own `PLAYLIST_CHANGED` event. The attribution RULES are covered without a player in [PlaybackStartupTraceTest].
 *
 * The ordering probe covers the player level only. The Media3 MediaController -> MediaSession hop cannot be observed without
 * a running service, so binding is deliberately independent of whether the callback precedes or follows the install mark.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class PlaybackStartupTracePlayerAttributionTest {

    private val lines = mutableListOf<String>()

    @Before fun setUp() {
        PlaybackStartupTrace.resetForTest()
        PlaybackStartupTrace.enabled = true
        PlaybackStartupTrace.sink = { lines += it }
    }

    @After fun tearDown() = PlaybackStartupTrace.resetForTest()

    private fun stages() = lines.map { Regex("stage=(\\S+)").find(it)!!.groupValues[1] }

    private fun song(id: Long) = Song(
        id = id, title = "T", artist = "A", album = "B", albumId = 0L, duration = 1_000L,
        uri = "content://media/$id", dateAdded = 0L, trackNumber = 0, year = 2020,
    )

    private fun items(ids: List<Long>) = ids.map { MediaItem.Builder().setUri("content://media/$it").setMediaId(it.toString()).build() }

    private fun eventTimeOf(player: ExoPlayer) = AnalyticsListener.EventTime(
        0L, player.currentTimeline, 0, null, 0L, player.currentTimeline, 0, null, 0L, 0L,
    )

    private fun newWiredPlayer(): ExoPlayer {
        val player = TestMediaSource.newPlayer(RuntimeEnvironment.getApplication())
        player.addAnalyticsListener(PlaybackStartupTrace.analyticsListenerFor(player))
        return player
    }

    @Test fun eachPhysicalPlayerGetsADistinctStableSerial() {
        val context = RuntimeEnvironment.getApplication()
        val first = TestMediaSource.newPlayer(context)
        val second = TestMediaSource.newPlayer(context)
        val stranger = TestMediaSource.newPlayer(context)
        try {
            val a = PlaybackStartupTrace.registerPlayer(first)
            val b = PlaybackStartupTrace.registerPlayer(second)
            assertNotEquals(a, b)
            assertEquals(a, PlaybackStartupTrace.registerPlayer(first)) // idempotent
            assertEquals(a, PlaybackStartupTrace.serialOf(first))
            assertEquals(b, PlaybackStartupTrace.serialOf(second))
            assertEquals(-1, PlaybackStartupTrace.serialOf(stranger)) // never registered: unknown, not guessed
        } finally {
            first.release(); second.release(); stranger.release()
        }
    }

    @Test fun productionFactoryAttachesAnIndependentListenerToEveryPhysicalPlayerInDebug() {
        val factory = defaultPhysicalPlayerFactory(RuntimeEnvironment.getApplication())
        val attrs = AudioAttributes.DEFAULT
        val current = factory.create(attrs, handleAudioFocus = false, handleAudioBecomingNoisy = false)
        val next = factory.create(attrs, handleAudioFocus = false, handleAudioBecomingNoisy = false)
        try {
            if (com.launchpoint.wavdrop.BuildConfig.DEBUG) {
                val a = PlaybackStartupTrace.serialOf(current)
                val b = PlaybackStartupTrace.serialOf(next)
                assertTrue(a > 0 && b > 0)
                assertNotEquals(a, b)
            } else {
                assertEquals(-1, PlaybackStartupTrace.serialOf(current))
            }
        } finally {
            current.release(); next.release()
        }
    }

    @Test fun eventMediaIdReadsTheWindowsMediaIdFromARealTimeline() {
        val player = TestMediaSource.newPlayer(RuntimeEnvironment.getApplication())
        try {
            player.setMediaItems(items(listOf(10L, 11L, 12L)))
            val timeline = player.currentTimeline
            assertEquals(11L, PlaybackStartupTrace.eventMediaId(timeline, 1))
            assertEquals(Long.MIN_VALUE, PlaybackStartupTrace.eventMediaId(timeline, 3)) // out of range
            assertEquals(Long.MIN_VALUE, PlaybackStartupTrace.eventMediaId(timeline, -1))
            player.setMediaItem(MediaItem.Builder().setUri("content://media/x").setMediaId("not-a-number").build())
            assertEquals(Long.MIN_VALUE, PlaybackStartupTrace.eventMediaId(player.currentTimeline, 0)) // non-numeric id
        } finally {
            player.release()
        }
    }

    @Test fun aRealTimelinesFingerprintEqualsTheControllerSideFingerprintOfTheSameIds() {
        val ids = (1L..40L).toList()
        val player = TestMediaSource.newPlayer(RuntimeEnvironment.getApplication())
        try {
            player.setMediaItems(items(ids))
            val timeline = player.currentTimeline
            val observed = PlaybackStartupTrace.fingerprint(timeline.windowCount) { i ->
                PlaybackStartupTrace.eventMediaId(timeline, i)
            }
            assertEquals(PlaybackStartupTrace.fingerprint(ids.size) { ids[it] }, observed)
            // Same length, different content: different fingerprint.
            val other = (101L..140L).toList()
            assertNotEquals(PlaybackStartupTrace.fingerprint(other.size) { other[it] }, observed)
        } finally {
            player.release()
        }
    }

    // ---- ordering probe + end-to-end binding from a REAL player's own callback ---------------------------------------------

    @Test fun orderingProbe_aRealPlayersPlaylistCallbackRelativeToSetMediaItems() {
        val order = mutableListOf<String>()
        val player = TestMediaSource.newPlayer(RuntimeEnvironment.getApplication())
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onTimelineChanged(eventTime: AnalyticsListener.EventTime, reason: Int) {
                order += "timeline_changed(reason=$reason)"
            }
        })
        try {
            order += "before_setMediaItems"
            player.setMediaItems(items((1L..5L).toList()))
            order += "after_setMediaItems"
            shadowOf(Looper.getMainLooper()).idle()
            order += "after_idle"
            println("ORDER-PROBE $order")
            // The callback must have been delivered by the time the looper is idle, whichever side of the call it lands on.
            assertTrue(order.any { it.startsWith("timeline_changed") })
        } finally {
            player.release()
        }
    }

    @Test fun realPlayerCallbackBindsTheOwnerWhicheverSideOfTheInstallMarkItLandsOn() {
        val ids = (1L..30L).toList()
        val player = newWiredPlayer()
        try {
            PlaybackStartupTrace.begin("play_from_queue", ids.size, controllerConnected = true)
            PlaybackStartupTrace.expectStart(ids.first())
            PlaybackStartupTrace.onInstallStarting("play_from_queue", ids.map(::song)) // window opens BEFORE setMediaItems
            player.setMediaItems(items(ids), 0, 0L)
            PlaybackStartupTrace.onPlaylistInstalled("play_from_queue", ids.size, 1)
            shadowOf(Looper.getMainLooper()).idle()
            PlaybackStartupTrace.onPrepareIssued()
            assertEquals(1, stages().count { it == "playlist_visible_on_player" })

            val serial = PlaybackStartupTrace.serialOf(player)
            PlaybackStartupTrace.onAudioPositionAdvancing(serial, PlaybackStartupTrace.eventMediaId(player.currentTimeline, 0))
            assertTrue(stages().contains("complete"))
        } finally {
            player.release()
        }
    }

    @Test fun realPlayerCallbackWithSameLengthButDifferentContentDoesNotBind() {
        val requested = (1L..30L).toList()
        val actuallyInstalled = (101L..130L).toList() // same length, different ids: e.g. an unrelated playlist mutation
        val player = newWiredPlayer()
        try {
            PlaybackStartupTrace.begin("play_from_queue", requested.size, controllerConnected = true)
            PlaybackStartupTrace.expectStart(requested.first())
            PlaybackStartupTrace.onInstallStarting("play_from_queue", requested.map(::song))
            player.setMediaItems(items(actuallyInstalled), 0, 0L)
            PlaybackStartupTrace.onPlaylistInstalled("play_from_queue", requested.size, 1)
            shadowOf(Looper.getMainLooper()).idle()
            PlaybackStartupTrace.onPrepareIssued()
            assertTrue(stages().contains("playlist_change_unmatched"))
            assertFalse(stages().contains("playlist_visible_on_player"))

            val serial = PlaybackStartupTrace.serialOf(player)
            PlaybackStartupTrace.onAudioPositionAdvancing(serial, requested.first())
            assertFalse(stages().contains("complete"))
            assertTrue(lines.last().endsWith("reason=no_owner player=$serial"))
        } finally {
            player.release()
        }
    }

    @Test fun realPlayerCallbackBeforeTheInstallWindowOpensIsStaleAndDoesNotBind() {
        val ids = (1L..30L).toList()
        val player = newWiredPlayer()
        try {
            PlaybackStartupTrace.begin("play_from_queue", ids.size, controllerConnected = true)
            player.setMediaItems(items(ids), 0, 0L) // a playlist change BEFORE this request's install window (stale)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(stages().contains("playlist_change_ignored"))
            PlaybackStartupTrace.onInstallStarting("play_from_queue", ids.map(::song))
            PlaybackStartupTrace.onPlaylistInstalled("play_from_queue", ids.size, 1)
            PlaybackStartupTrace.onPrepareIssued()
            assertFalse(stages().contains("playlist_visible_on_player"))
        } finally {
            player.release()
        }
    }

    @Test fun crossfadeSecondaryPlayersGraftOfTheSameQueueMakesOwnershipAmbiguousWithRealPlayers() {
        val ids = (1L..30L).toList()
        val current = newWiredPlayer()
        val next = newWiredPlayer()
        try {
            PlaybackStartupTrace.begin("play_from_queue", ids.size, controllerConnected = true)
            PlaybackStartupTrace.expectStart(ids.first())
            PlaybackStartupTrace.onInstallStarting("play_from_queue", ids.map(::song))
            current.setMediaItems(items(ids), 0, 0L)
            PlaybackStartupTrace.onPlaylistInstalled("play_from_queue", ids.size, 1)
            PlaybackStartupTrace.onPrepareIssued()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, stages().count { it == "playlist_visible_on_player" })

            // The NEXT slot receives a graft of the same queue before the request finished.
            next.setMediaItems(items(ids), 0, 0L)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(stages().contains("owner_ambiguous"))

            // Audio from either player is now refused, with the right reasons.
            val currentSerial = PlaybackStartupTrace.serialOf(current)
            val nextSerial = PlaybackStartupTrace.serialOf(next)
            PlaybackStartupTrace.onAudioPositionAdvancing(nextSerial, PlaybackStartupTrace.eventMediaId(eventTimeOf(next).timeline, 0))
            PlaybackStartupTrace.onAudioPositionAdvancing(currentSerial, PlaybackStartupTrace.eventMediaId(eventTimeOf(current).timeline, 0))
            assertFalse(stages().contains("complete"))
            assertEquals(2, lines.count { it.contains("reason=owner_ambiguous") })
        } finally {
            current.release(); next.release()
        }
    }

    @Test fun aPlayersOwnListenerAttributesItsAudioCallbacksToThatPlayersSerial() {
        val ids = listOf(10L, 11L)
        val owner = newWiredPlayer()
        val secondary = TestMediaSource.newPlayer(RuntimeEnvironment.getApplication())
        val secondaryListener = PlaybackStartupTrace.analyticsListenerFor(secondary)
        try {
            PlaybackStartupTrace.begin("play_from_queue", ids.size, controllerConnected = true)
            PlaybackStartupTrace.expectStart(10L)
            PlaybackStartupTrace.onInstallStarting("play_from_queue", ids.map(::song))
            owner.setMediaItems(items(ids), 0, 0L)
            PlaybackStartupTrace.onPlaylistInstalled("play_from_queue", ids.size, 1)
            PlaybackStartupTrace.onPrepareIssued()
            shadowOf(Looper.getMainLooper()).idle()
            // The secondary has the same items but its playlist callback was never delivered to the tracer (no listener on it yet).
            secondary.setMediaItems(items(ids), 0, 0L)
            shadowOf(Looper.getMainLooper()).idle()

            // The secondary player (e.g. the crossfade NEXT slot) reports audio first: attributed to ITS serial, refused.
            secondaryListener.onAudioPositionAdvancing(eventTimeOf(secondary), 0L)
            assertTrue(lines.last().contains("audio_position_advancing_unattributed") && lines.last().contains("reason=other_player"))
            assertFalse(stages().contains("complete"))

            val ownerListener = PlaybackStartupTrace.analyticsListenerFor(owner) // same player: idempotent serial
            ownerListener.onAudioPositionAdvancing(eventTimeOf(owner), 0L)
            assertTrue(stages().contains("complete"))
        } finally {
            owner.release(); secondary.release()
        }
    }
}
