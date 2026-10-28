package com.launchpoint.wavdrop.playback

import android.content.Context
import android.os.Bundle
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.media3.common.AudioAttributes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.playback.PlaybackSessionRepository
import com.launchpoint.wavdrop.data.playback.PlaybackSessionSnapshot
import com.launchpoint.wavdrop.data.repository.PlayEventWriter
import com.launchpoint.wavdrop.data.settings.AppSettingsRepository
import com.launchpoint.wavdrop.data.settings.ResumeBehaviorSettings
import com.launchpoint.wavdrop.data.settings.ResumeBehaviorSettingsRepository
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.robolectric.RuntimeEnvironment

/**
 * The cold-resumption topology of production, for the F5-1 command-ordering tests: two real ExoPlayers behind [PlayerEngine] and the
 * session [SessionFacade] (the logical queue controllers see), the PRODUCTION [PreviousBehaviorPlayer] as the session player, the PRODUCTION
 * [PlayerController] (sharing its [ColdPlayAuthority]), and a REAL [MediaSession] whose `onPlaybackResumption` is the PRODUCTION
 * [PlaybackResumptionHandler] (the logic PlaybackService delegates to). Commands come from a genuine unmarked MediaController, i.e. through
 * Media3's own `handleMediaControllerPlayRequest`, so the PLAY that follows a resumption is issued by Media3 itself, not re-implemented here.
 *
 * Gates ([snapshotGate], [songsGate]) hold the saved-session load so a newer command can arrive while the restoration is unfinished, with no
 * timing assumptions. [resumptionRoute] = false makes `onPlaybackResumption` fail (Media3's default for a session that does not implement
 * it), which leaves the explicit-PLAY hydration route ([PreviousBehaviorPlayer.play] on an empty player) as the only restorer.
 * Bounded is enabled for the test only; the committed gate stays false. Codec-free: no audio claim.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal class ResumptionOrderRig(bounded: Boolean = true, private val resumptionRoute: Boolean = true) {

    private class Writer : PlayEventWriter {
        override suspend fun recordPlay(songId: Long, contentUri: String, listenedMs: Long, durationMs: Long) = Unit
        override suspend fun recordSkip(songId: Long, contentUri: String, durationMs: Long) = Unit
        override suspend fun recordListenStart(songId: Long, contentUri: String) = Unit
    }

    private fun store(name: String) = PreferenceDataStoreFactory.create(
        scope = CoroutineScope(Dispatchers.IO + Job()),
        produceFile = { File.createTempFile(name, ".preferences_pb").also { f -> f.delete(); f.deleteOnExit() } },
    )

    val context: Context = RuntimeEnvironment.getApplication()
    private val sessionId = 4343
    val p1: ExoPlayer = TestMediaSource.newPlayer(context, sessionId)
    val p2: ExoPlayer = TestMediaSource.newPlayer(context, sessionId)
    val engine = PlayerEngine(context, p1, p2, sessionId, AudioAttributes.DEFAULT)
    /** Sits below the session player: its call log is the truth of which commands reached the logical (façade) player. */
    val recording = RecordingPlayer(engine.facade)

    val sessionRepo = PlaybackSessionRepository(store("sessionstore"))
    val pc = PlayerController(
        context,
        StatsTracker(Writer()).also { it.scope = CoroutineScope(Dispatchers.Unconfined); it.clock = { 0L } },
        sessionRepo, ResumeBehaviorSettingsRepository(store("resumestore")), AppSettingsRepository(store("appstore")),
    )

    /** Holds the saved-snapshot load (null = ungated). */
    @Volatile var snapshotGate: CompletableDeferred<Unit>? = null
    /** Holds the songs lookup of either route (null = ungated). */
    @Volatile var songsGate: CompletableDeferred<Unit>? = null
    /** Runs on the main thread immediately before a resumption is adopted (where the crossfade is cancelled): a hook for a command that lands there. */
    @Volatile var beforeAdoption: () -> Unit = {}
    var library: List<Song> = emptyList()

    /** `PlaybackResumptionHandler` and `PreviousBehaviorPlayer` log lines, in order. */
    val log = CopyOnWriteArrayList<String>()
    /** `ValidationTrace` lines: window_submitted / window_verified ... */
    val trace = CopyOnWriteArrayList<String>()

    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var session: MediaSession
    private val extra = mutableListOf<MediaController>()
    val appController: MediaController

    internal val sessionPlayer = PreviousBehaviorPlayer(
        player = recording,
        thresholdProvider = { 3_000L },
        scope = sessionScope,
        onExternalTransport = { pc.onExplicitExternalTransport() },
        hydrateForPlay = { songs -> pc.ensurePlayerHydratedFromSession(availableSongs = songs, operation = "explicit_play") },
        songsProvider = { songsGate?.await(); library },
        logResume = { log += it },
        sessionProvider = { session },
        onExplicitPause = {},
        onExplicitSeek = {},
        onExplicitNavigation = {},
        onExplicitRepeatChange = {},
        coldPlayAuthority = pc.coldPlayAuthority,
        onExternalPlayIntent = { play -> pc.onExplicitExternalPlayIntent(play) },
        selectionPlayArmed = { pc.isSelectionPlayArmed() },
    )

    /** The PRODUCTION handler, wired exactly as PlaybackService wires it. */
    fun newHandler() = PlaybackResumptionHandler(
        scope = sessionScope,
        loadSnapshot = { snapshotGate?.await(); sessionRepo.load() },
        loadSettings = { ResumeBehaviorSettings() },
        loadSongs = { songsGate?.await(); library },
        playerController = pc,
        cancelCrossfade = { beforeAdoption() },
        log = { log += it },
        coldPlay = pc.coldPlayAuthority,
    )

    init {
        session = MediaSession.Builder(context, sessionPlayer)
            .setId("ord-${counter++}")
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
                    PlaybackService.withWavdropCommands(session, controller)

                override fun onPlaybackResumption(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    isForPlayback: Boolean,
                ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
                    if (!resumptionRoute) Futures.immediateFailedFuture(UnsupportedOperationException("resumption not implemented"))
                    else newHandler().resume(isForPlayback) { plan ->
                        mediaSession.player.repeatMode = plan.repeatMode.toPlayerRepeatMode()
                        mediaSession.player.shuffleModeEnabled = false
                    }
            })
            .build()
        val hints = Bundle().apply { putBoolean(ExternalTransportPolicy.APP_CONTROLLER_HINT, true) }
        val future = MediaController.Builder(context, session.token).setConnectionHints(hints).buildAsync()
        var guard = 0
        while (!future.isDone && guard++ < 200) idleMainLooper()
        appController = future.get(5, TimeUnit.SECONDS)
        pc.boundedInstallEnabledForTesting = bounded
        pc.attachPhysicalWindowPort(EnginePhysicalPort(engine))
        engine.facade.bindLogicalQueue(pc.logicalQueuePresentation, pc.logicalQueueCommands)
        pc.setLogicalPresentationInvalidator { engine.facade.invalidateLogicalState() }
        connectAppController()
        ValidationTrace.enabled = true
        ValidationTrace.sink = { trace += it }
    }

    /**
     * One `onPlaybackResumption(isForPlayback = true)` request driven EXACTLY the way `MediaSessionImpl` drives it: the production handler's
     * future gets Media3's callback through `Futures.addCallback(future, callback, executor)`, and the callback does what
     * `MediaSessionImpl$2` does — apply the result with `MediaUtils.setMediaItemsWithStartIndexAndPosition`, then `Util.handlePlayButtonAction`
     * on the session player (on failure: only the PLAY). The only difference is the executor: Media3's is `Util.postOrRun(applicationHandler)`,
     * which runs inline when the completing thread is the application looper and posts otherwise; here the callback runs only when
     * [deliver] is called, so a test can hold it, delay it, reorder it between requests, or never deliver it.
     */
    inner class Media3Request {
        private val queued = java.util.ArrayDeque<Runnable>()
        val future: ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
            newHandler().resume(isForPlayback = true) { plan ->
                sessionPlayer.repeatMode = plan.repeatMode.toPlayerRepeatMode()
                sessionPlayer.shuffleModeEnabled = false
            }

        init {
            com.google.common.util.concurrent.Futures.addCallback(
                future,
                object : com.google.common.util.concurrent.FutureCallback<MediaSession.MediaItemsWithStartPosition> {
                    override fun onSuccess(result: MediaSession.MediaItemsWithStartPosition) {
                        Class.forName("androidx.media3.session.MediaUtils")
                            .getDeclaredMethod("setMediaItemsWithStartIndexAndPosition", androidx.media3.common.Player::class.java, MediaSession.MediaItemsWithStartPosition::class.java)
                            .apply { isAccessible = true }.invoke(null, sessionPlayer, result)
                        androidx.media3.common.util.Util.handlePlayButtonAction(sessionPlayer)
                    }
                    override fun onFailure(t: Throwable) { androidx.media3.common.util.Util.handlePlayButtonAction(sessionPlayer) }
                },
                java.util.concurrent.Executor { queued.addLast(it) },
            )
        }

        /** The handler finished (restored, dropped, unavailable or failed); Media3's callback has NOT run yet. */
        fun awaitCompletion() = TestMediaSource.awaitCondition { future.isDone }
        val callbackPending: Boolean get() = queued.isNotEmpty()

        /** Media3's callback finally runs. Returns how many PLAYs it let through to the physical-side player. */
        fun deliver(): Int {
            val before = recording.count("play")
            queued.removeFirst().run()
            idleMainLooper()
            return recording.count("play") - before
        }
    }

    private fun connectAppController() {
        PlayerController::class.java.getDeclaredField("controllerConnectionState").apply { isAccessible = true }
            .set(pc, ControllerConnectionState.Connected)
        PlayerController::class.java.getDeclaredMethod("onControllerConnected", MediaController::class.java)
            .apply { isAccessible = true }.invoke(pc, appController)
        TestMediaSource.settle(100)
    }

    val adapter: PhysicalWindowAdapter
        get() = PlayerController::class.java.getDeclaredField("physicalWindowAdapter").apply { isAccessible = true }.get(pc) as PhysicalWindowAdapter

    /** A genuine UNMARKED controller: its PLAY/PAUSE is explicit external user transport (notification, lock screen, media keys). */
    fun externalController(): MediaController {
        val future = MediaController.Builder(context, session.token).setConnectionHints(Bundle()).buildAsync()
        var guard = 0
        while (!future.isDone && guard++ < 200) idleMainLooper()
        return future.get(5, TimeUnit.SECONDS).also { extra += it; idleMainLooper(); TestMediaSource.settle(60) }
    }

    // ---------------------------------------------------------------------------------------------- saved session

    fun songs(n: Int): List<Song> = List(n) { RealMedia3QueueRig.song(it + 1L) }

    /** Saves a session whose queue is [queueIds] (ids may repeat: duplicate occurrences) and loads [library] as the song library. */
    fun saveSession(queueIds: List<Long>, currentIndex: Int, positionMs: Long = 0L, repeat: RepeatMode = RepeatMode.OFF) = runBlocking {
        sessionRepo.save(
            PlaybackSessionSnapshot(
                queueSongIds = queueIds, playbackOrder = null, currentSongId = queueIds[currentIndex], currentIndex = currentIndex,
                positionMs = positionMs, repeatMode = repeat, shuffleEnabled = false, updatedAtMs = 1L,
            ),
        )
    }

    /** n unique songs, all of them the saved queue in order, current at [current]. */
    fun saveSimpleSession(n: Int, current: Int, positionMs: Long = 0L) {
        library = songs(n)
        saveSession(List(n) { it + 1L }, current, positionMs)
    }

    // ---------------------------------------------------------------------------------------------- synchronisation (no sleeps)

    fun awaitInvoked(count: Int) = TestMediaSource.awaitCondition { log.count { it.startsWith("onPlaybackResumption invoked") } >= count }

    /** A resumption request ended: adopted, dropped by a newer queue authority, unavailable or failed. */
    fun completions(): Int = log.count {
        it.startsWith("onPlaybackResumption adopted") || it.startsWith("onPlaybackResumption superseded") ||
            it.startsWith("onPlaybackResumption unavailable") || it.startsWith("onPlaybackResumption failed")
    }
    fun awaitCompleted(count: Int) = TestMediaSource.awaitCondition { completions() >= count }

    fun quiesce() { TestMediaSource.awaitCondition { !adapter.isBusy }; idleMainLooper(); TestMediaSource.settle(60) }

    // ---------------------------------------------------------------------------------------------- observations

    val playWhenReady: Boolean get() = engine.currentPlayer.playWhenReady
    val currentMediaId: String? get() = engine.currentPlayer.currentMediaItem?.mediaId
    val logical get() = pc.nowPlayingState.value
    fun windowInstalls(): Int = trace.count { it.startsWith("window_submitted") && it.contains("kind=install") }
    fun windowVerified(): Int = trace.count { it.startsWith("window_verified") }
    fun windowRejections(): Int = trace.count { it.startsWith("window_not_verified") }
    fun suppressed(): Int = log.count { it.startsWith("resumption PLAY suppressed") }

    fun release() {
        runCatching { pc.release() }
        runCatching { sessionScope.cancel() }
        extra.forEach { runCatching { it.release() } }
        runCatching { appController.release() }
        runCatching { session.release() }
        runCatching { engine.release() }
        ValidationTrace.enabled = com.launchpoint.wavdrop.BuildConfig.DEBUG
        ValidationTrace.sink = { android.util.Log.d(ValidationTrace.TAG, it) }
    }

    private companion object { var counter = 0 }
}
