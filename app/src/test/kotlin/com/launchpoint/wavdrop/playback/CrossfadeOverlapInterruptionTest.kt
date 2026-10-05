package com.launchpoint.wavdrop.playback

import android.content.Intent
import android.media.AudioManager
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * CF-2M6: the overlap interaction policy on the REAL engine, façade, focus owner and promotion runtime over scripted physicals.
 * After a promotion B is already the logical CURRENT, so every interruption must CUT the retiring A, settle to B only, and then
 * let the requested logical action act on B (never rewind to A, never seek a second B). A focus duck is the one non-interruption.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class CrossfadeOverlapInterruptionTest {

    private fun rig() = OverlapRig().inOverlap()

    private fun pureSettlementEventsOnly(r: OverlapRig) {
        assertTrue("settlement emitted a logical transition: ${r.events}", r.events.none { it.startsWith("transition(") })
        assertTrue("settlement emitted a discontinuity: ${r.events}", r.events.none { it.startsWith("discontinuity(") })
    }

    // ── the settlement seam itself ───────────────────────────────────────────────────────────────────────────────────────

    @Test fun settlementAloneIsPhysicalCleanupOnlyAndEmitsNoLogicalEventAndTouchesBNotAtAll() {
        val r = rig()
        r.runtime.settleOverlap(PromotionInterruption.Other)
        r.assertSettledToB(PromotionInterruption.Other)
        assertTrue("no logical event at all: ${r.events}", r.events.isEmpty())
        assertTrue("B received no command: ${r.bLogical()}", r.bLogical().isEmpty())
        assertTrue(r.p2.playWhenReady)
        assertEquals(1, r.p2.volume.toInt())
    }

    @Test fun settlementIsIdempotentAndHarmlessWithNoOverlap() {
        val r = rig()
        r.runtime.settleOverlap(PromotionInterruption.Pause)
        val first = r.runtime.lastOutcome
        val commands = r.p1.commands.toList()
        r.runtime.settleOverlap(PromotionInterruption.Seek)
        r.runtime.cancel(CrossfadeCancelReason.ManualNavigation)
        assertEquals("a second settlement changes nothing", first, r.runtime.lastOutcome)
        assertEquals(commands, r.p1.commands)
        val idle = OverlapRig()
        idle.runtime.settleOverlap(PromotionInterruption.Pause)
        assertEquals(null, idle.runtime.lastOutcome)
        assertTrue(idle.engine.nextPreparation.state is NextSlotState.Ready)
    }

    @Test fun everySharedCancelReasonMapsTotallyAndTheMatrixReasonsMapExplicitly() {
        CrossfadeCancelReason.values().forEach { assertNotNull(PromotionInterruption.from(it)) }
        val expected = mapOf(
            CrossfadeCancelReason.Pause to PromotionInterruption.Pause,
            CrossfadeCancelReason.Seek to PromotionInterruption.Seek,
            CrossfadeCancelReason.ManualNavigation to PromotionInterruption.Navigation,
            CrossfadeCancelReason.RepeatChanged to PromotionInterruption.RepeatChanged,
            CrossfadeCancelReason.ShuffleChanged to PromotionInterruption.ShuffleChanged,
            CrossfadeCancelReason.QueueMutation to PromotionInterruption.QueueMutated,
            CrossfadeCancelReason.PlaybackError to PromotionInterruption.CurrentError,
            CrossfadeCancelReason.PrimaryPlaybackTerminated to PromotionInterruption.CurrentTerminal,
            CrossfadeCancelReason.ConfigurationDisabled to PromotionInterruption.ConfigurationDisabled,
            CrossfadeCancelReason.PlanInvalidated to PromotionInterruption.PlanInvalidated,
            CrossfadeCancelReason.ControllerDisconnected to PromotionInterruption.ControllerDisconnected,
            CrossfadeCancelReason.ServiceStopping to PromotionInterruption.Teardown,
        )
        expected.forEach { (reason, interruption) -> assertEquals(reason.name, interruption, PromotionInterruption.from(reason)) }
    }

    @Test fun debugDiagnosticsCarryTheSettleReasonKeyAndSlotIdsOnly() {
        val r = rig()
        r.facade.pause(); idleMainLooper()
        val line = r.logs.single { it.startsWith("OVERLAP_SETTLE") }
        assertTrue(line, line.contains("reason=PAUSE") && line.contains("gen=7 from=1 to=2") && line.contains("current=1 retiring=0"))
        assertFalse(r.logs.joinToString().contains("content://"))
        listOf(
            PromotionInterruption.Seek to "SEEK", PromotionInterruption.TransientFocusLoss to "FOCUS_LOSS_TRANSIENT",
            PromotionInterruption.RetiringError to "RETIRING_ERROR", PromotionInterruption.CurrentError to "CURRENT_ERROR",
        ).forEach { (i, label) -> assertEquals(label, i.label) }
    }

    // ── explicit pause ───────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun pauseCutsAFirstThenPausesBOnlyAndResumeResumesBOnly() {
        val r = rig()
        r.facade.pause(); idleMainLooper()
        r.assertSettledToB(PromotionInterruption.Pause)
        r.assertACutBeforeBCommand("P2.setPlayWhenReady(false)")
        assertEquals("exactly one pause reaches B", 1, r.p2.commands.count { it == "P2.setPlayWhenReady(false)" })
        assertFalse(r.p2.playWhenReady)
        assertEquals("B stays at its current index and is never sought", 2, r.p2.currentMediaItemIndex)
        pureSettlementEventsOnly(r)
        assertEquals("only the normal pause event", 1, r.events.count { it.startsWith("playWhenReady(false") })
        val aCommands = r.p1.commands.toList()
        r.facade.play(); idleMainLooper()
        assertTrue(r.p2.playWhenReady)
        assertEquals("resume reaches B only", 1, r.p2.commands.count { it == "P2.setPlayWhenReady(true)" })
        assertEquals("A is never touched again", aCommands, r.p1.commands)
        assertTrue("no second promotion", r.runtime.state == PromotionOverlapState.Idle)
        assertEquals(0, r.p1.mediaItemCount)
        assertTrue(r.p2.commands.none { it.contains(".seek(") })
    }

    @Test fun anExplicitPauseHookAndThePhysicalPauseSettleOnceInThatOrder() {
        val r = rig()
        var hookSettledBeforePause = false
        r.runtime.cancel(CrossfadeCancelReason.Pause) // the PreviousBehaviorPlayer hook runs first (verified by source guard)
        hookSettledBeforePause = r.runtime.state == PromotionOverlapState.Idle && r.p2.playWhenReady
        assertTrue("A is cut while B still plays, BEFORE the pause is forwarded", hookSettledBeforePause)
        r.facade.pause(); idleMainLooper()
        r.assertSettledToB(PromotionInterruption.Pause)
        assertEquals(1, r.p2.commands.count { it == "P2.setPlayWhenReady(false)" })
    }

    // ── explicit seek ────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun seekCutsAThenSeeksBOnlyWithNoSecondBAndNoASeek() {
        val r = rig()
        r.facade.seekTo(30_000L); idleMainLooper()
        r.assertSettledToB(PromotionInterruption.Seek)
        r.assertACutBeforeBCommand("P2.seek(")
        assertEquals(listOf("P2.seek(2,30000)"), r.p2.commands.filter { it.contains(".seek(") })
        assertTrue(r.p1.commands.none { it.contains("seek") })
        assertEquals("only the normal seek discontinuity", 1, r.events.count { it.startsWith("discontinuity(") && it.endsWith("SEEK)") })
        assertTrue(r.events.none { it.startsWith("transition(") })
        assertTrue(r.p2.playWhenReady)
    }

    @Test fun theExplicitSeekHookSettlesBeforeTheSeekAndTheBoundaryDoesNotDoubleIt() {
        val r = rig()
        recoverCrossfadeFromExplicitSeek(r.sink)
        assertEquals(PromotionOverlapState.Idle, r.runtime.state)
        assertTrue("nothing has reached B yet", r.bLogical().isEmpty())
        val outcome = r.runtime.lastOutcome
        r.facade.seekTo(5_000L); idleMainLooper()
        assertEquals("one settlement", outcome, r.runtime.lastOutcome)
        assertEquals(1, r.p2.commands.count { it.contains(".seek(") })
    }

    // ── next / previous ──────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun nextCutsAThenAdvancesFromBAndAReceivesNoNavigation() {
        val r = rig()
        r.facade.seekToNext(); idleMainLooper()
        r.assertSettledToB(PromotionInterruption.Navigation)
        r.assertACutBeforeBCommand("P2.seek(3")
        assertEquals("the next item after B (index 2) is index 3", 3, r.p2.currentMediaItemIndex)
        assertEquals(1, r.p2.commands.count { it.startsWith("P2.seek(") })
        assertEquals(1, r.events.count { it.startsWith("transition(") })
        assertTrue(r.events.none { it.contains("PLAYLIST_CHANGED") })
    }

    private fun previousPolicy(r: OverlapRig) = PreviousBehaviorPlayer(
        player = r.facade, thresholdProvider = { 3_000L }, scope = CoroutineScope(Dispatchers.Unconfined),
        onExternalTransport = {}, hydrateForPlay = { PlayerHydrationResult.Hydrated }, songsProvider = { emptyList() },
        logResume = {}, sessionProvider = { null }, onExplicitPause = {}, onExplicitSeek = {}, onExplicitNavigation = {}, onExplicitRepeatChange = {},
    )

    @Test fun previousBeyondTheThresholdRestartsBFromZeroAfterCuttingAAndNeverRestoresA() {
        val r = rig()
        r.p2.mutate { setContentPositionMs(10_000L) }; idleMainLooper()
        previousPolicy(r).seekToPrevious(); idleMainLooper()
        r.assertSettledToB()
        r.assertACutBeforeBCommand("P2.seek(2,0)")
        assertEquals("B restarts at its own index", 2, r.p2.currentMediaItemIndex)
        assertEquals(1, r.p2.commands.count { it.startsWith("P2.seek(") })
        assertTrue(r.p1.commands.none { it.contains("seek") })
    }

    @Test fun previousWithinTheThresholdResolvesTheNormalPreviousOccurrenceOnB() {
        val r = rig()
        previousPolicy(r).seekToPrevious(); idleMainLooper()
        r.assertSettledToB(PromotionInterruption.Navigation)
        r.assertACutBeforeBCommand("P2.seek(1")
        assertEquals(1, r.p2.currentMediaItemIndex)
        assertTrue(r.p1.commands.none { it.contains("seek") })
        assertSame(r.p2, r.engine.currentPlayer)
    }

    // ── repeat / shuffle ─────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun repeatChangeCutsAThenChangesOnlyBAndEmitsNoTransition() {
        val r = rig()
        r.facade.repeatMode = Player.REPEAT_MODE_ONE; idleMainLooper()
        r.assertSettledToB(PromotionInterruption.RepeatChanged)
        r.assertACutBeforeBCommand("P2.setRepeatMode(1)")
        assertEquals(Player.REPEAT_MODE_ONE, r.p2.repeatMode)
        assertEquals("the recycled NEXT stays repeat OFF/inert", Player.REPEAT_MODE_OFF, r.p1.repeatMode)
        pureSettlementEventsOnly(r)
    }

    @Test fun shuffleChangeCutsAThenMutatesLogicalStateAndPreparedWorkIsGone() {
        val r = rig()
        recoverCrossfadeFromShuffleChange(r.sink)
        assertEquals(PromotionInterruption.ShuffleChanged, r.interrupted()?.interruption)
        r.facade.shuffleModeEnabled = true; idleMainLooper()
        r.assertSettledToB(PromotionInterruption.ShuffleChanged)
        assertEquals(NextSlotState.Idle, r.engine.nextPreparation.state)
        assertEquals(1, r.p2.commands.count { it.startsWith("P2.setShuffle") })
        pureSettlementEventsOnly(r)
    }

    // ── queue mutation families (hook layer AND façade-only boundary) ────────────────────────────────────────────────────

    private class Family(val name: String, val hook: (CrossfadeCancelSink?) -> Unit, val act: (OverlapRig) -> Unit, val bCommand: String, val queueLoad: Boolean = false)

    private val families = listOf(
        Family("playNext", ::recoverCrossfadeFromPlayNextMutation, { it.facade.addMediaItem(3, it.mediaItem("X")) }, "P2.addMediaItems(3,1)"),
        Family("addToQueue", ::recoverCrossfadeFromAddToQueueMutation, { it.facade.addMediaItem(it.mediaItem("X")) }, "P2.addMediaItems(6,1)"),
        Family("reorder", ::recoverCrossfadeFromQueueReorder, { it.facade.moveMediaItem(4, 3) }, "P2.moveMediaItems(4,5,3)"),
        Family("remove", ::recoverCrossfadeFromQueueRemoval, { it.facade.removeMediaItem(5) }, "P2.removeMediaItems(5,6)"),
        Family("replacement", ::recoverCrossfadeFromQueueReplacement, { it.facade.setMediaItems(listOf(it.mediaItem("X"), it.mediaItem("Y")), 0, 0L) }, "P2.setMediaItems(2)", queueLoad = true),
        Family("libraryDeletion", ::recoverCrossfadeFromLibraryDeletion, { it.facade.removeMediaItem(4) }, "P2.removeMediaItems(4,5)"),
        Family("adoptedResumption", ::recoverCrossfadeFromPlaybackResumption, { it.facade.setMediaItems(listOf(it.mediaItem("X")), 0, 0L) }, "P2.setMediaItems(1)", queueLoad = true),
    )

    @Test fun everyQueueMutationFamilyCutsAFirstThenAppliesToBExactlyOnce() {
        for (f in families) {
            val r = rig()
            f.hook(r.sink)
            assertEquals(f.name, PromotionInterruption.QueueMutated, r.interrupted()?.interruption)
            assertTrue("${f.name}: the hook alone must not have touched B", r.bLogical().isEmpty())
            val outcome = r.runtime.lastOutcome
            f.act(r); idleMainLooper()
            assertEquals("${f.name}: still ONE settlement", outcome, r.runtime.lastOutcome)
            assertEquals("${f.name}: applies once to B", 1, r.p2.commands.count { it.startsWith(f.bCommand) })
            r.assertSettledToB(PromotionInterruption.QueueMutated, queueMutated = true)
        }
    }

    @Test fun theFacadeBoundaryAloneAlsoCutsAFirstForEveryQueueMutationWithoutAnyHook() {
        for (f in families) {
            val r = rig()
            f.act(r); idleMainLooper()
            r.assertACutBeforeBCommand(f.bCommand)
            r.assertSettledToB(PromotionInterruption.QueueMutated, queueMutated = true)
            assertEquals("${f.name}: applies once to B", 1, r.p2.commands.count { it.startsWith(f.bCommand) })
        }
    }

    // ── focus ────────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun aDuckDoesNotSettleTheOverlapAndAttenuatesBothThroughTheComposerAndFadeContinuesMonotonically() {
        val r = rig() // progress 0.5 applied
        val g = CrossfadeGainCurve.equalPower(0.5f)
        r.duck(0.2f)
        assertTrue("a duck is not an interruption", r.runtime.state is PromotionOverlapState.Overlap)
        assertSame(r.p2, r.engine.currentPlayer)
        assertEquals(g.outgoing * 0.2f, r.p1.volume, 1e-6f)
        assertEquals(g.incoming * 0.2f, r.p2.volume, 1e-6f)
        r.now += 1_000L; r.runtime.tick() // progress 4/6 under the duck
        val g2 = CrossfadeGainCurve.equalPower(4f / 6f)
        assertEquals("a fade tick preserves the duck", g2.outgoing * 0.2f, r.p1.volume, 1e-6f)
        assertEquals(g2.incoming * 0.2f, r.p2.volume, 1e-6f)
        assertTrue("fade progress is monotonic", g2.incoming >= g.incoming && g2.outgoing <= g.outgoing)
        r.duck(1f)
        assertEquals("unduck restores the fade-relative volumes", g2.outgoing, r.p1.volume, 1e-6f)
        assertEquals(g2.incoming, r.p2.volume, 1e-6f)
        r.now += 10_000L; r.runtime.tick()
        assertEquals(PromotionOutcome.Completed(r.key), r.runtime.lastOutcome)
        assertEquals(1f, r.p2.volume, 0f)
    }

    @Test fun settlingUnderADuckLeavesBAtDuckTimesOneAndAFullyCut() {
        val r = rig()
        r.duck(0.2f)
        r.facade.pause(); idleMainLooper()
        r.assertSettledToB(PromotionInterruption.Pause, bVolume = 0.2f)
        r.duck(1f)
        assertEquals(1f, r.p2.volume, 1e-6f)
    }

    @Test fun transientFocusLossSettlesTheOverlapSuppressesBAndRegainResumesBOnly() {
        val r = rig()
        r.focus(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        r.assertSettledToB(PromotionInterruption.TransientFocusLoss)
        assertEquals(Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS, r.facade.playbackSuppressionReason)
        assertFalse("B is suppressed", r.p2.playWhenReady)
        assertTrue("the logical play intent survives a transient loss", r.facade.playWhenReady)
        val aCommands = r.p1.commands.toList()
        r.now += 20_000L
        r.scheduler.activeNow.forEach { assertEquals(CrossfadeCadence.PRE_FADE_POLL_INTERVAL_MS, it.delayMs) } // no fade tick survives
        r.focus(AudioManager.AUDIOFOCUS_GAIN)
        assertTrue("focus regain resumes B", r.p2.playWhenReady)
        assertEquals(Player.PLAYBACK_SUPPRESSION_REASON_NONE, r.facade.playbackSuppressionReason)
        assertEquals("A is never resumed", aCommands, r.p1.commands)
        assertEquals(0, r.p1.mediaItemCount)
        assertSame(r.p2, r.engine.currentPlayer)
    }

    @Test fun permanentFocusLossSettlesTheOverlapAndLeavesBCurrentButNotPlaying() {
        val r = rig()
        r.focus(AudioManager.AUDIOFOCUS_LOSS)
        r.assertSettledToB(PromotionInterruption.PermanentFocusLoss)
        assertFalse(r.p2.playWhenReady)
        assertFalse(r.facade.playWhenReady)
        assertEquals("playWhenReady(false,${Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS})", r.events.single { it.startsWith("playWhenReady(false") })
        assertSame(r.p2, r.engine.currentPlayer)
        pureSettlementEventsOnly(r)
    }

    // ── noisy / route ────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun audioBecomingNoisySettlesTheOverlapAndProducesExactlyOneLogicalPause() {
        val r = rig()
        RuntimeEnvironment.getApplication().sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY)); idleMainLooper()
        r.assertSettledToB(PromotionInterruption.AudioBecomingNoisy)
        r.assertACutBeforeBCommand("P2.setPlayWhenReady(false)")
        assertEquals(1, r.p2.commands.count { it == "P2.setPlayWhenReady(false)" })
        assertEquals(1, r.events.count { it.startsWith("playWhenReady(false,${Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY})") })
        // a duplicate disconnect broadcast (route callbacks may repeat) is harmless
        RuntimeEnvironment.getApplication().sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY)); idleMainLooper()
        assertEquals(1, r.p2.commands.count { it == "P2.setPlayWhenReady(false)" })
        assertEquals(1, r.events.count { it.startsWith("playWhenReady(false") })
        assertEquals(0, r.p1.mediaItemCount)
        assertSame(r.p2, r.engine.currentPlayer)
    }

    // ── errors ───────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun aCurrentBErrorCutsAReachesTheLogicalErrorPathOnceAndNeverFallsBackToA() {
        val r = rig()
        r.p2.failWith(); idleMainLooper()
        assertEquals(PromotionOverlapState.Idle, r.runtime.state)
        val outcome = r.interrupted()!!
        assertTrue(outcome.interruption.toString(), outcome.interruption == PromotionInterruption.CurrentError || outcome.interruption == PromotionInterruption.CurrentTerminal)
        assertEquals("the logical player error is surfaced exactly once to recovery", 1, r.events.count { it.startsWith("error(") })
        assertSame("B stays the only authority; no hidden rollback", r.p2, r.engine.currentPlayer)
        assertSame(r.p2, r.facade.delegatePlayer)
        assertEquals(0, r.p1.mediaItemCount)
        assertTrue("A was never started or seeked as a fallback: ${r.p1.commands}", r.p1.commands.none { it == "P1.setPlayWhenReady(true)" || it.contains("seek") || it.contains("prepare") })
        assertTrue(r.events.none { it.startsWith("transition(") })
        // a second signal of the same death (error then IDLE) is a no-op
        r.runtime.cancel(CrossfadeCancelReason.PlaybackError)
        assertEquals(outcome, r.runtime.lastOutcome)
    }

    @Test fun aRetiringAErrorIsInvisibleLogicallyAndSettlesTheOverlapWithBPlaying() {
        val r = rig()
        r.p1.failWith(); idleMainLooper()
        r.assertSettledToB(PromotionInterruption.RetiringError)
        assertTrue("no session error, no transition, no timeline: ${r.events}", r.events.isEmpty())
        assertTrue("B was not touched", r.bLogical().isEmpty())
        assertTrue(r.p2.playWhenReady)
        assertEquals(1f, r.p2.volume, 0f)
    }

    @Test fun aRetiringAEndingEarlySettlesImmediatelyWithNoTransitionAndNoError() {
        val r = rig()
        r.p1.mutate { setPlaybackState(Player.STATE_ENDED) }; idleMainLooper()
        r.assertSettledToB(PromotionInterruption.RetiringEnded)
        assertTrue(r.events.isEmpty())
        assertTrue(r.bLogical().isEmpty())
        assertTrue(r.p2.playWhenReady)
    }

    @Test fun aLateEventOfTheOldRetiringEpisodeIsInertAfterTheRecycle() {
        val r = rig()
        r.p1.failWith(); idleMainLooper()
        val outcome = r.runtime.lastOutcome
        r.p1.failWith(); idleMainLooper() // the recycled player later fails (e.g. during a new preparation)
        r.p1.failWith(); idleMainLooper()
        assertEquals(outcome, r.runtime.lastOutcome)
        assertSame(r.p2, r.engine.currentPlayer)
        assertTrue(r.bLogical().isEmpty())
        assertTrue(r.events.isEmpty())
    }

    @Test fun aQuarantinedRetiringPlayerIsNeverReusedAndBKeepsPlayingWithCrossfadeFailingClosed() {
        val r = OverlapRig(hook = { if (it == PromotionStep.RetireClear) error("injected cleanup failure") }).inOverlap()
        r.facade.pause(); idleMainLooper()
        assertEquals(PromotionOutcome.Interrupted(r.key, PromotionInterruption.Pause, retiringRecycled = false), r.runtime.lastOutcome)
        assertSame(r.p2, r.engine.currentPlayer)
        assertEquals("A stays silent", 0f, r.p1.volume, 0f)
        assertTrue(r.engine.promotionActive)
        assertFalse(r.engine.nextPreparation.accepting)
        assertEquals(PromotionStartResult.Rejected(PromotionRejection.OverlapActive), r.engine.promoteReadyNext(r.key))
        r.engine.nextPreparation.request(NextSlotRequest(CrossfadeTransitionKey(7L, 2, 3), (0 until 6).map { r.mediaItem("T$it") }))
        assertEquals("quarantined A is not prepared into", NextSlotState.Idle, r.engine.nextPreparation.state)
        assertFalse("B is playable and unaffected", r.p2.playWhenReady)
        r.facade.play(); idleMainLooper()
        assertTrue(r.p2.playWhenReady)
    }

    // ── config / lifecycle ───────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun crossfadeOffDuringTheOverlapSettlesToBAndStopsPolling() {
        val r = rig(); r.runtime.start()
        assertEquals(CrossfadeActivationDecision.Disable, applyPromotionConfiguredDurationChange(6_000L, 0L, r.runtime))
        r.assertSettledToB(PromotionInterruption.ConfigurationDisabled)
        assertTrue(r.scheduler.activeNow.isEmpty())
        assertTrue(r.p2.playWhenReady)
        assertTrue("no logical event", r.events.isEmpty())
    }

    @Test fun anEnabledToEnabledDurationChangeNeverRetimesTheActiveOverlap() {
        val r = OverlapRig().inOverlap(elapsedMs = 0L)
        val before = r.runtime.state as PromotionOverlapState.Overlap
        assertEquals(CrossfadeActivationDecision.UpdateOnly, applyPromotionConfiguredDurationChange(6_000L, 9_000L, r.runtime))
        r.configured = 9_000L
        assertEquals(before, r.runtime.state)
        r.now += 3_000L; r.runtime.tick()
        val half = CrossfadeGainCurve.equalPower(0.5f) // 3,000 of the CAPTURED 6,000 ms, not of 9,000
        assertEquals(half.outgoing, r.p1.volume, 1e-5f)
        assertEquals(half.incoming, r.p2.volume, 1e-5f)
    }

    @Test fun equalizerEnableDuringTheOverlapSettlesToBAndNeverRepromotesWhileBlocked() {
        val r = rig()
        recoverCrossfadeFromEqualizerEnabled(r.sink)
        r.eq = true
        r.assertSettledToB(PromotionInterruption.PlanInvalidated)
        r.runtime.evaluate()
        assertEquals(PromotionOverlapState.Idle, r.runtime.state)
        val driver = NextSlotPreparationDriver(
            preparation = r.engine.nextPreparation,
            snapshotProvider = {
                CrossfadeRuntimeSnapshot(7L, r.songs, 2, RepeatMode.OFF, false, true, false, false, true, equalizerEnabled = true)
            },
            materialize = { emptyList() }, configuredDurationMsProvider = { 6_000L }, currentDurationMsProvider = { 180_000L }, scheduler = OverlapScheduler(),
        )
        driver.evaluate()
        assertEquals("existing eligibility still blocks future preparation", NextSlotState.Idle, r.engine.nextPreparation.state)
    }

    @Test fun controllerDisconnectSettlesTheOverlapWithoutReleasingEitherPlayer() {
        val r = rig()
        recoverCrossfadeFromControllerDisconnected(r.sink)
        r.assertSettledToB(PromotionInterruption.ControllerDisconnected)
        assertFalse(r.engine.released)
        assertTrue(r.base.f.releases.isEmpty())
        assertTrue(r.p2.playWhenReady)
        assertTrue(r.events.isEmpty())
    }

    @Test fun serviceCloseDuringTheOverlapCancelsEverythingAndReleaseIsIdempotent() {
        val r = OverlapRig()
        r.position(173_500L)
        r.runtime.start()
        r.scheduler.runNext() // promote through the real pulse
        assertTrue(r.runtime.state is PromotionOverlapState.Overlap)
        val stale = r.scheduler.activeNow.single()
        r.runtime.close()
        assertEquals(PromotionOverlapState.Idle, r.runtime.state)
        assertTrue(r.scheduler.activeNow.isEmpty())
        r.engine.release()
        r.engine.release()
        r.runtime.close()
        stale.block() // a stale tick that survived must be inert, not throw or resurrect the overlap
        assertEquals(PromotionOverlapState.Idle, r.runtime.state)
        assertEquals("each physical released exactly once", listOf("P1", "P2").sorted(), r.base.f.releases.sorted())
        assertEquals(1, r.engine.focusReleaseCount)
        assertTrue(r.scheduler.activeNow.isEmpty())
        r.facade.pause() // a late command after teardown must not throw through the boundary
    }

    @Test fun engineReleaseWithoutTheRuntimeClosingFirstDoesNotThrowOrLeaveAnActiveSink() {
        val r = rig()
        r.engine.release()
        assertTrue(r.engine.released)
        r.runtime.settleOverlap(PromotionInterruption.Teardown) // safe even after release
        r.runtime.close()
    }

    // ── preparation can restart after a healthy settlement ───────────────────────────────────────────────────────────────

    private fun driverFor(r: OverlapRig): NextSlotPreparationDriver {
        val scheduler = OverlapScheduler()
        return NextSlotPreparationDriver(
            preparation = r.engine.nextPreparation,
            snapshotProvider = {
                CrossfadeRuntimeSnapshot(7L, r.songs, r.currentIndex, RepeatMode.OFF, false, true, false, false, true)
            },
            materialize = { songs -> songs.map { MediaItem.Builder().setMediaId(r.titles[(it.id - 1).toInt()]).build() } },
            configuredDurationMsProvider = { 6_000L },
            currentDurationMsProvider = { 180_000L },
            scheduler = scheduler,
        )
    }

    private fun assertPreparedOnTheRecycledPlayer(r: OverlapRig, target: Int) {
        assertEquals("settlement does not recurse into preparation", NextSlotState.Idle, r.engine.nextPreparation.state)
        assertTrue(r.p1.commands.none { it.contains("setMediaItems") })
        driverFor(r).evaluate()
        assertTrue(r.engine.nextPreparation.state.toString(), r.engine.nextPreparation.state is NextSlotState.PreparingTarget)
        r.p1.becomeReady(); idleMainLooper()
        assertTrue(r.engine.nextPreparation.state is NextSlotState.Ready)
        assertEquals(target, r.p1.currentMediaItemIndex)
        assertEquals(r.titles, r.p1.mediaIds)
        assertFalse(r.p1.playWhenReady)
        assertSame(r.p2, r.engine.currentPlayer)
    }

    @Test fun afterPauseAndResumeALaterTransitionIsPreparedOnTheRecycledPlayer() {
        val r = rig()
        r.facade.pause(); idleMainLooper(); r.facade.play(); idleMainLooper()
        r.currentIndex = 2
        assertPreparedOnTheRecycledPlayer(r, 3)
    }

    @Test fun afterASeekALaterTransitionIsPreparedOnTheRecycledPlayer() {
        val r = rig()
        r.facade.seekTo(10_000L); idleMainLooper()
        r.currentIndex = 2
        assertPreparedOnTheRecycledPlayer(r, 3)
    }

    @Test fun afterNextALaterTransitionIsPreparedOnTheRecycledPlayerForTheNewCurrent() {
        val r = rig()
        r.facade.seekToNext(); idleMainLooper()
        r.currentIndex = 3
        assertPreparedOnTheRecycledPlayer(r, 4)
    }

    // ── source guards ────────────────────────────────────────────────────────────────────────────────────────────────────

    private fun code(file: String) = java.io.File("src/main/kotlin/com/launchpoint/wavdrop/playback/$file").readLines()
        .filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") || it.trimStart().startsWith("/*") }.joinToString("\n")

    @Test fun overlapSettlementHasNoStatsPersistenceOrOldHandoffOrSecondFlag() {
        for (file in listOf("CrossfadePromotionRuntime.kt", "PlayerEngine.kt")) {
            val c = code(file)
            listOf("StatsTracker", "recordPlay", "NowPlaying", "persist", "DataStore", "reconcile", "CrossfadePreparationRuntime", "RUNTIME_ENABLED", "swapRolesForTest(").forEach {
                // swapRolesForTest( may only appear as its own declaration in the engine
                if (!(file == "PlayerEngine.kt" && it == "swapRolesForTest(")) assertFalse("$file must not contain `$it`", c.contains(it))
            }
        }
    }

    @Test fun theRetiringPlayerIsNeverSoughtOrRolledBackInTheSettlementPath() {
        val c = code("CrossfadePromotionRuntime.kt") + code("PlayerEngine.kt")
        listOf(".seekTo(", "seekToNext", "seekToPrevious", "rollback", "Rollback", "restoreOutgoing", "swapRoles()").forEach {
            // table.swapRoles() is the promotion/failure bookkeeping only; the settlement seam never swaps back
            if (it != "swapRoles()") assertFalse("`$it` must not appear in the overlap path", c.contains(it))
        }
        val settle = code("CrossfadePromotionRuntime.kt").substringAfter("fun settleOverlap").substringBefore("private fun debug")
        assertFalse(settle.contains("swapRoles"))
        assertFalse(settle.contains("replaceDelegate"))
        assertFalse(settle.contains("promoteReadyNext"))
    }

    @Test fun theSharedSinkFansOutToTheRuntimeAndTheExplicitHooksRunBeforeTheActionIsForwarded() {
        val service = java.io.File("src/main/kotlin/com/launchpoint/wavdrop/playback/PlaybackService.kt").readText()
        assertTrue(service.contains("promotionRuntime?.cancel(reason)"))
        val previous = java.io.File("src/main/kotlin/com/launchpoint/wavdrop/playback/PreviousBehaviorPlayer.kt").readText()
        assertTrue(previous.indexOf("onExplicitPause()") < previous.indexOf("super.pause()"))
        assertTrue(previous.indexOf("if (isExternalUserTransportRequest()) onExplicitSeek()") < previous.indexOf("super.seekTo(positionMs)"))
        val controller = java.io.File("src/main/kotlin/com/launchpoint/wavdrop/playback/PlayerController.kt").readText()
        assertTrue(controller.indexOf("explicitSeekListeners.notifyExplicitSeek()") in 0 until controller.indexOf("val clamped = positionMs.coerceIn"))
    }

    @Test fun routeRemovalBookkeepingNeverPausesPlayback() {
        val controller = java.io.File("src/main/kotlin/com/launchpoint/wavdrop/playback/PlayerController.kt").readText()
        for (name in listOf("fun onBluetoothDeviceRemoved()", "fun onWiredDeviceRemoved()")) {
            val body = controller.replace("\r\n", "\n").substringAfter(name).substringBefore("\n    }\n")
            assertFalse("$name must not pause: it only records interruption entitlement", body.contains("pause()") || body.contains("playWhenReady") || body.contains("setPlayWhenReady"))
        }
    }

    @Test fun onlyTheComposerWritesPhysicalVolumeAndOnlyThroughApplyVolumes() {
        assertEquals(1, Regex("[.]volume = ").findAll(code("PlayerEngine.kt")).count())
        assertTrue(code("CrossfadePromotionRuntime.kt").let { !it.contains(".volume") && !it.contains("setVolume") })
    }
}
