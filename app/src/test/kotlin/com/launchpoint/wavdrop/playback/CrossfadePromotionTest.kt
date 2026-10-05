package com.launchpoint.wavdrop.playback

import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * CF-2M5: the engine's production promotion primitive on scripted physical players (exact preconditions, B started once and
 * never sought, one AUTO event, role swap, preparation consumption), the retiring tail strip and event isolation, the one-writer
 * gain composer (fade x duck) and the injected-failure settlements.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class CrossfadePromotionTest {

    private fun rig(hook: ((PromotionStep) -> Unit)? = null) = PromotionRig(hook = hook).playAndPrepare()

    // ── preconditions: fail closed, nothing changes ─────────────────────────────────────────────────────────────────────

    private fun assertUntouched(r: PromotionRig) {
        assertSame(r.p1, r.engine.currentPlayer)
        assertSame(r.p1, r.f.facade.delegatePlayer)
        assertFalse(r.p2.playWhenReady)
        assertTrue(r.p2.commands.isEmpty())
        assertTrue(r.p1.commands.isEmpty())
        assertFalse(r.engine.promotionActive)
    }

    @Test fun onlyTheReadyExactKeyPromotes() {
        val r = rig()
        assertTrue(r.promote() is PromotionStartResult.Promoted)
    }

    @Test fun aStaleOrDifferentKeyCannotPromote() {
        val r = rig()
        for (stale in listOf(
            CrossfadeTransitionKey(6L, r.from, r.from + 1),
            CrossfadeTransitionKey(7L, r.from + 1, r.from + 2),
            CrossfadeTransitionKey(7L, r.from, r.from + 2),
        )) {
            assertEquals(PromotionStartResult.Rejected(PromotionRejection.KeyMismatch), r.engine.promoteReadyNext(stale))
        }
        assertUntouched(r)
        assertTrue("the preparation was not consumed by a rejected promotion", r.engine.nextPreparation.state is NextSlotState.Ready)
    }

    @Test fun noPreparationOrNotReadyPreparationCannotPromote() {
        val f = PlayerEngineFixture(p1Titles = listOf("A", "B", "C", "D"), p1Index = 1)
        f.facade.play(); idleMainLooper()
        val key = CrossfadeTransitionKey(7L, 1, 2)
        assertEquals(PromotionStartResult.Rejected(PromotionRejection.PreparationNotReady), f.engine.promoteReadyNext(key))
        f.engine.nextPreparation.request(NextSlotRequest(key, (listOf("A", "B", "C", "D")).map { ScriptedPlayer.mediaItem(it) }))
        assertEquals("Preparing is not Ready", PromotionStartResult.Rejected(PromotionRejection.PreparationNotReady), f.engine.promoteReadyNext(key))
        assertFalse(f.p2.playWhenReady)
    }

    @Test fun aWrongCurrentIndexCannotPromote() {
        val r = rig()
        r.p1.mutate { setCurrentMediaItemIndex(2) } // CURRENT moved on (e.g. a manual skip the hooks have not processed yet)
        assertEquals(PromotionStartResult.Rejected(PromotionRejection.CurrentIndexMismatch), r.engine.promoteReadyNext(r.key))
        assertFalse(r.p2.playWhenReady)
        assertSame(r.p1, r.f.facade.delegatePlayer)
    }

    @Test fun aWrongNextIndexCannotPromote() {
        val r = rig()
        r.p2.mutate { setCurrentMediaItemIndex(0) }
        assertEquals(PromotionStartResult.Rejected(PromotionRejection.NextIndexMismatch), r.engine.promoteReadyNext(r.key))
        assertFalse(r.p2.playWhenReady)
    }

    @Test fun aMirroredTimelineThatNoLongerMatchesCannotPromote() {
        val r = rig()
        r.p1.removeMediaItem(3) // CURRENT's queue changed without invalidating NEXT
        assertEquals(PromotionStartResult.Rejected(PromotionRejection.TimelineMismatch), r.engine.promoteReadyNext(r.key))
    }

    @Test fun aNextThatIsNotReadyCannotPromote() {
        val r = rig()
        r.p2.mutate { setPlaybackState(Player.STATE_BUFFERING) }
        assertEquals(PromotionStartResult.Rejected(PromotionRejection.NextNotReady), r.engine.promoteReadyNext(r.key))
        assertFalse(r.p2.playWhenReady)
    }

    @Test fun anAlreadyStartedNextCannotPromote() {
        val r = rig()
        r.p2.mutate { setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) }
        assertEquals(PromotionStartResult.Rejected(PromotionRejection.NextAlreadyStarted), r.engine.promoteReadyNext(r.key))
    }

    @Test fun aCurrentThatIsNotPlayingCannotPromote() {
        val r = rig()
        r.f.facade.pause(); idleMainLooper()
        assertEquals(PromotionStartResult.Rejected(PromotionRejection.CurrentNotAdvancing), r.engine.promoteReadyNext(r.key))
        r.p1.commands.clear()
        assertFalse(r.p2.playWhenReady)
    }

    @Test fun aReleasedEngineAndAnActiveOverlapCannotPromote() {
        val r = rig()
        assertTrue(r.promote() is PromotionStartResult.Promoted)
        assertEquals(PromotionStartResult.Rejected(PromotionRejection.OverlapActive), r.engine.promoteReadyNext(r.key))
        r.engine.release()
        assertEquals(PromotionStartResult.Rejected(PromotionRejection.Released), r.engine.promoteReadyNext(r.key))
    }

    // ── start B once, never seek/reset, role swap once, same façade, one AUTO event ──────────────────────────────────────

    @Test fun bIsStartedExactlyOnceAndNeverSoughtResetOrReprepared() {
        val r = rig()
        r.promote()
        assertEquals(listOf("P2.setVolume(0.0)", "P2.setPlayWhenReady(true)"), r.p2.commands.filterNot { it.contains("setVolume(1.0)") })
        assertEquals(1, r.p2.commands.count { it == "P2.setPlayWhenReady(true)" })
        assertFalse(r.p2.commands.any { it.contains("seek") || it.contains("prepare") || it.contains("setMediaItems") || it.contains("stop") || it.contains("clear") || it.contains("removeMediaItems") })
        assertEquals("B starts at gain 0 BEFORE playing", 0f, r.p2.volume, 0f)
        assertTrue(r.p2.commands.indexOf("P2.setVolume(0.0)") < r.p2.commands.indexOf("P2.setPlayWhenReady(true)"))
        assertEquals(r.from + 1, r.p2.currentMediaItemIndex)
        assertEquals(r.titles.size, r.p2.mediaItemCount)
        assertEquals(0L, r.p2.currentPosition)
    }

    @Test fun theRoleSwapHappensOnceTheFacadeObjectIsStableAndItsDelegateBecomesP2() {
        val r = rig()
        val facade = r.f.facade
        val slot1 = r.engine.currentSlot
        val slot2 = r.engine.nextSlot
        val result = r.promote() as PromotionStartResult.Promoted
        assertEquals(slot1.id, result.outgoingSlotId)
        assertEquals(slot2.id, result.incomingSlotId)
        assertSame(r.p2, r.engine.currentPlayer)
        assertSame(r.p1, r.engine.nextPlayer)
        assertEquals(PlayerSlotRole.CURRENT, r.engine.roleOf(slot2))
        assertEquals(PlayerSlotRole.NEXT, r.engine.roleOf(slot1))
        assertSame(facade, r.f.facade)
        assertSame(r.p2, facade.delegatePlayer)
        assertSame(slot1, r.engine.retiringSlot)
    }

    @Test fun promotionEmitsExactlyOneAutoTransitionAndOneAutoDiscontinuityWithNoFlicker() {
        val r = rig()
        r.promote()
        val e = r.f.events.events
        assertEquals("exactly one AUTO transition: $e", 1, e.count { it.startsWith("transition(") })
        assertTrue(e.contains("transition(C,AUTO)"))
        assertEquals("exactly one discontinuity: $e", 1, e.count { it.startsWith("discontinuity(") })
        assertTrue(e.single { it.startsWith("discontinuity(") }.endsWith("AUTO_TRANSITION)"))
        assertFalse("no PLAYLIST_CHANGED: $e", e.any { it.contains("PLAYLIST_CHANGED") })
        assertFalse("no pause/play edge: $e", e.any { it.startsWith("isPlaying") || it.startsWith("playWhenReady") || it.startsWith("state(") })
        assertTrue(r.f.facade.isPlaying)
        assertEquals("C", r.f.facade.currentMediaItem?.mediaId)
        assertEquals(r.from + 1, r.f.facade.currentMediaItemIndex)
        assertEquals(r.titles.size, r.f.facade.mediaItemCount)
    }

    @Test fun logicalCommandsFollowTheNewCurrentOnly() {
        val r = rig()
        r.promote()
        r.p1.commands.clear(); r.p2.commands.clear()
        r.f.facade.repeatMode = Player.REPEAT_MODE_ALL; idleMainLooper()
        r.f.facade.pause(); idleMainLooper()
        assertEquals(listOf("P2.setRepeatMode(2)", "P2.setPlayWhenReady(false)"), r.p2.commands)
        assertTrue("the retiring player receives no logical command", r.p1.commands.isEmpty())
    }

    @Test fun preparationIsConsumedWithoutClearingP2() {
        val r = rig()
        r.promote()
        assertEquals(NextSlotState.Idle, r.engine.nextPreparation.state)
        assertEquals(1, r.engine.nextPreparation.promotionsConsumed)
        assertEquals("P2 keeps its full prepared queue", r.titles, r.p2.mediaIds)
        assertEquals(0, r.engine.nextPreparation.invalidations)
        assertFalse(r.p2.commands.any { it.contains("clear") || it.contains("stop") })
        // the consumed preparation can no longer be consumed again or cancel anything
        assertEquals(null, r.engine.nextPreparation.consumeReadyForPromotion(r.key))
        r.engine.nextPreparation.invalidate(CrossfadeCancelReason.Seek)
        assertEquals(r.titles, r.p2.mediaIds)
    }

    @Test fun preparationIsRefusedWhileARetiringPlayerOccupiesNext() {
        val r = rig()
        r.promote()
        val state = r.engine.nextPreparation.request(NextSlotRequest(CrossfadeTransitionKey(7L, 2, 3), r.queue))
        assertEquals(NextSlotState.Idle, state)
        assertFalse(r.engine.nextPreparation.accepting)
        assertTrue("nothing was prepared on the retiring physical", r.p1.commands.none { it.contains("setMediaItems") || it.contains("prepare") })
    }

    // ── retiring A ──────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun theRetiringTailIsStrippedWithoutSeekingOrLogicalEvents() {
        val r = rig()
        r.promote()
        r.f.events.events.clear()
        r.p1.commands.clear()
        r.engine.stripRetiringTail()
        idleMainLooper()
        assertEquals("A stays current on P1 with only its own past + itself", listOf("A", "B"), r.p1.mediaIds)
        assertEquals(r.from, r.p1.currentMediaItemIndex)
        assertEquals(listOf("P1.removeMediaItems(2,4)"), r.p1.commands)
        assertFalse(r.p1.commands.any { it.contains("seek") })
        assertTrue("no logical event from the strip: ${r.f.events.events}", r.f.events.events.isEmpty())
        assertEquals("P2's mirrored queue is untouched", r.titles, r.p2.mediaIds)
        assertEquals(r.from + 1, r.p2.currentMediaItemIndex)
    }

    @Test fun repeatAllOnTheRetiringPlayerIsTurnedOffSoItCannotWrapIntoItsOwnB() {
        val r = rig()
        r.f.facade.repeatMode = Player.REPEAT_MODE_ALL; idleMainLooper()
        r.p1.mutate { }
        r.engine.nextPreparation.invalidate(null)
        r.engine.nextPreparation.request(NextSlotRequest(r.key, r.queue, Player.REPEAT_MODE_ALL))
        r.p2.becomeReady(); idleMainLooper()
        r.promote()
        r.engine.stripRetiringTail()
        assertEquals(Player.REPEAT_MODE_OFF, r.p1.repeatMode)
        assertEquals("the new CURRENT keeps the mirrored repeat mode", Player.REPEAT_MODE_ALL, r.p2.repeatMode)
    }

    @Test fun retiringStateChangesNeverReachLogicalListeners() {
        val r = rig()
        r.promote()
        r.engine.stripRetiringTail()
        r.f.events.events.clear()
        r.p1.mutate { setPlaybackState(Player.STATE_BUFFERING) }
        r.p1.mutate { setPlaybackState(Player.STATE_READY) }
        r.p1.mutate { setRepeatMode(Player.REPEAT_MODE_ONE) }
        r.p1.mutate { setPlaybackState(Player.STATE_ENDED) }
        idleMainLooper()
        assertTrue("a retiring player is silent: ${r.f.events.events}", r.f.events.events.isEmpty())
        assertTrue(r.f.facade.isPlaying)
        assertEquals("C", r.f.facade.currentMediaItem?.mediaId)
    }

    @Test fun finishingRetirementStopsClearsAndRecyclesP1WithoutReleasingIt() {
        val r = rig()
        r.promote(); r.engine.stripRetiringTail()
        assertTrue(r.engine.finishRetirement())
        assertEquals(0, r.p1.mediaItemCount)
        assertEquals(Player.STATE_IDLE, r.p1.playbackState)
        assertFalse(r.p1.playWhenReady)
        assertEquals(1f, r.p1.volume, 0f)
        assertEquals(Player.REPEAT_MODE_OFF, r.p1.repeatMode)
        assertTrue("P1 is NOT released", r.f.releases.isEmpty() && r.p1.commands.none { it.contains("release") })
        assertEquals(null, r.engine.retiringSlot)
        assertFalse(r.engine.promotionActive)
        assertSame(r.p1, r.engine.nextPlayer)
        assertTrue(r.engine.nextPreparation.accepting)
        assertEquals(1f, r.p2.volume, 0f)
        assertTrue(r.p2.playWhenReady)
    }

    // ── gain composition: fade x duck, one writer ───────────────────────────────────────────────────────────────────────

    private fun equalPower(p: Float) = CrossfadeGainCurve.equalPower(p)

    @Test fun progressZeroGivesAOneAndBZeroAndProgressOneGivesAZeroAndBOne() {
        val r = rig(); r.promote()
        val g0 = equalPower(0f); r.engine.setFadeGains(g0.outgoing, g0.incoming)
        assertEquals(1f, r.p1.volume, 1e-6f); assertEquals(0f, r.p2.volume, 1e-6f)
        val g1 = equalPower(1f); r.engine.setFadeGains(g1.outgoing, g1.incoming)
        assertEquals(0f, r.p1.volume, 1e-6f); assertEquals(1f, r.p2.volume, 1e-6f)
    }

    @Test fun midpointMatchesTheExistingEqualPowerCurve() {
        val r = rig(); r.promote()
        val g = equalPower(0.5f)
        r.engine.setFadeGains(g.outgoing, g.incoming)
        assertEquals(cos(0.25 * PI).toFloat(), r.p1.volume, 1e-6f)
        assertEquals(sin(0.25 * PI).toFloat(), r.p2.volume, 1e-6f)
    }

    private fun duck(r: PromotionRig, multiplier: Float) {
        val audio = org.robolectric.RuntimeEnvironment.getApplication().getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        val shadow = org.robolectric.Shadows.shadowOf(audio)
        val listener = shadow.lastAudioFocusRequest.listener
        listener.onAudioFocusChange(if (multiplier < 1f) android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK else android.media.AudioManager.AUDIOFOCUS_GAIN)
        idleMainLooper()
    }

    @Test fun duckOfOneProducesPureFadeGains() {
        val r = rig(); r.promote()
        val g = equalPower(0.3f)
        r.engine.setFadeGains(g.outgoing, g.incoming)
        assertEquals(g.outgoing, r.p1.volume, 1e-6f)
        assertEquals(g.incoming, r.p2.volume, 1e-6f)
    }

    @Test fun aDuckAttenuatesBothAudiblePlayersDuringTheOverlap() {
        val r = rig(); r.promote()
        val g = equalPower(0.3f)
        r.engine.setFadeGains(g.outgoing, g.incoming)
        duck(r, 0.2f)
        assertEquals(g.outgoing * 0.2f, r.p1.volume, 1e-6f)
        assertEquals(g.incoming * 0.2f, r.p2.volume, 1e-6f)
    }

    @Test fun changingTheDuckMidOverlapRecomputesBothWithoutResettingTheFadeAndFadeTicksDoNotOverwriteTheDuck() {
        val r = rig(); r.promote()
        val g1 = equalPower(0.3f); r.engine.setFadeGains(g1.outgoing, g1.incoming)
        duck(r, 0.2f)
        val g2 = equalPower(0.7f); r.engine.setFadeGains(g2.outgoing, g2.incoming) // a fade tick under an active duck
        assertEquals("fade tick keeps the duck", g2.outgoing * 0.2f, r.p1.volume, 1e-6f)
        assertEquals(g2.incoming * 0.2f, r.p2.volume, 1e-6f)
        duck(r, 1f) // focus regained: only the duck changes; the fade component is preserved
        assertEquals(g2.outgoing, r.p1.volume, 1e-6f)
        assertEquals(g2.incoming, r.p2.volume, 1e-6f)
    }

    @Test fun afterRetirementTheNewCurrentUsesDuckTimesOneAndTheRecycledNextIsNeutral() {
        val r = rig(); r.promote(); r.engine.stripRetiringTail()
        duck(r, 0.2f)
        r.engine.setFadeGains(0.4f, 0.6f)
        r.engine.finishRetirement()
        assertEquals(0.2f, r.p2.volume, 1e-6f)
        assertEquals("the recycled NEXT resets its fade component and is not ducked", 1f, r.p1.volume, 1e-6f)
        duck(r, 1f)
        assertEquals(1f, r.p2.volume, 1e-6f)
    }

    @Test fun invalidFadeGainsAreRefusedAndWriteNothing() {
        val r = rig(); r.promote()
        val before = r.p1.volume to r.p2.volume
        for (bad in listOf(Float.NaN, -0.1f, 1.5f, Float.POSITIVE_INFINITY)) {
            try { r.engine.setFadeGains(bad, 0.5f); org.junit.Assert.fail("expected rejection for $bad") } catch (_: IllegalArgumentException) { }
        }
        assertEquals(before, r.p1.volume to r.p2.volume)
    }

    // ── injected failures: one authoritative player, no dual playback, no extra logical transition ───────────────────────

    private fun assertOneAuthoritative(r: PromotionRig, survivor: ScriptedPlayer, other: ScriptedPlayer) {
        assertSame(survivor, r.engine.currentPlayer)
        assertSame(survivor, r.f.facade.delegatePlayer)
        assertTrue("the survivor plays", survivor.playWhenReady)
        assertEquals("the survivor is at full gain", 1f, survivor.volume, 1e-6f)
        assertFalse("the other player is not playing", other.playWhenReady)
        assertFalse("no uncontrolled dual playback", survivor.playWhenReady && other.playWhenReady)
        assertTrue("no B->A / B->B seek", r.p1.commands.none { it.contains("seek") } && r.p2.commands.none { it.contains("seek") })
        assertTrue("at most one logical transition", r.f.events.events.count { it.startsWith("transition(") } <= 1)
        assertFalse(r.engine.promotionActive)
    }

    @Test fun failureStartingBLeavesAAuthoritativeAndB() {
        val r = rig { if (it == PromotionStep.StartIncoming) error("injected") }
        val result = r.promote() as PromotionStartResult.Failed
        assertEquals(PromotionStep.StartIncoming, result.step)
        assertOneAuthoritative(r, r.p1, r.p2)
        assertEquals("B was emptied and recycled", 0, r.p2.mediaItemCount)
        assertEquals(1f, r.p2.volume, 1e-6f)
        assertEquals("A kept its queue", r.titles, r.p1.mediaIds)
    }

    @Test fun failureAtTheConsumeBoundaryLeavesAAuthoritative() {
        val r = rig { if (it == PromotionStep.ConsumePreparation) error("injected") }
        val result = r.promote() as PromotionStartResult.Failed
        assertEquals(PromotionStep.ConsumePreparation, result.step)
        assertOneAuthoritative(r, r.p1, r.p2)
        assertTrue(r.f.events.events.isEmpty())
    }

    @Test fun failureWritingTheIncomingZeroGainLeavesAAuthoritativeAndBNeverStarted() {
        val r = rig { if (it == PromotionStep.IncomingGainZero) error("injected") }
        val result = r.promote() as PromotionStartResult.Failed
        assertEquals(PromotionStep.IncomingGainZero, result.step)
        assertOneAuthoritative(r, r.p1, r.p2)
        assertFalse(r.p2.commands.any { it == "P2.setPlayWhenReady(true)" })
    }

    @Test fun failureAtRoleSwapRestoresAAsTheOnlyAuthoritativePlayer() {
        val r = rig { if (it == PromotionStep.SwapRoles) error("injected") }
        val result = r.promote() as PromotionStartResult.Failed
        assertEquals(r.engine.currentSlot.id, result.survivorSlotId)
        assertOneAuthoritative(r, r.p1, r.p2)
        assertEquals(0, r.p2.mediaItemCount)
    }

    @Test fun failureAtFacadeReplacementWithTheFacadeStillOnAKeepsAAndSilencesB() {
        val r = rig { if (it == PromotionStep.ReplaceDelegate) error("injected") }
        val result = r.promote() as PromotionStartResult.Failed
        assertEquals(PromotionStep.SwapRoles, result.step)
        assertOneAuthoritative(r, r.p1, r.p2)
        assertEquals(0, r.p2.mediaItemCount)
        assertTrue("no logical transition at all", r.f.events.events.none { it.startsWith("transition(") })
    }

    @Test fun failureStrippingTheTailIsHandledByTheOverlapOwnerWithBAuthoritative() {
        val r = rig { if (it == PromotionStep.StripTail) error("injected") }
        r.promote()
        try { r.engine.stripRetiringTail(); org.junit.Assert.fail() } catch (_: IllegalStateException) { }
        // the owner's fail-closed response: cut A, B continues alone
        assertTrue(r.engine.finishRetirement())
        assertOneAuthoritative(r, r.p2, r.p1)
        assertEquals(1, r.f.events.events.count { it.startsWith("transition(") })
    }

    @Test fun failureWritingAFadeGainCanBeSettledWithBAuthoritative() {
        val r = rig { if (it == PromotionStep.FadeGainWrite) error("injected") }
        r.promote()
        try { r.engine.setFadeGains(0.5f, 0.5f); org.junit.Assert.fail() } catch (_: IllegalStateException) { }
        assertTrue(r.engine.finishRetirement())
        assertOneAuthoritative(r, r.p2, r.p1)
    }

    @Test fun failureClearingTheRetiringPlayerQuarantinesItSilentlyAndBlocksReuse() {
        val r = rig { if (it == PromotionStep.RetireClear) error("injected") }
        r.promote()
        assertFalse(r.engine.finishRetirement())
        assertEquals("the failed retiring player is silent", 0f, r.p1.volume, 0f)
        assertSame(r.p2, r.engine.currentPlayer)
        assertEquals(1f, r.p2.volume, 1e-6f)
        assertTrue("quarantined: no further promotion or preparation", r.engine.promotionActive && !r.engine.nextPreparation.accepting)
        assertEquals(1, r.f.events.events.count { it.startsWith("transition(") })
    }

    @Test fun promotionNeverPromotesTwiceForOneRequestedKey() {
        val r = rig()
        assertTrue(r.promote() is PromotionStartResult.Promoted)
        assertNotEquals(PromotionStartResult.Promoted(0, 1), r.engine.promoteReadyNext(r.key))
        assertEquals(1, r.p2.commands.count { it == "P2.setPlayWhenReady(true)" })
        assertEquals(1, r.f.events.events.count { it.startsWith("transition(") })
    }
}
