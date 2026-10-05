package com.launchpoint.wavdrop.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.launchpoint.wavdrop.data.playback.PlaybackSessionSnapshot
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * AK-1: app-kill / task-removal lifecycle. A Recents swipe is NOT process death and NOT force-stop: the UI task must not own
 * playback lifetime, a valid (playing or paused) session-facing queue stays resumable, an empty session may stop, and an active
 * crossfade preparation/overlap is untouched by the swipe. Process recovery comes from the persisted session, never from keeping
 * the service alive; a crossfade overlap is never persisted or reconstructed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class PlaybackLifecycleHardeningTest {

    private fun scripted(titles: List<String>, playing: Boolean, state: Int = Player.STATE_READY) =
        ScriptedPlayer("S", titles = titles, playing = playing, state = state)

    // ── pure task-removal decision ───────────────────────────────────────────────────────────────────────────────────────

    @Test fun aPlayingOrPausedSessionFacingQueueKeepsTheSession() {
        assertEquals(TaskRemovalDecision.KeepSession, TaskRemovalPlaybackPolicy.decide(scripted(listOf("A", "B"), playing = true)))
        assertEquals(TaskRemovalDecision.KeepSession, TaskRemovalPlaybackPolicy.decide(scripted(listOf("A", "B"), playing = false)))
    }

    @Test fun theDecisionDoesNotDependOnIsPlayingAlone() {
        val playing = TaskRemovalPlaybackPolicy.decide(scripted(listOf("A"), playing = true))
        val paused = TaskRemovalPlaybackPolicy.decide(scripted(listOf("A"), playing = false))
        val pausedBuffering = TaskRemovalPlaybackPolicy.decide(scripted(listOf("A"), playing = false, state = Player.STATE_BUFFERING))
        assertEquals(playing, paused)
        assertEquals(playing, pausedBuffering)
        assertEquals(TaskRemovalDecision.DefaultStop, TaskRemovalPlaybackPolicy.decide(scripted(emptyList(), playing = false, state = Player.STATE_IDLE)))
    }

    @Test fun anEmptyOrMissingSessionPlayerAllowsTheDefaultStopEvenIfALogicalQueueExistsElsewhere() {
        // The decision takes ONLY the session-facing player: a stale PlayerController logical queue is not an input at all.
        assertEquals(TaskRemovalDecision.DefaultStop, TaskRemovalPlaybackPolicy.decide(scripted(emptyList(), playing = false, state = Player.STATE_IDLE)))
        assertEquals(TaskRemovalDecision.DefaultStop, TaskRemovalPlaybackPolicy.decide(null))
    }

    @Test fun aPreparedNextAloneNeverKeepsAnOtherwiseEmptySession() {
        val f = PlayerEngineFixture(p1Titles = listOf("A", "B"))
        f.facade.clearMediaItems(); idleMainLooper()
        assertEquals(0, f.facade.mediaItemCount)
        f.p2.setMediaItems((0 until 4).map { ScriptedPlayer.mediaItem("n$it") }, 1, 0L) // NEXT holds a prepared queue
        assertTrue(f.p2.mediaItemCount > 0)
        assertEquals("the façade presents only CURRENT", TaskRemovalDecision.DefaultStop, TaskRemovalPlaybackPolicy.decide(f.facade))
    }

    @Test fun aRetiringPlayerNeverKeepsTheSessionAliveByItself() {
        val r = OverlapRig().inOverlap()
        assertTrue("A is still retiring and holds items", r.engine.retiringPlayer != null && r.p1.mediaItemCount > 0)
        assertEquals(TaskRemovalDecision.KeepSession, TaskRemovalPlaybackPolicy.decide(r.facade))
        r.p2.clearMediaItems(); idleMainLooper() // the logical CURRENT becomes empty (the M6 terminal-state policy then cuts A as well)
        assertEquals(TaskRemovalDecision.DefaultStop, TaskRemovalPlaybackPolicy.decide(r.facade))
    }

    // ── a swipe changes nothing about an active crossfade ────────────────────────────────────────────────────────────────

    private fun snapshotOf(r: OverlapRig) = r.p1.commands.toList() to r.p2.commands.toList()

    @Test fun taskRemovalDuringPreparationLeavesThePreparedNextAndRuntimeUntouched() {
        val r = OverlapRig()
        assertTrue(r.engine.nextPreparation.state is NextSlotState.Ready)
        val before = snapshotOf(r)
        assertEquals(TaskRemovalDecision.KeepSession, TaskRemovalPlaybackPolicy.decide(r.facade))
        assertTrue("the swipe does not invalidate the preparation", r.engine.nextPreparation.state is NextSlotState.Ready)
        assertEquals(PromotionOverlapState.Idle, r.runtime.state)
        assertEquals(before, snapshotOf(r))
    }

    @Test fun taskRemovalDuringAnOverlapKeepsBAuthoritativeAndTheOverlapRunning() {
        val r = OverlapRig().inOverlap()
        val before = snapshotOf(r)
        assertEquals(TaskRemovalDecision.KeepSession, TaskRemovalPlaybackPolicy.decide(r.facade))
        assertTrue("no forced settlement", r.runtime.state is PromotionOverlapState.Overlap)
        assertEquals(before, snapshotOf(r))
        r.now += 1_000L; r.runtime.tick()
        assertTrue(r.runtime.state is PromotionOverlapState.Overlap)
        assertSame(r.p2, r.engine.currentPlayer)
        assertSame(r.p2, r.facade.delegatePlayer)
        r.now += 20_000L; r.runtime.tick() // the overlap still completes normally after the swipe
        assertEquals(PromotionOutcome.Completed(r.key), r.runtime.lastOutcome)
        assertEquals(0, r.p1.mediaItemCount)
        assertEquals(TaskRemovalDecision.KeepSession, TaskRemovalPlaybackPolicy.decide(r.facade))
        assertTrue(r.p2.playWhenReady)
    }

    @Test fun theServiceTaskRemovalPathNeverTouchesTheCrossfadeOwners() {
        val body = serviceSource().substringAfter("override fun onTaskRemoved").substringBefore("override fun onDestroy")
            .lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
        listOf("promotionRuntime", "nextSlotDriver", "crossfadeCancelSink", "recoverCrossfade", "settleOverlap", "engine", "release()", "stopSelf").forEach {
            assertFalse("onTaskRemoved must not reference `$it`", body.contains(it))
        }
        assertTrue(body.contains("TaskRemovalPlaybackPolicy.decide("))
        assertTrue("a kept session returns BEFORE the Media3 default stop", body.indexOf("return") in 0 until body.indexOf("super.onTaskRemoved"))
    }

    // ── paused crossfade-ready session ───────────────────────────────────────────────────────────────────────────────────

    @Test fun aPausedSessionWithAPreparedNextKeepsCurrentResumableAndPreparationRestartsLater() {
        val r = OverlapRig()
        val scheduler = OverlapScheduler()
        val driver = NextSlotPreparationDriver(
            preparation = r.engine.nextPreparation,
            snapshotProvider = { CrossfadeRuntimeSnapshot(7L, r.songs, 1, RepeatMode.OFF, false, true, false, false, true) },
            materialize = { songs -> songs.map { MediaItem.Builder().setMediaId(r.titles[(it.id - 1).toInt()]).build() } },
            configuredDurationMsProvider = { 6_000L }, currentDurationMsProvider = { 180_000L }, scheduler = scheduler,
        )
        r.facade.pause(); idleMainLooper()
        driver.cancel(CrossfadeCancelReason.Pause) // the existing explicit-pause policy invalidates the prepared transition
        assertEquals(NextSlotState.Idle, r.engine.nextPreparation.state)
        assertEquals("the swipe keeps the session because CURRENT has a real resumable queue",
            TaskRemovalDecision.KeepSession, TaskRemovalPlaybackPolicy.decide(r.facade))
        assertFalse(r.p1.playWhenReady)
        val p2Before = r.p2.commands.toList()
        r.facade.play(); idleMainLooper() // a later PLAY resumes CURRENT normally, with no autoplay from the swipe itself
        assertTrue(r.p1.playWhenReady)
        assertEquals(1, r.p1.commands.count { it == "P1.setPlayWhenReady(true)" })
        assertEquals(p2Before, r.p2.commands.toList())
        assertSame(r.p1, r.engine.currentPlayer)
        driver.evaluate() // preparation may restart later
        assertTrue(r.engine.nextPreparation.state.toString(), r.engine.nextPreparation.state is NextSlotState.PreparingTarget)
    }

    // ── teardown contract ────────────────────────────────────────────────────────────────────────────────────────────────

    private fun serviceSource() = File("src/main/kotlin/com/launchpoint/wavdrop/playback/PlaybackService.kt").readText().replace("\r\n", "\n")

    @Test fun onDestroyKeepsItsPinnedOrder() {
        val body = serviceSource().substringAfter("override fun onDestroy() {").substringBefore("super.onDestroy()")
        val order = listOf(
            "playerController.setExplicitSeekListener(null)", "playerController.setControllerDisconnectedListener(null)",
            "unregisterAudioDeviceCallback(audioDeviceCallback)", "serviceScope.cancel()", "promotionRuntime?.close()",
            "nextSlotDriver?.close()", "enhancementController?.release()", "engine?.release()", "mediaSession?.run",
        ).map { body.indexOf(it).also { i -> assertTrue("missing in onDestroy: $it", i >= 0) } }
        assertEquals("teardown order changed: $order", order.sorted(), order)
    }

    @Test fun everyControllerCallbackTheServiceRegistersIsClearedInOnDestroy() {
        val s = serviceSource()
        val registered = Regex("playerController\\.set(Explicit\\w+|ControllerDisconnected)Listener \\{").findAll(s).map { it.groupValues[1] }.toSet()
        val cleared = Regex("playerController\\.set(Explicit\\w+|ControllerDisconnected)Listener\\(null\\)").findAll(s).map { it.groupValues[1] }.toSet()
        assertTrue(registered.isNotEmpty())
        assertEquals("a callback registered in onCreate has no clear in onDestroy", registered, cleared)
    }

    @Test fun theFullTeardownInServiceOrderLeavesNoLiveCallbackAndReleasesEachPlayerOnce() {
        val r = OverlapRig()
        val driverScheduler = OverlapScheduler()
        var materialized = 0
        val driver = NextSlotPreparationDriver(
            preparation = r.engine.nextPreparation,
            snapshotProvider = { CrossfadeRuntimeSnapshot(7L, r.songs, 2, RepeatMode.OFF, false, true, false, false, true) },
            materialize = { materialized++; emptyList() },
            configuredDurationMsProvider = { 6_000L }, currentDurationMsProvider = { 180_000L }, scheduler = driverScheduler,
        )
        r.position(173_500L); r.runtime.start(); r.scheduler.runNext() // a live overlap through the real pulse
        driver.start()
        assertTrue(r.runtime.state is PromotionOverlapState.Overlap)
        val staleTick = r.scheduler.activeNow.single()
        val staleDriverPulse = driverScheduler.activeNow.single()
        // service order: runtime first, then the preparation driver, then the engine (the only physical release)
        r.runtime.close(); driver.close(); r.engine.release()
        r.runtime.close(); driver.close(); r.engine.release() // idempotent
        assertTrue(r.scheduler.activeNow.isEmpty() && driverScheduler.activeNow.isEmpty())
        staleTick.block(); staleDriverPulse.block() // callbacks that survived must be inert
        assertEquals(PromotionOverlapState.Idle, r.runtime.state)
        assertEquals(0, materialized)
        assertEquals(listOf("P1", "P2"), r.base.f.releases.sorted())
        assertEquals(1, r.engine.focusReleaseCount)
        assertTrue(driverScheduler.activeNow.isEmpty() && r.scheduler.activeNow.isEmpty())
    }

    @Test fun theDriverCloseIsIdempotentAndAStaleCallbackAfterCloseDoesNothing() {
        val r = OverlapRig()
        val s = OverlapScheduler()
        var materialized = 0
        val driver = NextSlotPreparationDriver(
            preparation = r.engine.nextPreparation,
            snapshotProvider = { CrossfadeRuntimeSnapshot(7L, r.songs, 1, RepeatMode.OFF, false, true, false, false, true) },
            materialize = { materialized++; emptyList() }, configuredDurationMsProvider = { 6_000L }, currentDurationMsProvider = { 180_000L }, scheduler = s,
        )
        driver.start()
        val stale = s.activeNow.single()
        driver.close(); driver.close()
        stale.block()
        assertEquals(0, materialized)
        assertTrue(s.activeNow.isEmpty())
        assertEquals(NextSlotState.Idle, r.engine.nextPreparation.state)
    }

    // ── process death / force-stop model ─────────────────────────────────────────────────────────────────────────────────

    @Test fun thePersistedSessionHasNoCrossfadeOverlapOrSlotRoleState() {
        val fields = PlaybackSessionSnapshot::class.java.declaredFields.map { it.name.lowercase() }.filterNot { it == "\$stable" }
        listOf("next", "retiring", "overlap", "crossfade", "slot", "promotion", "fade").forEach { word ->
            assertTrue("persisted session must not carry `$word` state: $fields", fields.none { it.contains(word) })
        }
        listOf("queuesongids", "playbackorder", "currentsongid", "currentindex", "positionms", "repeatmode", "shuffleenabled").forEach {
            assertTrue("persisted contract lost `$it`: $fields", fields.contains(it))
        }
    }

    @Test fun aRecreatedEngineStartsWithOnlyCurrentAndFreshCrossfadePreparationCanBegin() {
        // Process recovery builds a NEW engine: NEXT empty/idle/paused (the engine enforces it), no retiring player, nothing promoted.
        val f = PlayerEngineFixture(p1Titles = listOf("A", "B", "C", "D"), p1Index = 1)
        assertEquals(0, f.p2.mediaItemCount)
        assertEquals(NextSlotState.Idle, f.engine.nextPreparation.state)
        assertTrue(f.engine.retiringPlayer == null && !f.engine.promotionActive)
        val songs = (1L..4L).map { id ->
            com.launchpoint.wavdrop.data.model.Song(id = id, title = "S$id", artist = "A", album = "B", albumId = 0L, duration = 180_000L,
                uri = "content://media/$id", dateAdded = 0L, trackNumber = 0, year = 2020)
        }
        val driver = NextSlotPreparationDriver(
            preparation = f.engine.nextPreparation,
            snapshotProvider = { CrossfadeRuntimeSnapshot(1L, songs, 1, RepeatMode.OFF, false, true, false, false, true) },
            materialize = { s -> s.map { MediaItem.Builder().setMediaId(listOf("A", "B", "C", "D")[(it.id - 1).toInt()]).build() } },
            configuredDurationMsProvider = { 6_000L }, currentDurationMsProvider = { 180_000L }, scheduler = OverlapScheduler(),
        )
        f.facade.play(); idleMainLooper()
        driver.evaluate()
        assertTrue(f.engine.nextPreparation.state.toString(), f.engine.nextPreparation.state is NextSlotState.PreparingTarget)
        assertEquals(CrossfadeTransitionKey(1L, 1, 2), (f.engine.nextPreparation.state as NextSlotState.PreparingTarget).key)
    }

    @Test fun noResurrectionMechanismExistsInThePlaybackPackage() {
        val offenders = File("src/main/kotlin/com/launchpoint/wavdrop/playback").walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { f ->
                f.readLines().filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") }
                    .any { l -> listOf("AlarmManager", "WorkManager", "JobScheduler", "START_STICKY", "setExactAndAllowWhileIdle").any { l.contains(it) } }
            }.map { it.name }.toList()
        assertTrue("force-stop must be respected; no watchdog/resurrection: $offenders", offenders.isEmpty())
        // The only code that starts the service from outside it is the existing system-broadcast reconnect receiver (a user plugging
        // in / connecting an output; a force-stopped app receives no broadcasts until the user launches it).
        val starters = File("src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { f -> f.readLines().filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") }.any { it.contains("startForegroundService") } }
            .map { it.name }.toSet()
        assertEquals(setOf("AudioOutputReconnectReceiver.kt"), starters)
    }

    // ── Equalizer state after a service recreation ───────────────────────────────────────────────────────────────────────

    @Test fun crossfadeIsBlockedWhileTheEqStateIsUnknownOrEnabled() {
        assertTrue(crossfadeEqualizerBlocks(equalizerStateKnown = false, equalizerEnabled = false))
        assertTrue(crossfadeEqualizerBlocks(equalizerStateKnown = false, equalizerEnabled = true))
        assertTrue(crossfadeEqualizerBlocks(equalizerStateKnown = true, equalizerEnabled = true))
        assertFalse(crossfadeEqualizerBlocks(equalizerStateKnown = true, equalizerEnabled = false))
    }

    @Test fun aRecreatedServiceDoesNotPrepareCrossfadeBeforeItKnowsTheEqStateAndPreparesOnceItIsOff() {
        val f = PlayerEngineFixture(p1Titles = listOf("A", "B", "C", "D"), p1Index = 1)
        f.facade.play(); idleMainLooper()
        var known = false
        val songs = (1L..4L).map { id ->
            com.launchpoint.wavdrop.data.model.Song(id = id, title = "S$id", artist = "A", album = "B", albumId = 0L, duration = 180_000L,
                uri = "content://media/$id", dateAdded = 0L, trackNumber = 0, year = 2020)
        }
        val driver = NextSlotPreparationDriver(
            preparation = f.engine.nextPreparation,
            snapshotProvider = {
                CrossfadeRuntimeSnapshot(1L, songs, 1, RepeatMode.OFF, false, true, false, false, true,
                    equalizerEnabled = crossfadeEqualizerBlocks(known, false))
            },
            materialize = { s -> s.map { MediaItem.Builder().setMediaId(listOf("A", "B", "C", "D")[(it.id - 1).toInt()]).build() } },
            configuredDurationMsProvider = { 6_000L }, currentDurationMsProvider = { 180_000L }, scheduler = OverlapScheduler(),
        )
        driver.evaluate()
        assertEquals("EQ state unknown: no preparation", NextSlotState.Idle, f.engine.nextPreparation.state)
        known = true // the persisted EQ value (off) arrived
        driver.evaluate()
        assertNotEquals(NextSlotState.Idle, f.engine.nextPreparation.state)
        val src = serviceSource()
        assertEquals("both service snapshot providers apply the unknown-state guard", 2, Regex("crossfadeEqualizerBlocks\\(crossfadeEqualizerKnown, crossfadeEqualizerEnabled\\)").findAll(src).count())
        assertTrue(src.contains("crossfadeEqualizerKnown = true"))
    }
}
