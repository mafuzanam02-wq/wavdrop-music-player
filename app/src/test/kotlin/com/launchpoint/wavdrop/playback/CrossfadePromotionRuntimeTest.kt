package com.launchpoint.wavdrop.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.launchpoint.wavdrop.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * CF-2M5: the overlap owner. Timing (monotonic elapsed time, late-tick clamp, END_MARGIN, insufficient tail), the full successful
 * overlap through the scheduler, settlement and recycling of P1 for the next M4 preparation, and the fail-closed cancel contract.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class CrossfadePromotionRuntimeTest {

    private class FakeScheduler : CrossfadeTimingScheduler {
        class Pending(val delayMs: Long, val block: () -> Unit) { var active = true }
        val all = mutableListOf<Pending>()
        val activeNow get() = all.filter { it.active }
        override fun postDelayed(delayMs: Long, block: () -> Unit) { all += Pending(delayMs, block) }
        override fun cancelAll() { all.forEach { it.active = false } }
        fun runNext() { val p = activeNow.single(); p.active = false; p.block() }
    }

    private fun song(id: Long) = Song(
        id = id, title = "S$id", artist = "A", album = "B", albumId = 0L, duration = 180_000L,
        uri = "content://media/$id", dateAdded = 0L, trackNumber = 0, year = 2020,
    )

    private class Rig(test: CrossfadePromotionRuntimeTest) {
        val base = PromotionRig().playAndPrepare()
        val scheduler = FakeScheduler()
        var now = 1_000L
        var generation = 7L
        var eq = false
        var configured = 6_000L
        val songs = (1L..4L).map(test::song)
        val logs = mutableListOf<String>()
        val runtime = CrossfadePromotionRuntime(
            engine = base.engine,
            snapshotProvider = {
                CrossfadeRuntimeSnapshot(
                    queueGeneration = generation, playbackQueue = songs, currentPlaybackIndex = base.from,
                    repeatMode = RepeatMode.OFF, shuffleEnabled = false, isPlaying = true, isExternalPlayback = false,
                    playerQueueNeedsSync = false, controllerConnected = true, equalizerEnabled = eq,
                )
            },
            configuredDurationMsProvider = { configured },
            scheduler = scheduler,
            clock = { now },
            debugLog = { logs += it },
        )
        val engine get() = base.engine
        val p1 get() = base.p1
        val p2 get() = base.p2

        fun position(ms: Long) = p1.mutate { setContentPositionMs(ms) }
    }

    private fun rig() = Rig(this)

    // ── when is the window due ───────────────────────────────────────────────────────────────────────────────────────────

    @Test fun nothingHappensBeforeTheFadeStartPosition() {
        val r = rig()
        r.position(173_499L)
        r.runtime.evaluate()
        assertEquals(PromotionOverlapState.Idle, r.runtime.state)
        assertTrue(r.p2.commands.isEmpty())
        assertSame(r.p1, r.engine.currentPlayer)
    }

    @Test fun theWindowOpensAtDurationMinusFadeMinusEndMarginAndPromotesOnce() {
        val r = rig()
        r.position(180_000L - 6_000L - CrossfadePromotionTiming.END_MARGIN_MS)
        r.runtime.evaluate()
        val overlap = r.runtime.state as PromotionOverlapState.Overlap
        assertEquals(6_000L, overlap.durationMs)
        assertEquals(r.base.key, overlap.key)
        assertEquals(1_000L, overlap.startedAtMs)
        assertEquals(1, r.p2.commands.count { it == "P2.setPlayWhenReady(true)" })
        // re-evaluating while an overlap exists does nothing
        r.runtime.evaluate()
        assertEquals(1, r.p2.commands.count { it == "P2.setPlayWhenReady(true)" })
    }

    @Test fun aLateObservationShortensTheFadeSoItStillEndsEndMarginBeforeTheNaturalEnd() {
        val r = rig()
        r.position(175_000L)
        r.runtime.evaluate()
        val overlap = r.runtime.state as PromotionOverlapState.Overlap
        assertEquals(4_500L, overlap.durationMs)
        assertEquals(500L, 180_000L - (175_000L + overlap.durationMs))
    }

    @Test fun everyDueObservationEndsAtLeastEndMarginBeforeTheNaturalEnd() {
        for (position in listOf(173_500L, 174_000L, 175_500L, 177_000L, 178_500L)) {
            val r = rig()
            r.position(position)
            r.runtime.evaluate()
            val overlap = r.runtime.state as PromotionOverlapState.Overlap
            assertTrue("pos=$position: fade ends ${180_000L - position - overlap.durationMs} ms before the end",
                position + overlap.durationMs + CrossfadePromotionTiming.END_MARGIN_MS <= 180_000L)
            assertTrue(overlap.durationMs >= CrossfadeRules.MIN_ENABLED_DURATION_MS)
        }
    }

    @Test fun insufficientRemainingTailSkipsThePromotionAndIsNotRetried() {
        val r = rig()
        r.position(179_000L) // 180,000 - 179,000 - 500 = 500 ms < 1,000 ms minimum
        r.runtime.evaluate()
        assertEquals(PromotionOverlapState.Idle, r.runtime.state)
        assertTrue(r.runtime.lastOutcome is PromotionOutcome.Skipped)
        assertFalse("B was never started", r.p2.commands.any { it == "P2.setPlayWhenReady(true)" })
        assertSame(r.p1, r.engine.currentPlayer)
        assertSame(r.p1, r.base.f.facade.delegatePlayer)
        assertEquals("the preparation was invalidated, NEXT emptied", 0, r.p2.mediaItemCount)
        r.runtime.evaluate()
        assertEquals(1, r.engine.nextPreparation.invalidations)
    }

    @Test fun theMinimumMeaningfulTailStillPromotes() {
        val r = rig()
        r.position(178_500L) // exactly 1,000 ms available
        r.runtime.evaluate()
        assertEquals(1_000L, (r.runtime.state as PromotionOverlapState.Overlap).durationMs)
    }

    @Test fun anIneligibleLivePlanOrLostOwnershipNeverPromotes() {
        for (mutate in listOf<(Rig) -> Unit>({ it.eq = true }, { it.generation = 9L }, { it.configured = 0L })) {
            val r = rig()
            r.position(175_000L)
            mutate(r)
            r.runtime.evaluate()
            assertEquals(PromotionOverlapState.Idle, r.runtime.state)
            assertTrue(r.p2.commands.isEmpty())
        }
    }

    // ── timing model: monotonic elapsed time, not ticks ──────────────────────────────────────────────────────────────────

    private fun Rig.promoteNow(positionMs: Long = 173_500L) { position(positionMs); runtime.evaluate(); check(runtime.state is PromotionOverlapState.Overlap) }

    @Test fun monotonicElapsedTimeDrivesProgress() {
        val r = rig(); r.promoteNow()
        r.now = 1_000L + 3_000L // half of 6,000 ms
        r.runtime.tick()
        val g = CrossfadeGainCurve.equalPower(0.5f)
        assertEquals(g.outgoing, r.p1.volume, 1e-5f)
        assertEquals(g.incoming, r.p2.volume, 1e-5f)
    }

    @Test fun tickCountDoesNotDriveProgress() {
        val r = rig(); r.promoteNow()
        repeat(500) { r.runtime.tick() } // many ticks at the same instant
        assertEquals(1f, r.p1.volume, 1e-6f)
        assertEquals(0f, r.p2.volume, 1e-6f)
        r.now = 1_000L + 1_500L
        r.runtime.tick()
        val g = CrossfadeGainCurve.equalPower(0.25f)
        assertEquals(g.outgoing, r.p1.volume, 1e-5f)
    }

    @Test fun aLateSchedulerTickClampsToProgressOneAndCompletes() {
        val r = rig(); r.promoteNow()
        r.now = 1_000L + 60_000L
        r.runtime.tick()
        assertEquals(PromotionOverlapState.Idle, r.runtime.state)
        assertTrue(r.runtime.lastOutcome is PromotionOutcome.Completed)
        assertEquals(1f, r.p2.volume, 0f)
    }

    @Test fun aClockThatReadsEarlierThanTheStartIsClampedToProgressZero() {
        val r = rig(); r.promoteNow()
        r.now = 500L
        r.runtime.tick()
        assertEquals(1f, r.p1.volume, 1e-6f)
        assertEquals(0f, r.p2.volume, 1e-6f)
        assertTrue(r.runtime.state is PromotionOverlapState.Overlap)
    }

    @Test fun endMarginIsANamedIndependentConstantAndNoRetiredHandoffConstantIsUsed() {
        assertEquals(500L, CrossfadePromotionTiming.END_MARGIN_MS)
        val retired = listOf("NATURAL_TAKEOVER_MAX_LAG_MS", "NATURAL_TRANSFER_ENTRY_TOLERANCE_MS", "NATURAL_TRANSFER_ABORT_TOLERANCE_MS", "NATURAL_TAKEOVER_TRANSFER_DURATION_MS", "NATURAL_TAKEOVER_MAX_LEAD_MS", "NATURAL_HANDOFF_MAX_PROJECTION_AGE_MS", "NATURAL_HANDOFF_RAW_AGREEMENT_MS", "NaturalHandoffPositionClock", "HandoffPending", "CrossfadeNaturalHandoffSeams", "reconcilePrimary", "PrimaryTakeoverFacts")
        for (file in listOf("CrossfadePromotionRuntime.kt", "PlayerEngine.kt")) {
            val code = File("src/main/kotlin/com/launchpoint/wavdrop/playback/$file").readLines()
                .filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") || it.trimStart().startsWith("/*") }.joinToString("\n")
            retired.forEach { assertFalse("$file must not use `$it` (CF-2L handoff machinery)", code.contains(it)) }
        }
    }

    // ── full successful overlap through the scheduler ───────────────────────────────────────────────────────────────────

    private fun Rig.runToCompletion() {
        position(173_500L)
        runtime.start()
        scheduler.runNext() // first pulse: window due -> promote
        check(runtime.state is PromotionOverlapState.Overlap) { runtime.state }
        var guard = 0
        while (runtime.state is PromotionOverlapState.Overlap) {
            assertEquals(CrossfadeTimingDriver.FADE_TICK_INTERVAL_MS, scheduler.activeNow.single().delayMs)
            now += 50
            scheduler.runNext()
            check(guard++ < 1000)
        }
    }

    @Test fun aSuccessfulOverlapSettlesToOneAuthoritativePlayerAndAnEmptyReusableNext() {
        val r = rig()
        val audio = org.robolectric.RuntimeEnvironment.getApplication().getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        val focusBefore = org.robolectric.Shadows.shadowOf(audio).lastAudioFocusRequest
        r.runToCompletion()
        assertEquals(PromotionOverlapState.Idle, r.runtime.state)
        assertEquals(PromotionOutcome.Completed(r.base.key), r.runtime.lastOutcome)
        // exactly one current physical, playing, full gain
        assertSame(r.p2, r.engine.currentPlayer)
        assertTrue(r.p2.playWhenReady)
        assertEquals(1f, r.p2.volume, 0f)
        assertEquals(r.base.titles, r.p2.mediaIds)
        // the retiring physical is empty, paused, neutral and NOT released
        assertEquals(0, r.p1.mediaItemCount)
        assertFalse(r.p1.playWhenReady)
        assertEquals(1f, r.p1.volume, 0f)
        assertTrue(r.base.f.releases.isEmpty())
        // one focus owner, one noisy owner, one shared session
        assertSame("no second focus request", focusBefore, org.robolectric.Shadows.shadowOf(audio).lastAudioFocusRequest)
        assertEquals(1, org.robolectric.Shadows.shadowOf(org.robolectric.RuntimeEnvironment.getApplication()).registeredReceivers.count { it.intentFilter.hasAction(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY) })
        assertEquals(SHARED_SESSION_ID, r.p1.audioSessionId)
        assertEquals(SHARED_SESSION_ID, r.p2.audioSessionId)
        assertEquals(SHARED_SESSION_ID, r.engine.audioSessionId)
        // polling resumes at the pre-fade cadence
        assertEquals(CrossfadeTimingDriver.PRE_FADE_POLL_INTERVAL_MS, r.scheduler.activeNow.single().delayMs)
        // no logical transition at fade end
        assertEquals(1, r.base.f.events.events.count { it.startsWith("transition(") })
    }

    @Test fun theRecycledP1IsPreparedAgainByTheM4DriverForTheNextTransition() {
        val r = rig()
        r.runToCompletion()
        // CURRENT is now B (index 2); the next eligible transition is 2 -> 3 and must be prepared on the recycled P1.
        val driverScheduler = FakeScheduler()
        val driver = NextSlotPreparationDriver(
            preparation = r.engine.nextPreparation,
            snapshotProvider = {
                CrossfadeRuntimeSnapshot(
                    queueGeneration = 7L, playbackQueue = r.songs, currentPlaybackIndex = 2, repeatMode = RepeatMode.OFF,
                    shuffleEnabled = false, isPlaying = true, isExternalPlayback = false, playerQueueNeedsSync = false, controllerConnected = true,
                )
            },
            materialize = { songs -> songs.map { MediaItem.Builder().setMediaId(r.base.titles[(it.id - 1).toInt()]).build() } },
            configuredDurationMsProvider = { 6_000L },
            currentDurationMsProvider = { 180_000L },
            scheduler = driverScheduler,
        )
        driver.evaluate()
        val state = r.engine.nextPreparation.state as NextSlotState.PreparingTarget
        assertEquals(CrossfadeTransitionKey(7L, 2, 3), state.key)
        assertEquals("prepared on the recycled P1", listOf("D"), r.p1.mediaIds)
        r.p1.becomeReady(); idleMainLooper()
        assertTrue(r.engine.nextPreparation.state is NextSlotState.Ready)
        assertEquals(r.base.titles, r.p1.mediaIds)
        assertEquals(3, r.p1.currentMediaItemIndex)
        assertFalse(r.p1.playWhenReady)
        assertSame(r.p2, r.engine.currentPlayer)
    }

    @Test fun theM4DriverDoesNotPlanOrMaterializeWhileARetiringPlayerOccupiesNext() {
        val r = rig(); r.promoteNow()
        var materialized = 0
        val driver = NextSlotPreparationDriver(
            preparation = r.engine.nextPreparation,
            snapshotProvider = {
                CrossfadeRuntimeSnapshot(
                    queueGeneration = 7L, playbackQueue = r.songs, currentPlaybackIndex = 2, repeatMode = RepeatMode.OFF,
                    shuffleEnabled = false, isPlaying = true, isExternalPlayback = false, playerQueueNeedsSync = false, controllerConnected = true,
                )
            },
            materialize = { materialized++; emptyList() },
            configuredDurationMsProvider = { 6_000L },
            currentDurationMsProvider = { 180_000L },
            scheduler = FakeScheduler(),
        )
        driver.evaluate()
        assertEquals(0, materialized)
        assertEquals(NextSlotState.Idle, r.engine.nextPreparation.state)
    }

    // ── fail-closed cancel contract (the CF-2M6 matrix will refine it) ───────────────────────────────────────────────────

    @Test fun aCancelDuringTheOverlapCutsAAndLeavesBAsTheOnlyLogicalCurrent() {
        val r = rig(); r.promoteNow()
        r.now += 2_000
        r.runtime.tick()
        r.runtime.cancel(CrossfadeCancelReason.Pause)
        assertEquals(PromotionOverlapState.Idle, r.runtime.state)
        assertTrue(r.runtime.lastOutcome is PromotionOutcome.Aborted)
        assertEquals(0, r.p1.mediaItemCount)
        assertFalse(r.p1.playWhenReady)
        assertEquals(1f, r.p2.volume, 0f)
        assertSame(r.p2, r.engine.currentPlayer)
        assertSame(r.p2, r.base.f.facade.delegatePlayer)
        assertEquals("no second logical transition", 1, r.base.f.events.events.count { it.startsWith("transition(") })
    }

    @Test fun aCancelWithNoOverlapIsHarmless() {
        val r = rig()
        r.runtime.cancel(CrossfadeCancelReason.Seek)
        assertEquals(PromotionOverlapState.Idle, r.runtime.state)
        assertTrue(r.engine.nextPreparation.state is NextSlotState.Ready)
    }

    @Test fun aStaleTickFromAnAbortedOverlapCannotActOnALaterOne() {
        val r = rig()
        r.position(173_500L)
        r.runtime.start()
        r.scheduler.runNext() // promote
        val stale = r.scheduler.activeNow.single()
        r.runtime.cancel(CrossfadeCancelReason.Seek)
        val volumes = r.p1.volume to r.p2.volume
        stale.block()
        assertEquals(volumes, r.p1.volume to r.p2.volume)
        assertEquals(PromotionOverlapState.Idle, r.runtime.state)
    }

    @Test fun theRetiringPlayerEndingEarlyCutsTheOverlapWithBAuthoritative() {
        val r = rig(); r.promoteNow()
        r.p1.mutate { setPlaybackState(Player.STATE_ENDED) }
        idleMainLooper()
        assertEquals(PromotionOverlapState.Idle, r.runtime.state)
        assertEquals(1, r.base.f.events.events.count { it.startsWith("transition(") })
        assertSame(r.p2, r.engine.currentPlayer)
        assertEquals(1f, r.p2.volume, 0f)
    }

    @Test fun aTailStripFailureInThePromotionPathCutsAImmediately() {
        val base = PromotionRig(hook = { if (it == PromotionStep.StripTail) error("injected") }).playAndPrepare()
        val runtime = CrossfadePromotionRuntime(
            engine = base.engine,
            snapshotProvider = {
                CrossfadeRuntimeSnapshot(
                    queueGeneration = 7L, playbackQueue = (1L..4L).map(::song), currentPlaybackIndex = base.from, repeatMode = RepeatMode.OFF,
                    shuffleEnabled = false, isPlaying = true, isExternalPlayback = false, playerQueueNeedsSync = false, controllerConnected = true,
                )
            },
            configuredDurationMsProvider = { 6_000L }, scheduler = FakeScheduler(), clock = { 1_000L },
        )
        base.p1.mutate { setContentPositionMs(173_500L) }
        runtime.evaluate()
        assertEquals(PromotionOverlapState.Idle, runtime.state)
        assertTrue(runtime.lastOutcome is PromotionOutcome.Aborted)
        assertSame(base.p2, base.engine.currentPlayer)
        assertEquals(0, base.p1.mediaItemCount)
        assertEquals(1f, base.p2.volume, 0f)
    }

    @Test fun aFadeGainFailureDuringATickCutsAWithBAuthoritative() {
        val base = PromotionRig(hook = { if (it == PromotionStep.FadeGainWrite) error("injected") }).playAndPrepare()
        var now = 1_000L
        val runtime = CrossfadePromotionRuntime(
            engine = base.engine,
            snapshotProvider = {
                CrossfadeRuntimeSnapshot(
                    queueGeneration = 7L, playbackQueue = (1L..4L).map(::song), currentPlaybackIndex = base.from, repeatMode = RepeatMode.OFF,
                    shuffleEnabled = false, isPlaying = true, isExternalPlayback = false, playerQueueNeedsSync = false, controllerConnected = true,
                )
            },
            configuredDurationMsProvider = { 6_000L }, scheduler = FakeScheduler(), clock = { now },
        )
        base.p1.mutate { setContentPositionMs(173_500L) }
        runtime.evaluate()
        assertTrue(runtime.state is PromotionOverlapState.Overlap)
        now += 1_000
        runtime.tick()
        assertEquals(PromotionOverlapState.Idle, runtime.state)
        assertSame(base.p2, base.engine.currentPlayer)
        assertEquals(0, base.p1.mediaItemCount)
        assertEquals(1, base.f.events.events.count { it.startsWith("transition(") })
    }

    @Test fun debugDiagnosticsCarryOnlyKeyAndSlotFactsAndTheSampledTickLines() {
        val r = rig()
        r.runToCompletion()
        val joined = r.logs.joinToString("\n")
        listOf("PROMOTION_BEGIN", "PROMOTION_COMMITTED", "OVERLAP_TICK", "RETIRE_BEGIN", "RETIRE_COMPLETE").forEach { assertTrue(it, joined.contains(it)) }
        assertTrue("OVERLAP_TICK is sampled, not every 50 ms tick", r.logs.count { it.startsWith("OVERLAP_TICK") } < 120 / 5)
        assertFalse(joined.contains("content://"))
        assertTrue(r.logs.first().contains("gen=7 from=1 to=2"))
    }

    @Test fun configurationDisableCutsTheOverlapBeforeStoppingThePoll() {
        val r = rig(); r.runtime.start(); r.promoteNow()
        assertEquals(CrossfadeActivationDecision.Disable, applyPromotionConfiguredDurationChange(6_000L, 0L, r.runtime))
        assertEquals(PromotionOverlapState.Idle, r.runtime.state)
        assertEquals(0, r.p1.mediaItemCount)
        assertTrue(r.scheduler.activeNow.isEmpty())
        applyPromotionConfiguredDurationChange(null, 6_000L, null) // null runtime harmless
    }

    @Test fun closeDuringAnOverlapCutsItAndStopsPolling() {
        val r = rig(); r.runtime.start(); r.promoteNow()
        r.runtime.close()
        assertEquals(PromotionOverlapState.Idle, r.runtime.state)
        assertEquals(0, r.p1.mediaItemCount)
        assertTrue(r.scheduler.activeNow.isEmpty())
        r.runtime.evaluate()
        assertNotEquals(PromotionOverlapState.Overlap::class, r.runtime.state::class)
    }
}
