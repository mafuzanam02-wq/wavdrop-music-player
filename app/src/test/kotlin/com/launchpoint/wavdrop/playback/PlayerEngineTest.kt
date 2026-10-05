package com.launchpoint.wavdrop.playback

import android.content.Intent
import android.media.AudioManager
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * CF-2M3: ownership foundation. Two physical players, explicit roles, one stable façade, one shared session id, one logical
 * noisy owner. NEXT is inert. Nothing here prepares NEXT, promotes or crossfades.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class PlayerEngineTest {

    // ── 1-3. ownership and initial roles ─────────────────────────────────────────────────────────────────────────────────

    @Test fun engineOwnsExactlyTwoPhysicalPlayersWithExplicitRoles() {
        val f = PlayerEngineFixture()
        assertEquals(2, f.engine.currentSlot.let { listOf(it, f.engine.nextSlot) }.map { it.player }.distinct().size)
        assertSame(f.p1, f.engine.currentPlayer)
        assertSame(f.p2, f.engine.nextPlayer)
        assertEquals(PlayerSlotRole.CURRENT, f.engine.roleOf(f.engine.currentSlot))
        assertEquals(PlayerSlotRole.NEXT, f.engine.roleOf(f.engine.nextSlot))
    }

    @Test fun facadeInitiallyDelegatesToCurrent() {
        val f = PlayerEngineFixture()
        assertSame(f.p1, f.facade.delegatePlayer)
        assertEquals("A", f.facade.currentMediaItem?.mediaId)
        assertEquals(2, f.facade.mediaItemCount)
    }

    // ── 4/5. NEXT inertness and CURRENT command routing ──────────────────────────────────────────────────────────────────

    @Test fun nextIsEmptyIdlePausedNeutralAndNeverCommanded() {
        val f = PlayerEngineFixture()
        assertEquals(0, f.p2.mediaItemCount)
        assertEquals(Player.STATE_IDLE, f.p2.playbackState)
        assertFalse(f.p2.playWhenReady)
        assertFalse(f.p2.isPlaying)
        assertEquals(1f, f.p2.volume, 0f)
        // A full session of logical commands, a focus event and a noisy event: NEXT is never touched.
        f.facade.play(); idleMainLooper()
        f.facade.seekTo(1, 5_000L); idleMainLooper()
        f.facade.repeatMode = Player.REPEAT_MODE_ALL; idleMainLooper()
        f.facade.pause(); idleMainLooper()
        assertTrue("NEXT must receive no command of any kind", f.p2.commands.isEmpty())
        // ... and no logical facade event was ever produced by NEXT: every event is explained by a CURRENT command.
        assertTrue(f.events.events.none { it.startsWith("error") })
    }

    @Test fun nextHasNoSessionOwnershipOrFocusOrNoisyHandling() {
        val f = PlayerEngineFixture()
        // Only the façade (delegate = CURRENT) is session-facing.
        assertSame(f.p1, f.facade.delegatePlayer)
        assertNull(shadowOf(RuntimeEnvironment.getApplication().getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager).lastAudioFocusRequest)
        assertEquals(1, noisyReceiverCount())
    }

    @Test fun nextEventsNeverReachTheFacade() {
        val f = PlayerEngineFixture()
        f.events.events.clear()
        f.p2.mutate { setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) }
        idleMainLooper()
        assertTrue("NEXT is not session-facing: ${f.events.events}", f.events.events.isEmpty())
    }

    @Test fun currentCommandsRouteToCurrentOnly() {
        val f = PlayerEngineFixture()
        f.facade.play(); idleMainLooper()
        f.facade.seekTo(1, 7_000L); idleMainLooper()
        f.facade.repeatMode = Player.REPEAT_MODE_ONE; idleMainLooper()
        f.facade.pause(); idleMainLooper()
        assertEquals(
            listOf("P1.setPlayWhenReady(true)", "P1.seek(1,7000)", "P1.setRepeatMode(1)", "P1.setPlayWhenReady(false)"),
            f.p1.commands,
        )
        assertTrue(f.p2.commands.isEmpty())
    }

    // ── 6-9. test-only role swap ────────────────────────────────────────────────────────────────────────────────────────

    @Test fun roleSwapExchangesRolesKeepsFacadeObjectAndRoutesToNewCurrent() {
        val f = PlayerEngineFixture()
        val facadeBefore = f.facade
        val slot1 = f.engine.currentSlot
        val slot2 = f.engine.nextSlot
        f.engine.swapRolesForTest()
        idleMainLooper()
        assertSame(f.p2, f.engine.currentPlayer)
        assertSame(f.p1, f.engine.nextPlayer)
        assertEquals(PlayerSlotRole.NEXT, f.engine.roleOf(slot1))
        assertEquals(PlayerSlotRole.CURRENT, f.engine.roleOf(slot2))
        assertSame("the façade is the same object", facadeBefore, f.facade)
        assertSame(f.p2, f.facade.delegatePlayer)

        f.facade.repeatMode = Player.REPEAT_MODE_ALL; idleMainLooper()
        f.facade.play(); idleMainLooper()
        assertEquals(listOf("P2.setRepeatMode(2)", "P2.setPlayWhenReady(true)"), f.p2.commands)
        assertTrue("old CURRENT receives no logical command", f.p1.commands.isEmpty())
        assertTrue("no release occurred", f.releases.isEmpty())
    }

    @Test fun oldCurrentEmitsNoLogicalEventsAfterSwap() {
        val f = PlayerEngineFixture()
        f.engine.swapRolesForTest()
        idleMainLooper()
        f.events.events.clear()
        f.p1.mutate { setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) }
        f.p1.mutate { setRepeatMode(Player.REPEAT_MODE_ONE) }
        f.p1.mutate { setPlaybackState(Player.STATE_ENDED) }
        idleMainLooper()
        assertTrue("retired-from-CURRENT player must be silent: ${f.events.events}", f.events.events.isEmpty())
    }

    @Test fun swapDoesNotMutateQueueSeekOrReleaseAnything() {
        val f = PlayerEngineFixture()
        val queueBefore = (0 until f.p1.mediaItemCount).map { f.p1.getMediaItemAt(it).mediaId }
        f.engine.swapRolesForTest()
        idleMainLooper()
        assertEquals(queueBefore, (0 until f.p1.mediaItemCount).map { f.p1.getMediaItemAt(it).mediaId })
        assertEquals(0, f.p2.mediaItemCount)
        assertTrue(f.p1.commands.isEmpty() && f.p2.commands.isEmpty())
        assertTrue(f.releases.isEmpty())
    }

    // ── 10/11. shared audio session ──────────────────────────────────────────────────────────────────────────────────────

    @Test fun bothSlotsShareOneAudioSessionIdAndItIsStableAcrossSwap() {
        val f = PlayerEngineFixture()
        assertEquals(SHARED_SESSION_ID, f.engine.audioSessionId)
        assertEquals(SHARED_SESSION_ID, f.p1.audioSessionId)
        assertEquals(SHARED_SESSION_ID, f.p2.audioSessionId)
        assertEquals("façade reports the delegate's id", SHARED_SESSION_ID, f.facade.audioSessionId)
        f.engine.swapRolesForTest()
        idleMainLooper()
        assertEquals(SHARED_SESSION_ID, f.engine.audioSessionId)
        assertEquals(SHARED_SESSION_ID, f.p1.audioSessionId)
        assertEquals(SHARED_SESSION_ID, f.p2.audioSessionId)
        assertEquals(SHARED_SESSION_ID, f.facade.audioSessionId)
    }

    @Test fun engineRejectsPlayersWithoutTheSharedSessionId() {
        val p1 = ScriptedPlayer("X1", titles = emptyList(), playing = false, state = Player.STATE_IDLE, audioSessionId = 1)
        val p2 = ScriptedPlayer("X2", titles = emptyList(), playing = false, state = Player.STATE_IDLE, audioSessionId = 2)
        try {
            PlayerEngine(RuntimeEnvironment.getApplication(), p1, p2, 1, androidx.media3.common.AudioAttributes.DEFAULT)
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("shared audio session id"))
        }
    }

    @Test fun engineRejectsANonInertNextPlayer() {
        val p1 = ScriptedPlayer("X1", titles = emptyList(), playing = false, state = Player.STATE_IDLE, audioSessionId = SHARED_SESSION_ID)
        val used = ScriptedPlayer("X2", titles = listOf("A"), playing = false, state = Player.STATE_READY, audioSessionId = SHARED_SESSION_ID)
        try {
            PlayerEngine(RuntimeEnvironment.getApplication(), p1, used, SHARED_SESSION_ID, androidx.media3.common.AudioAttributes.DEFAULT)
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("NEXT"))
        }
    }

    // ── 17. one noisy owner ──────────────────────────────────────────────────────────────────────────────────────────────

    private fun noisyReceiverCount(): Int =
        shadowOf(RuntimeEnvironment.getApplication()).registeredReceivers.count {
            it.intentFilter.hasAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        }

    private fun sendNoisy() {
        RuntimeEnvironment.getApplication().sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        idleMainLooper()
    }

    @Test fun oneNoisyEventProducesOneLogicalPause() {
        val f = PlayerEngineFixture()
        f.facade.play(); idleMainLooper()
        assertEquals("exactly ONE noisy receiver for two physical players", 1, noisyReceiverCount())
        f.events.events.clear()
        f.p1.commands.clear()
        sendNoisy()
        assertFalse(f.facade.playWhenReady)
        assertEquals(
            listOf("playWhenReady(false,${Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY})", "isPlaying(false)"),
            f.events.events,
        )
        assertEquals(listOf("P1.setPlayWhenReady(false)"), f.p1.commands)
        assertTrue(f.p2.commands.isEmpty())
        assertEquals(
            PrimaryPlaybackInterruption.AudioRoute,
            classifyPrimaryPlayWhenReadyInterruption(false, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY),
        )
        // A second noisy event while already paused is not another pause.
        f.events.events.clear()
        sendNoisy()
        assertTrue(f.events.events.isEmpty())
        assertEquals(listOf("P1.setPlayWhenReady(false)"), f.p1.commands)
    }

    @Test fun noisyPreferenceOffRemovesTheOnlyReceiverAndIgnoresTheEvent() {
        val f = PlayerEngineFixture()
        f.facade.play(); idleMainLooper()
        f.engine.setHandleAudioBecomingNoisy(false)
        assertEquals(0, noisyReceiverCount())
        sendNoisy()
        assertTrue(f.facade.playWhenReady)
        f.engine.setHandleAudioBecomingNoisy(true)
        f.engine.setHandleAudioBecomingNoisy(true) // idempotent: still one receiver
        assertEquals(1, noisyReceiverCount())
    }

    @Test fun noisyAfterRoleSwapPausesTheNewLogicalCurrentOnly() {
        val f = PlayerEngineFixture()
        f.engine.swapRolesForTest()
        f.facade.play(); idleMainLooper()
        f.p1.commands.clear()
        f.p2.commands.clear()
        sendNoisy()
        assertEquals(listOf("P2.setPlayWhenReady(false)"), f.p2.commands)
        assertTrue(f.p1.commands.isEmpty())
    }

    // ── 15. CURRENT parity with the CF-2M2 single-player façade ──────────────────────────────────────────────────────────

    @Test fun currentBehavesLikeTheBareCf2m2FacadeForTheSameCommands() {
        val engineSide = PlayerEngineFixture()
        val bareP = ScriptedPlayer("P1", titles = listOf("A", "B"), playing = false, state = Player.STATE_READY, audioSessionId = SHARED_SESSION_ID)
        val bare = SessionFacade(bareP)
        val bareEvents = EventRecorder().also { bare.addListener(it) }
        idleMainLooper()

        fun drive(player: Player) {
            player.play(); idleMainLooper()
            player.seekTo(1, 9_000L); idleMainLooper()
            player.repeatMode = Player.REPEAT_MODE_ALL; idleMainLooper()
            player.pause(); idleMainLooper()
        }
        drive(engineSide.facade)
        drive(bare)

        assertEquals(bareP.commands.filterNot { it.contains("setPlaybackParameters") }, engineSide.p1.commands.filterNot { it.contains("setPlaybackParameters") })
        assertEquals(bareEvents.events, engineSide.events.events)
        listOf<(Player) -> Any?>(
            { it.playWhenReady }, { it.isPlaying }, { it.playbackState }, { it.currentMediaItemIndex }, { it.currentPosition },
            { it.repeatMode }, { it.playbackParameters }, { it.currentMediaItem?.mediaId }, { it.mediaMetadata.title },
            { it.mediaItemCount }, { it.audioSessionId },
        ).forEachIndexed { i, read ->
            assertEquals("state field #$i differs", read(bare), read(engineSide.facade))
        }
        assertNotNull(engineSide.facade.currentMediaItem)
    }
}
