package com.launchpoint.wavdrop.playback

import android.content.Context
import android.os.Bundle
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.media3.common.AudioAttributes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.playback.PlaybackSessionRepository
import com.launchpoint.wavdrop.data.repository.PlayEventWriter
import com.launchpoint.wavdrop.data.settings.AppSettingsRepository
import com.launchpoint.wavdrop.data.settings.ResumeBehaviorSettingsRepository
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.robolectric.RuntimeEnvironment

/**
 * P5-E: the PRODUCTION playback topology in one place, for the crossfade / restoration / integration tests: two real ExoPlayers behind
 * [PlayerEngine], the session [SessionFacade] presenting the logical queue, a real MediaSession, the app's own (hinted) controller, the
 * real [PlayerController] owning a bounded window through [EnginePhysicalPort], and the real NEXT-slot driver and promotion runtime
 * driven by a fake scheduler/clock. Bounded is enabled for the test only; the committed gate stays false. Codec-free: no audio claim.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal class EngineChainRig(bounded: Boolean = true) {
    private class Writer : PlayEventWriter {
        override suspend fun recordPlay(songId: Long, contentUri: String, listenedMs: Long, durationMs: Long) = Unit
        override suspend fun recordSkip(songId: Long, contentUri: String, durationMs: Long) = Unit
        override suspend fun recordListenStart(songId: Long, contentUri: String) = Unit
    }

    private class NoopScheduler : CrossfadeTimingScheduler {
        override fun postDelayed(delayMs: Long, block: () -> Unit) = Unit
        override fun cancelAll() = Unit
    }

    val context: Context = RuntimeEnvironment.getApplication()
    private val sessionId = 4242
    val p1: ExoPlayer = TestMediaSource.newPlayer(context, sessionId)
    val p2: ExoPlayer = TestMediaSource.newPlayer(context, sessionId)
    val engine = PlayerEngine(context, p1, p2, sessionId, AudioAttributes.DEFAULT)
    val session: MediaSession = MediaSession.Builder(context, engine.facade).setId("ecr-${counter++}").setCallback(AckCallback()).build()
    val controller: MediaController
    val port = EnginePhysicalPort(engine)
    val sessionRepo: PlaybackSessionRepository
    val pc: PlayerController
    val extra = mutableListOf<MediaController>()
    var now = 1_000L

    /** Songs converted to MediaItems for NEXT preparation, summed over every request (evidence that it is O(window), never O(queue)). */
    var materializedForCrossfade = 0

    val driver: NextSlotPreparationDriver
    val runtime: CrossfadePromotionRuntime<ExoPlayer>

    init {
        fun store(name: String) = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + Job()),
            produceFile = { File.createTempFile(name, ".preferences_pb").also { f -> f.delete(); f.deleteOnExit() } },
        )
        sessionRepo = PlaybackSessionRepository(store("sessionstore"))
        pc = PlayerController(
            context,
            StatsTracker(Writer()).also { it.scope = CoroutineScope(Dispatchers.Unconfined); it.clock = { 0L } },
            sessionRepo, ResumeBehaviorSettingsRepository(store("resumestore")), AppSettingsRepository(store("appstore")),
        )
        driver = NextSlotPreparationDriver(
            preparation = engine.nextPreparation,
            snapshotProvider = { pc.captureCrossfadeRuntimeSnapshot() },
            materialize = { songs -> materializedForCrossfade += songs.size; pc.materializePlaybackMediaItemsForCrossfade(songs) },
            configuredDurationMsProvider = { 6_000L },
            currentDurationMsProvider = { engine.currentPlayer.duration.takeIf { it > 0L } },
            scheduler = NoopScheduler(),
        )
        runtime = CrossfadePromotionRuntime(
            engine = engine,
            snapshotProvider = { pc.captureCrossfadeRuntimeSnapshot() },
            configuredDurationMsProvider = { 6_000L },
            scheduler = NoopScheduler(),
            clock = { now },
        )
        val hints = Bundle().apply { putBoolean(ExternalTransportPolicy.APP_CONTROLLER_HINT, true) }
        val future = MediaController.Builder(context, session.token).setConnectionHints(hints).buildAsync()
        var guard = 0
        while (!future.isDone && guard++ < 200) idleMainLooper()
        controller = future.get(5, TimeUnit.SECONDS)
        pc.boundedInstallEnabledForTesting = bounded
        pc.attachPhysicalWindowPort(port)
        engine.facade.bindLogicalQueue(pc.logicalQueuePresentation, pc.logicalQueueCommands)
        pc.setLogicalPresentationInvalidator { engine.facade.invalidateLogicalState() }
        // The service's explicit-cancellation wiring (PlaybackService.onCreate), so user intent cancels NEXT exactly as in production.
        val sink = CrossfadeCancelSink { reason -> driver.cancel(reason); runtime.cancel(reason) }
        pc.setExplicitSeekListener { recoverCrossfadeFromExplicitSeek(sink) }
        pc.setExplicitNavigationListener { recoverCrossfadeFromExplicitNavigation(sink) }
        pc.setExplicitRepeatChangeListener { recoverCrossfadeFromRepeatChange(sink) }
        pc.setExplicitShuffleChangeListener { recoverCrossfadeFromShuffleChange(sink) }
        pc.setExplicitPlayNextMutationListener { recoverCrossfadeFromPlayNextMutation(sink) }
        pc.setExplicitAddToQueueMutationListener { recoverCrossfadeFromAddToQueueMutation(sink) }
        pc.setExplicitQueueReorderListener { recoverCrossfadeFromQueueReorder(sink) }
        pc.setExplicitQueueRemovalListener { recoverCrossfadeFromQueueRemoval(sink) }
        pc.setExplicitLibraryDeletionListener { recoverCrossfadeFromLibraryDeletion(sink) }
        pc.setExplicitQueueReplacementListener { recoverCrossfadeFromQueueReplacement(sink) }
        pc.setControllerDisconnectedListener { recoverCrossfadeFromControllerDisconnected(sink) }
        connectAppController()
    }

    fun connectAppController() {
        PlayerController::class.java.getDeclaredField("controllerConnectionState").apply { isAccessible = true }
            .set(pc, ControllerConnectionState.Connected)
        PlayerController::class.java.getDeclaredMethod("onControllerConnected", MediaController::class.java)
            .apply { isAccessible = true }.invoke(pc, controller)
        TestMediaSource.settle(100)
    }

    val adapter: PhysicalWindowAdapter
        get() = PlayerController::class.java.getDeclaredField("physicalWindowAdapter").apply { isAccessible = true }.get(pc) as PhysicalWindowAdapter

    fun quiesce() { TestMediaSource.awaitCondition { !adapter.isBusy }; idleMainLooper(); TestMediaSource.settle(60) }

    fun externalController(): MediaController {
        val future = MediaController.Builder(context, session.token).setConnectionHints(Bundle()).buildAsync()
        var guard = 0
        while (!future.isDone && guard++ < 200) idleMainLooper()
        return future.get(5, TimeUnit.SECONDS).also { extra += it; idleMainLooper(); TestMediaSource.settle(60) }
    }

    /** Starts the queue and waits until CURRENT is playing. */
    fun start(queue: List<Song>, index: Int) {
        pc.playFromQueue(queue, index)
        quiesce()
        TestMediaSource.awaitCondition { engine.currentPlayer.playbackState == Player.STATE_READY && engine.currentPlayer.playWhenReady }
    }

    fun prepareNext(): NextSlotState.Ready {
        driver.evaluate()
        TestMediaSource.awaitCondition { engine.nextPreparation.state is NextSlotState.Ready }
        return engine.nextPreparation.state as NextSlotState.Ready
    }

    /** Makes the fade window due on CURRENT and lets the runtime promote; then completes the overlap on the fake clock. */
    fun promoteAndSettle(): PromotionOutcome? {
        val current = engine.currentPlayer
        val duration = current.duration
        current.seekTo(current.currentMediaItemIndex, duration - 6_500L)
        TestMediaSource.settle(60)
        runtime.evaluate()
        if (runtime.state is PromotionOverlapState.Overlap) {
            now += 10_000L
            runtime.tick()
        }
        TestMediaSource.settle(120)
        quiesce()
        return runtime.lastOutcome
    }

    fun ids(p: Player) = (0 until p.mediaItemCount).map { p.getMediaItemAt(it).mediaId }

    fun release() {
        runCatching { runtime.close() }; runCatching { driver.close() }
        extra.forEach { runCatching { it.release() } }
        runCatching { controller.release() }; runCatching { pc.release() }
        runCatching { session.release() }; runCatching { engine.release() }
    }

    companion object { private var counter = 0 }
}
