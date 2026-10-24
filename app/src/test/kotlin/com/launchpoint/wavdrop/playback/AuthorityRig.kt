package com.launchpoint.wavdrop.playback

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import com.google.common.util.concurrent.SettableFuture
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.playback.PlaybackSessionRepository
import com.launchpoint.wavdrop.data.playback.PlaybackSessionSnapshot
import com.launchpoint.wavdrop.data.repository.PlayEventWriter
import com.launchpoint.wavdrop.data.settings.AppSettingsRepository
import com.launchpoint.wavdrop.data.settings.HeadphoneResumeMode
import com.launchpoint.wavdrop.data.settings.ResumeBehaviorSettingsRepository
import java.io.File
import java.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * Slice-3 test rig: the PRODUCTION [PlayerController] and the PRODUCTION [PreviousBehaviorPlayer] over a genuine ExoPlayer, wired
 * exactly like the service wires them (shared [ColdPlayAuthority], the same external-transport callbacks), behind a genuine
 * MediaSession. Two kinds of genuine MediaController connect to it: the APP controller (hint-marked, what PlayerController owns)
 * and EXTERNAL controllers (unmarked: widget, notification, media keys), so PLAY/PAUSE reach the session player through the same
 * `controllerForCurrentRequest` classification as in production. Only the controller *connection attempt* is a [SettableFuture].
 *
 * [recording] sits BELOW the session player, so its call log is the truth of which Media3 commands the physical player received.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal class AuthorityRig(sessionName: String) {

    private class Writer : PlayEventWriter {
        override suspend fun recordPlay(songId: Long, contentUri: String, listenedMs: Long, durationMs: Long) = Unit
        override suspend fun recordSkip(songId: Long, contentUri: String, durationMs: Long) = Unit
        override suspend fun recordListenStart(songId: Long, contentUri: String) = Unit
    }

    val context: Context = RuntimeEnvironment.getApplication()
    val queue: List<Song> = (1L..5L).map(RealMedia3QueueRig::song)
    val physical: ExoPlayer = TestMediaSource.newPlayer(context)
    val recording = RecordingPlayer(physical)

    private fun store(name: String) = PreferenceDataStoreFactory.create(
        scope = CoroutineScope(Dispatchers.IO + Job()),
        produceFile = { File.createTempFile(name, ".preferences_pb").also { f -> f.delete(); f.deleteOnExit() } },
    )

    val sessionRepo = PlaybackSessionRepository(store("sessionstore"))
    val resumeRepo = ResumeBehaviorSettingsRepository(store("resumestore"))
    val tracker = StatsTracker(Writer()).also { it.scope = CoroutineScope(Dispatchers.Unconfined) }
    val pc = PlayerController(context, tracker, sessionRepo, resumeRepo, AppSettingsRepository(store("appstore")))

    /** Opened to release the session player's songs lookup / hydration (null = ungated). */
    @Volatile var songsGate: CompletableDeferred<Unit>? = null
    @Volatile var hydrateGate: CompletableDeferred<Unit>? = null
    /** Suspends the cold PLAY AFTER its own hydration finished, before it re-authorizes. */
    @Volatile var afterHydrateGate: CompletableDeferred<Unit>? = null
    var hydrationCalls = 0

    val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var session: MediaSession? = null
    val sessionPlayer = PreviousBehaviorPlayer(
        player = recording,
        thresholdProvider = { 3_000L },
        scope = sessionScope,
        onExternalTransport = { pc.onExplicitExternalTransport() },
        hydrateForPlay = { songs ->
            hydrateGate?.await()
            hydrationCalls++
            pc.ensurePlayerHydratedFromSession(availableSongs = songs, operation = "explicit_play")
                .also { afterHydrateGate?.await() }
        },
        songsProvider = { songsGate?.await(); queue },
        logResume = {},
        sessionProvider = { session },
        onExplicitPause = {},
        onExplicitSeek = {},
        onExplicitNavigation = {},
        onExplicitRepeatChange = {},
        coldPlayAuthority = pc.coldPlayAuthority,
        onExternalPlayIntent = { play -> pc.onExplicitExternalPlayIntent(play) },
    )

    /** One entry per connection attempt PlayerController started, in order. */
    val attempts = mutableListOf<SettableFuture<MediaController>>()
    private val controllers = mutableListOf<MediaController>()
    @Volatile var wiredConnected = true
    @Volatile var bluetoothConnected = true

    init {
        session = MediaSession.Builder(context, sessionPlayer)
            .setId("$sessionName-${counter++}")
            // The production callback delegates to exactly this handler (PlaybackService.onMediaButtonEvent).
            .setCallback(object : MediaSession.Callback {
                override fun onMediaButtonEvent(session: MediaSession, controllerInfo: MediaSession.ControllerInfo, intent: Intent): Boolean =
                    ColdPlayMediaButton.handle(
                        intent = intent,
                        authority = pc.coldPlayAuthority,
                        onExplicitTransport = { pc.onExplicitExternalTransport() },
                        pause = { session.player.pause() },
                    )
            })
            .build()
        // The constructor posted one connection request. Neutralise it, then install the deterministic attempt factory.
        field("controllerConnectionState").set(pc, ControllerConnectionState.Connected)
        settle(50)
        pc.controllerFutureFactory = { SettableFuture.create<MediaController>().also { attempts += it } }
        field("controllerConnectionState").set(pc, ControllerConnectionState.Disconnected)
        pc.wiredOutputConnectedQuery = { wiredConnected }
        pc.bluetoothOutputConnectedQuery = { bluetoothConnected }
    }

    fun field(name: String) = PlayerController::class.java.getDeclaredField(name).apply { isAccessible = true }

    private fun build(hints: Bundle): MediaController {
        val future = MediaController.Builder(context, session!!.token).setConnectionHints(hints).buildAsync()
        var guard = 0
        while (!future.isDone && guard++ < 200) settle(20)
        return future.get().also { controllers += it }
    }

    /** A controller the way PlayerController builds its own: hint-marked, so its calls are NOT external user transport. */
    fun newAppController(): MediaController =
        build(Bundle().apply { putBoolean(ExternalTransportPolicy.APP_CONTROLLER_HINT, true) })

    /** Widget / notification / media key: an unmarked controller, so its PLAY/PAUSE is explicit external user transport. */
    fun newExternalController(): MediaController = build(Bundle())

    /** Completes the newest outstanding attempt with a fresh app controller (the demand is posted to the main looper first). */
    fun connectApp(attempt: Int? = null, settleMs: Long = 300): MediaController {
        settle(50)
        val controller = newAppController()
        attempts[attempt ?: attempts.lastIndex].set(controller)
        settle(settleMs)
        return controller
    }

    /** Makes PlayerController demand a controller (as any service-driven action would) without queuing a command. */
    fun demandController() {
        PlayerController::class.java.getDeclaredMethod("ensureControllerConnection").apply { isAccessible = true }.invoke(pc)
        settle(50)
    }

    /**
     * The notification PLAY_PAUSE button exactly as Media3 delivers it: the intent is built by Media3's own
     * `PlaybackPendingIntentBuilder` for COMMAND_PLAY_PAUSE (what DefaultMediaNotificationProvider's button carries), and
     * handed to `MediaSessionImpl.onMediaButtonEvent` the way `MediaSessionService.onStartCommand` does (notification
     * controller, or the fallback caller it creates). Returns the handler's verdict.
     */
    fun deliverNotificationPlayPause(): Boolean {
        val s = session!!
        val intent = androidx.media3.session.PlaybackPendingIntentBuilder.createMediaButtonIntent(
            context, androidx.media3.common.Player.COMMAND_PLAY_PAUSE, Bundle.EMPTY, s.id, PlaybackService::class.java,
        )
        val impl = MediaSession::class.java.getDeclaredMethod("getImpl").apply { isAccessible = true }.invoke(s)!!
        val implClass = Class.forName("androidx.media3.session.MediaSessionImpl")
        val caller = implClass.getDeclaredMethod("getMediaNotificationControllerInfo").apply { isAccessible = true }.invoke(impl)
            ?: androidx.media3.session.MediaSessionService::class.java
                .getDeclaredMethod("createFallbackMediaButtonCaller", Intent::class.java)
                .apply { isAccessible = true }.invoke(null, intent)
        val handled = implClass.getDeclaredMethod("onMediaButtonEvent", MediaSession.ControllerInfo::class.java, Intent::class.java)
            .apply { isAccessible = true }.invoke(impl, caller, intent) as Boolean
        settle(150)
        return handled
    }

    fun disconnectApp(controller: MediaController) {
        (field("controllerLifecycleListener").get(pc) as MediaController.Listener).onDisconnected(controller)
        settle(50)
    }

    val appController: MediaController? get() = field("mediaController").get(pc) as MediaController?

    /**
     * Parks the current item 5 ms before its end (without a command that reaches the session player). Playing it ends the
     * item at once, so a controller then observes playWhenReady=true with isPlaying=false: the 700 ms retry condition.
     */
    fun seekNearEnd() {
        physical.seekTo(physical.mediaItemCount - 1, TestMediaSource.DURATION_US / 1_000L - 5L)
        settle(250)
        clearCalls()
    }

    // ---------------------------------------------------------------------------------------------- time

    /** Real-time settle: lets the playback thread and IO datastore threads run, without advancing virtual time. */
    fun settle(ms: Long = 200) = TestMediaSource.settle(ms)

    /** Advances the (virtual) main-looper clock by [virtualMs] in small slices, giving real threads a moment each slice. */
    fun advance(virtualMs: Long) {
        var left = virtualMs
        while (left > 0L) {
            val slice = minOf(left, 50L)
            Thread.sleep(8) // real time for the IO datastore threads to complete between virtual slices
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(slice))
            left -= slice
        }
        settle(150)
    }

    // ---------------------------------------------------------------------------------------------- state

    fun count(prefix: String) = recording.count(prefix)
    fun clearCalls() = recording.calls.clear()
    val physicalIds: List<String> get() = (0 until physical.mediaItemCount).map { physical.getMediaItemAt(it).mediaId }
    val currentMediaId: String? get() = physical.currentMediaItem?.mediaId
    val logical get() = pc.nowPlayingState.value

    fun saveSession(currentIndex: Int = 2) = runBlocking {
        sessionRepo.save(
            PlaybackSessionSnapshot(
                queueSongIds = queue.map { it.id },
                currentSongId = queue[currentIndex].id,
                currentIndex = currentIndex,
                positionMs = 0L,
                repeatMode = RepeatMode.OFF,
                shuffleEnabled = false,
                updatedAtMs = 1L,
            ),
        )
    }

    fun setWiredMode(mode: HeadphoneResumeMode) = runBlocking { resumeRepo.setWiredResumeMode(mode) }
    fun setBluetoothMode(mode: HeadphoneResumeMode) = runBlocking { resumeRepo.setBluetoothResumeMode(mode) }
    fun setRememberLastTrack(enabled: Boolean) = runBlocking { resumeRepo.setRememberLastTrack(enabled) }
    fun setWiredPending(pending: Boolean) = runBlocking { resumeRepo.setWiredInterruptedResumePending(pending) }
    fun setBluetoothPending(pending: Boolean) = runBlocking { resumeRepo.setBluetoothInterruptedResumePending(pending) }
    fun wiredPending(): Boolean = runBlocking { resumeRepo.hasWiredInterruptedResumePending() }
    fun bluetoothPending(): Boolean = runBlocking { resumeRepo.hasBluetoothInterruptedResumePending() }

    /**
     * An installed, connected, PAUSED session: a fresh start is accepted, the app controller connects and installs it, then
     * the user pauses. Leaves the call log empty so assertions see only what happens afterwards.
     */
    fun loadPausedSession(
        startIndex: Int = 1,
        songs: List<Song> = queue,
        interruptedByWired: Boolean = false,
        interruptedByBluetooth: Boolean = false,
    ): MediaController {
        pc.playFromQueue(songs, startIndex = startIndex)
        settle(50)
        val controller = connectApp()
        settle(300)
        // The device is removed while playing: records the interruption entitlement (in memory + persisted).
        if (interruptedByWired) pc.onWiredDeviceRemoved()
        if (interruptedByBluetooth) pc.onBluetoothDeviceRemoved()
        settle(200)
        pc.togglePlayPause() // playing -> paused (an app-controller user pause)
        settle(300)
        clearCalls()
        return controller
    }

    fun releaseAll() {
        runCatching { pc.release() }
        runCatching { sessionScope.cancel() }
        controllers.forEach { runCatching { it.release() } }
        runCatching { session?.release() }
        runCatching { physical.release() }
    }

    private companion object {
        var counter = 0
    }
}
