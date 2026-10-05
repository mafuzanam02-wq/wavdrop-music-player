package com.launchpoint.wavdrop.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import com.launchpoint.wavdrop.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * CF-2M4: the NEXT-slot preparation driver reuses the existing planning/ownership rules, and every explicit-cancellation
 * family (the same `recoverCrossfadeFrom...` lifecycle point CF-2G/2H/2I wired) ends NEXT's ownership through the shared sink.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class NextSlotInvalidationTest {

    private class FakeScheduler : CrossfadeTimingScheduler {
        class Pending(val delayMs: Long, val block: () -> Unit) { var active = true }
        val all = mutableListOf<Pending>()
        val activeNow get() = all.filter { it.active }
        override fun postDelayed(delayMs: Long, block: () -> Unit) { all += Pending(delayMs, block) }
        override fun cancelAll() { all.forEach { it.active = false } }
        fun runNext() { val p = activeNow.single(); p.active = false; p.block() }
    }

    private fun song(id: Long) = Song(
        id = id, title = "S$id", artist = "A", album = "B", albumId = 0L, duration = 200_000L,
        uri = "content://media/$id", dateAdded = 0L, trackNumber = 0, year = 2020,
    )

    private class Harness(test: NextSlotInvalidationTest) {
        val fixture = PlayerEngineFixture()
        val scheduler = FakeScheduler()
        var queue = (1L..5L).map(test::song)
        var generation = 7L
        var currentIndex = 1
        var repeat = RepeatMode.OFF
        var playing = true
        var connected = true
        var eq = false
        var configured = 6_000L
        val materializeCalls = mutableListOf<Int>()

        val driver = NextSlotPreparationDriver(
            preparation = fixture.engine.nextPreparation,
            snapshotProvider = {
                CrossfadeRuntimeSnapshot(
                    queueGeneration = generation, playbackQueue = queue, currentPlaybackIndex = currentIndex,
                    repeatMode = repeat, shuffleEnabled = false, isPlaying = playing, isExternalPlayback = false,
                    playerQueueNeedsSync = false, controllerConnected = connected, equalizerEnabled = eq,
                )
            },
            materialize = { songs -> materializeCalls += songs.size; songs.map { MediaItem.Builder().setMediaId(it.id.toString()).build() } },
            configuredDurationMsProvider = { configured },
            currentDurationMsProvider = { 200_000L },
            scheduler = scheduler,
        )
        val prep get() = fixture.engine.nextPreparation
        val p2 get() = fixture.p2

        fun toReady(): NextSlotState.Ready {
            driver.evaluate()
            p2.becomeReady()
            idleMainLooper()
            return prep.state as NextSlotState.Ready
        }
    }

    private fun harness() = Harness(this)

    // ── driver: reuses the existing plan; prepares target first; idempotent ──────────────────────────────────────────────

    @Test fun eligibleTransitionPreparesTheExactTargetThenGraftsTheWholeQueue() {
        val h = harness()
        h.driver.evaluate()
        assertEquals(CrossfadeTransitionKey(7L, 1, 2), h.prep.state.key)
        assertEquals("only B (queue index 2 = song 3) is loaded first", listOf("3"), h.p2.mediaIds)
        h.p2.becomeReady(); idleMainLooper()
        assertEquals(listOf("1", "2", "3", "4", "5"), h.p2.mediaIds)
        assertEquals(2, h.p2.currentMediaItemIndex)
        assertFalse(h.p2.playWhenReady)
    }

    @Test fun repeatedEvaluationIsIdempotentAndDoesNotMaterializeAgain() {
        val h = harness()
        h.driver.evaluate(); h.driver.evaluate(); h.p2.becomeReady(); h.driver.evaluate()
        assertEquals(1, h.prep.preparationsStarted)
        assertEquals("materialization only when a new request is needed", 1, h.materializeCalls.size)
    }

    @Test fun repeatModeIsMirroredFromTheSameSnapshot() {
        val h = harness()
        h.repeat = RepeatMode.ALL
        h.driver.evaluate(); h.p2.becomeReady(); idleMainLooper()
        assertEquals(androidx.media3.common.Player.REPEAT_MODE_ALL, h.p2.repeatMode)
    }

    @Test fun repeatOneKeepsCrossfadeIneligibleSoNothingIsPrepared() {
        val h = harness()
        h.repeat = RepeatMode.ONE
        h.driver.evaluate()
        assertEquals(NextSlotState.Idle, h.prep.state)
        assertEquals(0, h.p2.mediaItemCount)
    }

    @Test fun ineligiblePlansPrepareNothingAndTearDownAnOwnedPreparation() {
        val cases = listOf<(Harness) -> Unit>(
            { it.playing = false }, { it.eq = true }, { it.connected = false }, { it.configured = 0L },
            { it.queue = it.queue.take(1) }, { it.repeat = RepeatMode.ONE },
        )
        cases.forEachIndexed { i, mutate ->
            val h = harness()
            h.toReady()
            mutate(h)
            h.driver.evaluate()
            assertEquals("case $i must end ownership", NextSlotState.Idle, h.prep.state)
            assertEquals("case $i must empty NEXT", 0, h.p2.mediaItemCount)
        }
    }

    @Test fun queueGenerationChangeEndsOwnershipAndRearmsTheNewGenerationsKey() {
        val h = harness()
        h.toReady()
        h.generation = 8L
        h.driver.evaluate()
        assertEquals(CrossfadeTransitionKey(8L, 1, 2), h.prep.state.key)
        assertEquals(1, h.prep.invalidations)
    }

    @Test fun naturalAdvanceOfCurrentSupersedesTheOldKeyWithTheNextOccurrence() {
        val h = harness()
        h.toReady()
        h.currentIndex = 2
        h.driver.evaluate()
        assertEquals(CrossfadeTransitionKey(7L, 2, 3), h.prep.state.key)
        assertEquals(listOf("4"), h.p2.mediaIds)
    }

    @Test fun targetOccurrenceChangingUnderTheSameIndexIsAGenerationChangeAndNeverReusesAPreparedB() {
        // Same song ids at the target index but a new generation (e.g. a reorder that reproduced the order): never reused.
        val h = harness()
        val first = h.toReady()
        h.generation = 9L
        h.queue = h.queue.toList()
        h.driver.evaluate()
        assertTrue(h.prep.state is NextSlotState.PreparingTarget)
        assertTrue((h.prep.state as NextSlotState.PreparingTarget).token != first.token)
    }

    @Test fun aFailedKeyIsNotRetriedByThePoll() {
        val h = harness()
        h.driver.evaluate()
        h.p2.failWith()
        h.p2.commands.clear()
        h.driver.evaluate(); h.driver.evaluate()
        assertTrue(h.prep.state is NextSlotState.Failed)
        assertTrue(h.p2.commands.isEmpty())
    }

    // ── scheduling / lifecycle ──────────────────────────────────────────────────────────────────────────────────────────

    @Test fun startPollsAtTheExistingPreFadeCadenceAndStopCancelsThePulse() {
        val h = harness()
        h.driver.start()
        assertEquals(0L, h.scheduler.activeNow.single().delayMs)
        h.scheduler.runNext()
        assertTrue(h.prep.state is NextSlotState.PreparingTarget)
        assertEquals(CrossfadeTimingDriver.PRE_FADE_POLL_INTERVAL_MS, h.scheduler.activeNow.single().delayMs)
        h.driver.stop()
        assertTrue(h.scheduler.activeNow.isEmpty())
    }

    @Test fun durationPolicyStartsOnEnableAndDisableInvalidatesNextBeforeStopping() {
        val h = harness()
        assertEquals(CrossfadeActivationDecision.Start, applyNextSlotConfiguredDurationChange(null, 6_000L, h.driver))
        assertEquals(1, h.scheduler.activeNow.size)
        h.scheduler.runNext()
        h.p2.becomeReady(); idleMainLooper()
        assertTrue(h.prep.state is NextSlotState.Ready)
        assertEquals(CrossfadeActivationDecision.UpdateOnly, applyNextSlotConfiguredDurationChange(6_000L, 8_000L, h.driver))
        assertTrue("update-only never cancels", h.prep.state is NextSlotState.Ready)
        assertEquals(CrossfadeActivationDecision.Disable, applyNextSlotConfiguredDurationChange(8_000L, 0L, h.driver))
        assertEquals(NextSlotState.Idle, h.prep.state)
        assertEquals(CrossfadeCancelReason.ConfigurationDisabled, h.prep.lastInvalidationReason)
        assertTrue(h.scheduler.activeNow.isEmpty())
    }

    @Test fun nullDriverIsHarmlessForTheDurationPolicy() {
        applyNextSlotConfiguredDurationChange(null, 6_000L, null)
        applyNextSlotConfiguredDurationChange(6_000L, 0L, null)
    }

    @Test fun closeInvalidatesAndLaterCancelsAreInert() {
        val h = harness()
        h.toReady()
        h.driver.close()
        assertEquals(NextSlotState.Idle, h.prep.state)
        h.driver.evaluate()
        assertEquals(NextSlotState.Idle, h.prep.state)
        h.driver.cancel(CrossfadeCancelReason.Seek)
    }

    // ── every explicit-cancellation family ends NEXT through the shared sink ────────────────────────────────────────────

    private val families: List<Pair<String, (CrossfadeCancelSink?) -> Unit>> = listOf(
        "primary playback error" to { s -> recoverCrossfadeFromPrimaryPlaybackError(s) },
        "explicit pause" to { s -> recoverCrossfadeFromExplicitPause(s) },
        "explicit seek" to { s -> recoverCrossfadeFromExplicitSeek(s) },
        "next/previous navigation" to { s -> recoverCrossfadeFromExplicitNavigation(s) },
        "repeat change" to { s -> recoverCrossfadeFromRepeatChange(s) },
        "shuffle change" to { s -> recoverCrossfadeFromShuffleChange(s) },
        "play-next mutation" to { s -> recoverCrossfadeFromPlayNextMutation(s) },
        "add-to-queue mutation" to { s -> recoverCrossfadeFromAddToQueueMutation(s) },
        "queue reorder" to { s -> recoverCrossfadeFromQueueReorder(s) },
        "queue removal" to { s -> recoverCrossfadeFromQueueRemoval(s) },
        "library deletion" to { s -> recoverCrossfadeFromLibraryDeletion(s) },
        "whole-queue replacement" to { s -> recoverCrossfadeFromQueueReplacement(s) },
        "adopted resumption" to { s -> recoverCrossfadeFromPlaybackResumption(s) },
        "primary terminal state" to { s -> recoverCrossfadeFromPrimaryTerminalState(s) },
        "controller disconnect" to { s -> recoverCrossfadeFromControllerDisconnected(s) },
        "audio focus / route interruption" to { s -> recoverCrossfadeFromPrimaryInterruption(s) },
        "equalizer enabled" to { s -> recoverCrossfadeFromEqualizerEnabled(s) },
    )

    @Test fun everyExplicitCancellationFamilyInvalidatesAReadyNextAndPreparingNext() {
        for ((name, hook) in families) {
            val ready = harness()
            ready.toReady()
            hook(ready.driver)
            assertEquals("$name must invalidate a Ready NEXT", NextSlotState.Idle, ready.prep.state)
            assertEquals("$name must empty NEXT", 0, ready.p2.mediaItemCount)
            assertTrue("$name leaves CURRENT untouched", ready.fixture.p1.commands.isEmpty())

            val preparing = harness()
            preparing.driver.evaluate()
            hook(preparing.driver)
            assertEquals("$name must invalidate a Preparing NEXT", NextSlotState.Idle, preparing.prep.state)
            // A late READY from the invalidated attempt does nothing.
            preparing.p2.mutate { }
            assertEquals(0, preparing.p2.mediaItemCount)
        }
    }

    @Test fun nullSinkIsAHarmlessNoOpForEveryFamily() {
        families.forEach { (_, hook) -> hook(null) }
    }

    @Test fun theLegacyRuntimeStillSatisfiesTheSinkWidening() {
        // CrossfadePreparationRuntime is a CrossfadeCancelSink: existing call sites that pass a runtime keep compiling.
        val sink: CrossfadeCancelSink? = null as CrossfadePreparationRuntime?
        recoverCrossfadeFromExplicitSeek(sink)
    }

    // ── service wiring (source guards) ──────────────────────────────────────────────────────────────────────────────────

    private fun service(): String =
        File("src/main/kotlin/com/launchpoint/wavdrop/playback/PlaybackService.kt").readText()

    @Test fun everyRecoverCallInTheServiceGoesThroughTheSharedSink() {
        val calls = Regex("recoverCrossfadeFrom[A-Za-z]+\\(([A-Za-z]+)\\)").findAll(service()).toList()
        assertTrue("expected the full hook family in the service, found ${calls.size}", calls.size >= 17)
        val offenders = calls.filter { it.groupValues[1] != "crossfadeCancelSink" }.map { it.value }
        assertTrue("these hooks bypass the shared sink: $offenders", offenders.isEmpty())
    }

    @Test fun theSharedSinkEndsBothTheLegacyRuntimeAndTheEngineNextPreparation() {
        val s = service()
        val sink = s.substringAfter("private val crossfadeCancelSink").substringBefore("\n    }\n")
        assertTrue(sink.contains("crossfadePreparation?.cancel(reason)"))
        assertTrue(sink.contains("nextSlotDriver?.cancel(reason)"))
    }

    @Test fun theNextSlotDriverIsBuiltOnlyForTheEngineTopology() {
        val s = service()
        assertTrue(s.contains("assembly.engine?.let { engine ->"))
        assertEquals(1, Regex("NextSlotPreparationDriver\\(").findAll(s).count())
    }
}
