package com.launchpoint.wavdrop.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.launchpoint.wavdrop.BuildConfig
import com.launchpoint.wavdrop.MainActivity
import com.launchpoint.wavdrop.R
import com.launchpoint.wavdrop.data.playback.PlaybackSessionRepository
import com.launchpoint.wavdrop.data.repository.SongRepository
import com.launchpoint.wavdrop.data.settings.AppSettingsRepository
import com.launchpoint.wavdrop.data.settings.AudioEnhancementsRepository
import com.launchpoint.wavdrop.data.settings.NotificationControlsSetting
import com.launchpoint.wavdrop.data.settings.PreviousButtonBehavior
import com.launchpoint.wavdrop.data.settings.ResumeBehaviorSettingsRepository
import com.launchpoint.wavdrop.ui.widget.WidgetPlaybackSnapshot
import com.launchpoint.wavdrop.ui.widget.WidgetStateStore
import com.launchpoint.wavdrop.ui.widget.WavdropWidgetUpdater
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Owns the ExoPlayer instance and its MediaSession.
 * The player lives here so playback survives UI navigation.
 * Clients connect via MediaController (see PlayerController).
 */
@AndroidEntryPoint
class PlaybackService : MediaLibraryService() {

    @Inject lateinit var resumeBehaviorRepository: ResumeBehaviorSettingsRepository
    @Inject lateinit var sessionRepository: PlaybackSessionRepository
    @Inject lateinit var appSettingsRepository: AppSettingsRepository
    @Inject lateinit var audioEnhancementsRepository: AudioEnhancementsRepository
    @Inject lateinit var playerController: PlayerController
    @Inject lateinit var songRepository: SongRepository
    @Inject lateinit var widgetStateStore: WidgetStateStore

    private var mediaSession: MediaLibrarySession? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var enhancementController: AudioEnhancementController? = null
    // CF-2B2/2B3/2E1: only ever constructed behind CrossfadeRolloutPolicy.RUNTIME_ENABLED (false). Owns the silent
    // secondary player (single release owner); not a session player. The timing driver below is built over this
    // exact runtime but is never started (dormant).
    private var playbackAssembly: PlaybackAssembly? = null
    private var crossfadePreparation: CrossfadePreparationRuntime? = null
    private var crossfadeTimingDriver: CrossfadeTimingDriver? = null
    // CF-2M4: gate true only. Decides when the engine NEXT slot is prepared; never starts B. Null with the gate false.
    private var nextSlotDriver: NextSlotPreparationDriver? = null
    // CF-2M5: gate true only. Promotes the Ready NEXT at the fade window and runs the overlap. Null with the gate false.
    private var promotionRuntime: CrossfadePromotionRuntime<ExoPlayer>? = null

    // CF-2M4: the ONE explicit-cancellation lifecycle point. Every recoverCrossfadeFrom... call goes through it and it ends
    // whichever owner exists (the legacy CF-2L runtime, never built now, and the engine NEXT preparation).
    private val crossfadeCancelSink = CrossfadeCancelSink { reason ->
        crossfadePreparation?.cancel(reason)
        nextSlotDriver?.cancel(reason)
        promotionRuntime?.cancel(reason)
    }
    // CF-2E2: main-thread cached, already-normalized persisted duration read synchronously by the driver provider.
    private var crossfadeConfiguredDurationMs: Long = CrossfadeRules.OFF_MS
    // CF-2I2: cached EQ-enabled compatibility fact for the crossfade snapshot (conservative default = persisted default).
    private var crossfadeEqualizerEnabled: Boolean = false
    private var lastObservedCrossfadeDurationMs: Long? = null
    private var previousRestartThresholdMs: Long =
        PreviousButtonBehavior.DEFAULT.previousRestartThresholdMs()

    // Fires when audio devices are added or removed. Runs on the main thread (null handler).
    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
            val hasBluetooth = addedDevices.any { it.isSink && BluetoothAudioDetector.isBluetoothAudioType(it.type) }
            val hasWired = addedDevices.any { it.isSink && WiredHeadphoneDetector.isWiredOutputType(it.type) }
            logResume(
                "audioDeviceCallback.onAudioDevicesAdded: count=${addedDevices.size} " +
                    "hasBluetooth=$hasBluetooth hasWired=$hasWired " +
                    "sinkTypes=${addedDevices.filter { it.isSink }.map { it.type }}",
            )
            if (!hasBluetooth && !hasWired) return
            serviceScope.launch {
                val songs = songRepository.songs.first()
                logResume("audioDeviceCallback: got ${songs.size} songs for resume")
                if (hasBluetooth) playerController.resumeForBluetooth(songs)
                if (hasWired) playerController.resumeForWiredHeadphones(songs)
            }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
            val hasBluetooth = removedDevices.any { it.isSink && BluetoothAudioDetector.isBluetoothAudioType(it.type) }
            val hasWired = removedDevices.any { it.isSink && WiredHeadphoneDetector.isWiredOutputType(it.type) }
            logResume(
                "audioDeviceCallback.onAudioDevicesRemoved: count=${removedDevices.size} " +
                    "hasBluetooth=$hasBluetooth hasWired=$hasWired",
            )
            if (hasBluetooth) playerController.onBluetoothDeviceRemoved()
            if (hasWired) playerController.onWiredDeviceRemoved()
        }
    }

    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    override fun onCreate() {
        super.onCreate()
        val notificationProvider = DefaultMediaNotificationProvider(this).apply {
            setSmallIcon(R.drawable.ic_stat_wavdrop)
        }
        setMediaNotificationProvider(notificationProvider)

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        // CF-2M3: gate false (shipping) = ONE physical ExoPlayer with its own focus + noisy handling, exactly as before.
        // Gate true = PlayerEngine: two physical slots, one logical focus owner, one noisy owner, one shared audio-session id,
        // one stable SessionFacade. The engine never prepares NEXT, swaps roles or promotes in this slice.
        val assembly = assemblePlayback(this, CrossfadeRolloutPolicy.RUNTIME_ENABLED, audioAttributes)
        playbackAssembly = assembly
        // CF-2M5: `player` is the INITIAL physical player (the only one when the gate is false). It must never be read where the
        // LOGICAL CURRENT is meant: after a promotion that physical may be the retiring/NEXT slot. Use assembly.currentPlayer.
        val player = assembly.primaryPlayer
        if (assembly.topology.constructsLegacyCrossfadeGraph) {
            // CF-2M3 coexistence (option A): the legacy CF-2L graph (and its secondary player) is never constructed in either
            // topology, so CURRENT + engine NEXT + a CF-2L secondary cannot coexist. CF-2M8 deletes this block.
            // CF-2E1/2E2: composition only. The graph does not start the driver; the persisted-duration observer below
            // (CF-2E2 activation policy) starts/stops it. Shipping stays inert because this whole block is gated off.
            val graph = createCrossfadeProductionGraph(
                snapshotProvider = { playerController.captureCrossfadeRuntimeSnapshot().copy(equalizerEnabled = crossfadeEqualizerEnabled) },
                backendFactory = { ExoSecondaryPlayerBackend(this, audioAttributes) },
                // Narrow local seam to the authoritative primary ExoPlayer; never routed through a controller/session.
                primaryGainBackend = PrimaryGainBackend { gain ->
                    try {
                        player.volume = gain
                        true
                    } catch (e: Exception) {
                        Log.w(AUDIO_SESSION_TAG, "primary crossfade gain write failed", e)
                        false
                    }
                },
                reconcilePrimary = { key, snapshot -> playerController.reconcileCrossfadePrimary(key, snapshot) },
                scheduler = MainLooperCrossfadeTimingScheduler(),
                clock = ElapsedRealtimeCrossfadeClock,
                configuredDurationMsProvider = { crossfadeConfiguredDurationMs },
                primaryDurationMs = { player.duration },
                primaryPositionMs = { player.currentPosition },
                // CF-2L1: natural-AUTO handoff seams. Real primary facts (never "a command returned") and a same-item reposition.
                primaryTakeoverFacts = {
                    PrimaryTakeoverFacts(
                        physicalIndex = player.currentMediaItemIndex,
                        isReady = player.playbackState == Player.STATE_READY,
                        positionMs = player.currentPosition,
                        // CF-2L4: projection may only bridge sampling granularity while the clock really advances.
                        isAdvancing = isPrimaryPlaybackAdvancing(player.playbackState, player.playWhenReady, player.playbackSuppressionReason),
                    )
                },
                reconcilePrimaryAfterNaturalTransition = { key, snapshot ->
                    playerController.reconcileCrossfadePrimaryAfterNaturalTransition(key, snapshot)
                },
                // CF-2L2: concise DEBUG-only transfer evidence (no file names or paths); null in release.
                naturalTransferLog = if (BuildConfig.DEBUG) { message -> Log.d("WavdropCrossfade", message) } else null,
                // CF-2I1: read-only session observability only; no EQ is attached to the secondary.
                primaryAudioSessionId = { player.audioSessionId },
                audioSessionObserver = CrossfadeAudioSessionObserver { key, primaryId, secondaryId ->
                    if (BuildConfig.DEBUG) Log.d(AUDIO_SESSION_TAG, formatCrossfadeAudioSessionObservation(key, primaryId, secondaryId))
                },
            )
            crossfadePreparation = graph.runtime
            crossfadeTimingDriver = graph.timingDriver
            // Intentionally no start() here: only the activation policy (initial-enabled / OFF->enabled) starts it.
        }
        assembly.engine?.let { engine ->
            // CF-2M4: NEXT preparation owner. Reuses the existing plan/key/ownership rules; started by the same persisted-duration
            // activation policy below. Nothing starts or promotes B.
            nextSlotDriver = NextSlotPreparationDriver(
                preparation = engine.nextPreparation,
                snapshotProvider = { playerController.captureCrossfadeRuntimeSnapshot().copy(equalizerEnabled = crossfadeEqualizerEnabled) },
                materialize = { songs -> playerController.materializePlaybackMediaItemsForCrossfade(songs) },
                configuredDurationMsProvider = { crossfadeConfiguredDurationMs },
                currentDurationMsProvider = { usableCrossfadePrimaryDuration(engine.currentPlayer.duration) },
                scheduler = MainLooperCrossfadeTimingScheduler(),
            )
            promotionRuntime = CrossfadePromotionRuntime(
                engine = engine,
                snapshotProvider = { playerController.captureCrossfadeRuntimeSnapshot().copy(equalizerEnabled = crossfadeEqualizerEnabled) },
                configuredDurationMsProvider = { crossfadeConfiguredDurationMs },
                scheduler = MainLooperCrossfadeTimingScheduler(),
                clock = ElapsedRealtimeCrossfadeClock,
                debugLog = if (BuildConfig.DEBUG) { message -> Log.d("WavdropCrossfade", message) } else null,
            )
        }
        // CF-2E2: persisted duration -> cached value + explicit driver lifecycle policy. A persisted value is NOT
        // rollout permission: with the hard gate false there is no graph, so this only updates the cache.
        serviceScope.launch {
            appSettingsRepository.crossfadeDurationMs
                .distinctUntilChanged()
                .collect { duration ->
                    val previous = lastObservedCrossfadeDurationMs
                    crossfadeConfiguredDurationMs = duration
                    if (CrossfadeRolloutPolicy.RUNTIME_ENABLED) {
                        applyCrossfadeConfiguredDurationChange(previous, duration, crossfadePreparation, crossfadeTimingDriver)
                        applyNextSlotConfiguredDurationChange(previous, duration, nextSlotDriver)
                        applyPromotionConfiguredDurationChange(previous, duration, promotionRuntime)
                    }
                    lastObservedCrossfadeDurationMs = duration
                }
        }
        // CF-2I2: one narrow collector of the persisted EQ-enabled flag. The cache is updated first; only OFF->ON cancels.
        serviceScope.launch {
            audioEnhancementsRepository.settings
                .map { it.eqEnabled }
                .distinctUntilChanged()
                .collect { enabled ->
                    val previous = crossfadeEqualizerEnabled
                    crossfadeEqualizerEnabled = enabled
                    if (shouldCancelCrossfadeForEqualizerChange(previous, enabled)) {
                        recoverCrossfadeFromEqualizerEnabled(crossfadeCancelSink)
                    }
                }
        }
        // CF-2G2: app UI position seeks notify this lifecycle-scoped callback (cleared in onDestroy).
        playerController.setExplicitSeekListener { recoverCrossfadeFromExplicitSeek(crossfadeCancelSink) }
        // CF-2G3: app skipToNext/skipToPrevious notify this lifecycle-scoped callback (cleared in onDestroy).
        playerController.setExplicitNavigationListener { recoverCrossfadeFromExplicitNavigation(crossfadeCancelSink) }
        // CF-2H1: app cycleRepeatMode notifies this lifecycle-scoped callback (cleared in onDestroy).
        playerController.setExplicitRepeatChangeListener { recoverCrossfadeFromRepeatChange(crossfadeCancelSink) }
        // CF-2H2: app toggleShuffle (UI and custom notification command) notifies this callback (cleared in onDestroy).
        playerController.setExplicitShuffleChangeListener { recoverCrossfadeFromShuffleChange(crossfadeCancelSink) }
        // CF-2H3A: explicit playNext/playAllNext/moveToPlayNext notify this callback (cleared in onDestroy).
        playerController.setExplicitPlayNextMutationListener { recoverCrossfadeFromPlayNextMutation(crossfadeCancelSink) }
        // CF-2H3B: explicit addToQueue/addAllToQueue notify this callback (cleared in onDestroy).
        playerController.setExplicitAddToQueueMutationListener { recoverCrossfadeFromAddToQueueMutation(crossfadeCancelSink) }
        // CF-2H3C: explicit moveQueueItemUp/Down/To notify this callback (cleared in onDestroy).
        playerController.setExplicitQueueReorderListener { recoverCrossfadeFromQueueReorder(crossfadeCancelSink) }
        // CF-2H3D: explicit removeFromQueue/clearEarlierQueue/clearUpNext notify this callback (cleared in onDestroy).
        playerController.setExplicitQueueRemovalListener { recoverCrossfadeFromQueueRemoval(crossfadeCancelSink) }
        // CF-2H3E: handleSongDeleted notifies this callback (cleared in onDestroy).
        playerController.setExplicitLibraryDeletionListener { recoverCrossfadeFromLibraryDeletion(crossfadeCancelSink) }
        // CF-2H3F: explicit whole-queue playback starts notify this callback (cleared in onDestroy).
        playerController.setExplicitQueueReplacementListener { recoverCrossfadeFromQueueReplacement(crossfadeCancelSink) }
        // CF-2F4: an authoritative MediaController disconnect notifies this callback (cleared in onDestroy).
        playerController.setControllerDisconnectedListener { recoverCrossfadeFromControllerDisconnected(crossfadeCancelSink) }
        // CF-2M2: physical ExoPlayer -> SessionFacade -> PreviousBehaviorPlayer -> MediaLibrarySession. The façade is bound
        // ONLY behind the single rollout gate (no second flag): with the gate false (shipping) the chain is exactly the
        // pre-CF-2M2 ExoPlayer -> PreviousBehaviorPlayer, so shipping behaviour and rollback surface are unchanged. The
        // façade never swaps delegates here (one physical player); its swap seam is exercised only by tests.
        val logicalPlayer: Player = assembly.logicalPlayer
        val sessionPlayer = PreviousBehaviorPlayer(
            player = logicalPlayer,
            thresholdProvider = { previousRestartThresholdMs },
            scope = serviceScope,
            onExternalTransport = { playerController.onExplicitExternalTransport() },
            hydrateForPlay = { songs ->
                playerController.ensurePlayerHydratedFromSession(availableSongs = songs, operation = "explicit_play")
            },
            songsProvider = { songRepository.songs.first() },
            logResume = ::logResume,
            sessionProvider = { mediaSession },
            onExplicitPause = { recoverCrossfadeFromExplicitPause(crossfadeCancelSink) },
            onExplicitSeek = { recoverCrossfadeFromExplicitSeek(crossfadeCancelSink) },
            onExplicitNavigation = { recoverCrossfadeFromExplicitNavigation(crossfadeCancelSink) },
            onExplicitRepeatChange = { recoverCrossfadeFromRepeatChange(crossfadeCancelSink) },
            // CF-2L3: DEBUG-only evidence of REAL transport commands (distinct from Media3 state changes caused by seeking/buffering).
            transportLog = if (BuildConfig.DEBUG) { message -> Log.d(CROSSFADE_TAG, message) } else null,
            crossfadeSummary = { formatCrossfadeSettlementSummary(crossfadePreparation?.settlementSnapshot()) },
        )

        if (BuildConfig.DEBUG) {
            Log.d(AUDIO_SESSION_TAG, "[init] player created audioSessionId=${player.audioSessionId} ts=${System.currentTimeMillis()}")
        }

        // CF-2M2 listener ownership. LOGICAL consumer (widget state): listens to the session-facing player (the facade when the
        // gate is true, otherwise the same physical player). It must never observe a physical player directly once promotion
        // exists. The PHYSICAL/crossfade observer below stays on the raw ExoPlayer: errors, READY/terminal state, the
        // authoritative AUTO transition fact for the crossfade owner, and raw audio-session callbacks.
        //
        // Widget state follows the player events directly (notification controls, lock-screen, Bluetooth buttons and widget
        // action intents all reach it) without the MediaController -> MediaSession IPC round-trip.
        logicalPlayer.addListener(
            WidgetPlaybackStateListener(
                player = logicalPlayer,
                scope = serviceScope,
                sink = object : WidgetStateSink {
                    override suspend fun save(snapshot: WidgetPlaybackSnapshot) = widgetStateStore.save(snapshot)
                    override suspend fun updateIsPlaying(isPlaying: Boolean) = widgetStateStore.updateIsPlaying(isPlaying)
                    override suspend fun clear() = widgetStateStore.clear()
                    override fun requestUpdate() = WavdropWidgetUpdater.requestUpdate(applicationContext)
                },
            ),
        )

        // PHYSICAL / crossfade observer (raw ExoPlayer only).
        assembly.addCurrentPlayerListener(object : Player.Listener {

            // DEBUG-only: previous media item index, for the WavdropGapless transition line.
            private var lastGaplessIndex = assembly.currentPlayer.currentMediaItemIndex

            // CF-2F1: the authoritative primary reported a real playback error. Synchronously cancel any owned
            // crossfade (key-less). PlayerController keeps owning bad-media queue recovery; the timing driver is
            // neither stopped nor restarted here.
            override fun onPlayerError(error: PlaybackException) {
                recoverCrossfadeFromPrimaryPlaybackError(crossfadeCancelSink)
            }

            // CF-2F5: Media3-authoritative interruption signals only (never generic isPlaying == false, never buffering).
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (classifyPrimaryPlayWhenReadyInterruption(playWhenReady, reason) != PrimaryPlaybackInterruption.None) {
                    recoverCrossfadeFromPrimaryInterruption(crossfadeCancelSink)
                }
            }

            override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
                if (classifyPrimarySuppressionInterruption(playbackSuppressionReason) != PrimaryPlaybackInterruption.None) {
                    recoverCrossfadeFromPrimaryInterruption(crossfadeCancelSink)
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                logCrossfadePrimaryState(assembly.currentPlayer, "PRIMARY_IS_PLAYING value=$isPlaying")
                if (BuildConfig.DEBUG) Log.d(AUDIO_SESSION_TAG, "[listener] onIsPlayingChanged=$isPlaying sessionId=${assembly.currentPlayer.audioSessionId} ts=${System.currentTimeMillis()}")
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                logCrossfadePrimaryState(assembly.currentPlayer, "PRIMARY_STATE playbackState=$playbackState")
                // CF-2F2: synchronous, before any asynchronous widget work. BUFFERING and READY never cancel.
                if (isPrimaryTerminalPlaybackState(playbackState)) {
                    recoverCrossfadeFromPrimaryTerminalState(crossfadeCancelSink)
                }
                // CF-2L1: READY is real readiness evidence for a pending natural handoff (inert when none is pending).
                if (playbackState == Player.STATE_READY) advanceCrossfadeHandoff(crossfadePreparation)
                if (BuildConfig.DEBUG) Log.d(AUDIO_SESSION_TAG, "[listener] onPlaybackStateChanged=$playbackState sessionId=${assembly.currentPlayer.audioSessionId} ts=${System.currentTimeMillis()}")
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // CF-2L1: authoritative transition fact for the crossfade owner (only a genuine AUTO onto the exact target counts).
                observeCrossfadeNaturalTransition(crossfadePreparation, assembly.currentPlayer.currentMediaItemIndex, reason)
                if (BuildConfig.DEBUG) {
                    // One concise line per transition for physical gapless validation (no position ticks,
                    // no file names): reason, indexes, player state and audio session.
                    val newIndex = assembly.currentPlayer.currentMediaItemIndex
                    Log.d(
                        GAPLESS_TAG,
                        "transition reason=${classifyMediaItemTransition(reason)} oldIndex=$lastGaplessIndex" +
                            " newIndex=$newIndex state=${assembly.currentPlayer.playbackState}" +
                            " playWhenReady=${assembly.currentPlayer.playWhenReady} isPlaying=${assembly.currentPlayer.isPlaying}" +
                            " sessionId=${assembly.currentPlayer.audioSessionId} ts=${SystemClock.elapsedRealtime()}",
                    )
                    lastGaplessIndex = newIndex
                }
                if (BuildConfig.DEBUG) Log.d(AUDIO_SESSION_TAG, "[listener] onMediaItemTransition reason=$reason sessionId=${assembly.currentPlayer.audioSessionId} title=${mediaItem?.mediaMetadata?.title} ts=${System.currentTimeMillis()}")
            }

            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                if (BuildConfig.DEBUG) {
                    val item = assembly.currentPlayer.currentMediaItem
                    Log.d(
                        AUDIO_SESSION_TAG,
                        "[changed] audioSessionId=$audioSessionId" +
                            " playbackState=${assembly.currentPlayer.playbackState}" +
                            " songId=${item?.mediaId}" +
                            " title=${item?.mediaMetadata?.title}" +
                            " ts=${System.currentTimeMillis()}",
                    )
                }
                enhancementController?.onAudioSessionIdChanged(audioSessionId)
            }

        })

        if (BuildConfig.DEBUG) {
            Log.d(
                AUDIO_SESSION_TAG,
                "[post-listener] audioSessionId=${assembly.currentPlayer.audioSessionId} playbackState=${assembly.currentPlayer.playbackState} ts=${System.currentTimeMillis()}"
            )
        }

        // One-time cleanup of stale EQ keys from earlier builds (fire-and-forget).
        // Runs concurrently with attach(); DataStore serializes the edit so controller
        // always applies a clean state after the first emission following normalization.
        serviceScope.launch { audioEnhancementsRepository.normalizeEqPresetStateOnce() }

        enhancementController = AudioEnhancementController(
            repository = audioEnhancementsRepository,
            scope = serviceScope,
        ).also { it.attach(player) }

        // Keep ExoPlayer's noisy-audio handling in sync with the user's preference.
        // Default is true (matches the builder value above), so there is no gap on
        // the first emission even if the coroutine hasn't fired yet.
        serviceScope.launch {
            resumeBehaviorRepository.settings.collect { settings ->
                assembly.setHandleAudioBecomingNoisy(settings.pauseOnAudioDisconnect)
            }
        }

        serviceScope.launch {
            appSettingsRepository.previousButtonBehavior.collect { behavior ->
                previousRestartThresholdMs = behavior.previousRestartThresholdMs()
            }
        }

        // Register for audio device connection events. Null handler → main thread.
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)

        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        mediaSession = MediaLibrarySession.Builder(this, sessionPlayer, WavdropSessionCallback())
            .setSessionActivity(openAppIntent)
            .build()

        // Observe the notification-controls setting and shuffle/repeat state together.
        // Rebuilds the custom layout only when the setting or the relevant playback state changes,
        // avoiding unnecessary updates on every position tick.
        serviceScope.launch {
            combine(
                appSettingsRepository.notificationControlsSetting,
                playerController.nowPlayingState
                    .map { it.shuffleEnabled to it.repeatMode }
                    .distinctUntilChanged(),
            ) { setting, (shuffleEnabled, repeatMode) ->
                buildCustomLayout(setting, shuffleEnabled, repeatMode)
            }.collect { buttons ->
                mediaSession?.setCustomLayout(buttons)
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        mediaSession

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        logResume("onStartCommand: action=${intent?.action}")
        PlaybackServiceStartCommandPolicy.reconnectOutputKindForStartCommand(
            action = intent?.action,
            outputKind = intent?.getStringExtra(EXTRA_AUDIO_OUTPUT_KIND),
        )?.let { outputKind ->
            logResume("onStartCommand: ignored private reconnect command outputKind=$outputKind")
        }
        // Widget controls and reconnect events are no longer accepted as raw exported
        // service actions. They are delivered through non-exported receivers, which
        // drive playback via in-process dependencies / MediaController on this session.
        if (BuildConfig.DEBUG) {
            when (intent?.action) {
                ACTION_DEBUG_EQ_ENABLE -> {
                    Log.d(AUDIO_EFFECTS_TAG, "[debug-cmd] EQ enable requested")
                    serviceScope.launch {
                        audioEnhancementsRepository.setEqFlatPreset()
                        audioEnhancementsRepository.setEqEnabled(true)
                    }
                }
                ACTION_DEBUG_EQ_DISABLE -> {
                    Log.d(AUDIO_EFFECTS_TAG, "[debug-cmd] EQ disable requested")
                    serviceScope.launch {
                        audioEnhancementsRepository.setEqEnabled(false)
                    }
                }
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        val keep = player != null && TaskRemovalPlaybackPolicy.shouldKeepSession(
            mediaItemCount = player.mediaItemCount,
            hasCurrentMediaItem = player.currentMediaItem != null,
        )
        logResume("onTaskRemoved: keepSession=$keep isPlaying=${player?.isPlaying}")
        // Playing sessions already survive via Media3's default; a paused session with a real
        // Media3 queue must too, so PLAY can still target Wavdrop. Empty sessions use the default
        // (pause + stopSelf). onDestroy cleanup is untouched and runs whenever the service ends.
        if (keep) return
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        // CF-2G2: never leave the singleton PlayerController holding a callback into this destroyed service.
        playerController.setExplicitSeekListener(null)
        playerController.setExplicitNavigationListener(null)
        playerController.setExplicitRepeatChangeListener(null)
        playerController.setExplicitShuffleChangeListener(null)
        playerController.setExplicitPlayNextMutationListener(null)
        playerController.setExplicitAddToQueueMutationListener(null)
        playerController.setExplicitQueueReorderListener(null)
        playerController.setExplicitQueueRemovalListener(null)
        playerController.setExplicitLibraryDeletionListener(null)
        playerController.setExplicitQueueReplacementListener(null)
        playerController.setControllerDisconnectedListener(null)
        // Unregister the BT listener before cancelling the scope so no callback
        // can enqueue a new coroutine after the scope is cancelled.
        (getSystemService(Context.AUDIO_SERVICE) as AudioManager)
            .unregisterAudioDeviceCallback(audioDeviceCallback)

        // Cancel the settings observer before releasing the player to avoid
        // calling setHandleAudioBecomingNoisy on a released ExoPlayer instance.
        serviceScope.cancel()
        // CF-2E1: invalidate timing callbacks first (the driver owns them), then CF-2C6: restore any crossfade-lowered
        // primary gain while the primary player is still alive, then
        // release the secondary. Must precede the primary player release below.
        promotionRuntime?.close()
        promotionRuntime = null
        nextSlotDriver?.close()
        nextSlotDriver = null
        closeCrossfadeGraph(crossfadeTimingDriver, crossfadePreparation)
        crossfadeTimingDriver = null
        crossfadePreparation = null
        // Release audio effects before the player so the session is still valid during cleanup.
        enhancementController?.release()
        enhancementController = null
        // CF-2M3: with the engine, PlayerEngine.release() is the ONLY physical release (both slots, focus, noisy receiver);
        // the session player (façade) must not release a delegate as well. Shipping path is unchanged.
        val engine = playbackAssembly?.engine
        playbackAssembly = null
        engine?.release()
        mediaSession?.run {
            if (engine == null) player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    // Registers the custom shuffle/repeat session commands so the notification controller
    // can invoke them. Called once per controller connection (including the system notification
    // controller). The custom layout (setCustomLayout) controls visibility; this controls
    // availability.
    private inner class WavdropSessionCallback : MediaLibrarySession.Callback {
    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): MediaSession.ConnectionResult = withWavdropCommands(session, controller)

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> {
        // Cheap and synchronous: no library scan. A "recent" request (System UI resumption) gets the
        // dedicated recent root, whose immediate children are the playable resumable item.
        val root = WavdropMediaLibrary.rootFor(isRecent = params?.isRecent == true)
        return Futures.immediateFuture(LibraryResult.ofItem(root, params))
    }

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> {
        if (WavdropMediaLibrary.isBrowseNode(mediaId)) {
            return Futures.immediateFuture(
                LibraryResult.ofItem(checkNotNull(WavdropMediaLibrary.item(mediaId, emptyList())), null),
            )
        }
        return libraryFuture {
            val item = WavdropMediaLibrary.item(mediaId, songRepository.songs.first())
            if (item != null) LibraryResult.ofItem(item, null)
            else LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
        }
    }

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = when (parentId) {
        WavdropLibraryIds.ROOT -> Futures.immediateFuture(
            LibraryResult.ofItemList(WavdropMediaLibrary.page(WavdropMediaLibrary.rootChildren(), page, pageSize), params),
        )
        WavdropLibraryIds.SONGS -> libraryFuture {
            LibraryResult.ofItemList(
                WavdropMediaLibrary.songChildren(songRepository.songs.first(), page, pageSize),
                params,
            )
        }
        WavdropLibraryIds.RECENT, WavdropLibraryIds.RECENT_ROOT -> libraryFuture {
            val items = WavdropMediaLibrary.recentChildren(
                snapshot = sessionRepository.load(),
                settings = resumeBehaviorRepository.settings.first(),
                songs = songRepository.songs.first(),
            )
            LibraryResult.ofItemList(WavdropMediaLibrary.page(items, page, pageSize), params)
        }
        else -> Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
    }

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle,
    ): ListenableFuture<SessionResult> {
        when (customCommand.customAction) {
            CMD_TOGGLE_SHUFFLE -> playerController.toggleShuffle()
            CMD_CYCLE_REPEAT -> playerController.cycleRepeatMode()
        }
        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
    }

    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        isForPlayback: Boolean,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
        logResume("onPlaybackResumption invoked isForPlayback=$isForPlayback")
        serviceScope.launch {
            runCatching {
                PlaybackResumptionMapper.map(
                    snapshot = sessionRepository.load(),
                    settings = resumeBehaviorRepository.settings.first(),
                    availableSongs = songRepository.songs.first(),
                )
            }.fold(
                onSuccess = { result ->
                    when (result) {
                        is PlaybackResumptionResult.Ready -> {
                            val plan = result.plan
                            // CF-2H3G: cancel only when this resumption will really be adopted (Ready + isForPlayback),
                            // before the generation bump and queue replacement. Queries, Unavailable and failures never cancel.
                            if (shouldCancelCrossfadeForPlaybackResumption(resultReady = true, isForPlayback = isForPlayback)) {
                                recoverCrossfadeFromPlaybackResumption(crossfadeCancelSink)
                            }
                            if (isForPlayback) {
                                playerController.adoptPlaybackResumption(plan)
                                mediaSession.player.repeatMode = plan.repeatMode.toPlayerRepeatMode()
                                mediaSession.player.shuffleModeEnabled = false
                            }
                            logResume(
                                "onPlaybackResumption ready queueSize=${plan.mediaItems.size} " +
                                    "startIndex=${plan.startPlaybackIndex} " +
                                    "startPositionMs=${plan.startPositionMs}",
                            )
                            future.set(plan.toMedia3Resumption())
                        }
                        is PlaybackResumptionResult.Unavailable -> {
                            logResume("onPlaybackResumption unavailable reason=${result.reason}")
                            future.setException(
                                IllegalStateException("Playback resumption unavailable: ${result.reason}"),
                            )
                        }
                    }
                },
                onFailure = { error ->
                    logResume(
                        "onPlaybackResumption failed: ${error::class.simpleName} ${error.message}",
                    )
                    future.setException(error)
                },
            )
        }
        return future
    }
}

    // Builds the list of CommandButton instances for the notification based on the user's
    // notification-controls preference and the current shuffle/repeat state.
    // Returns an empty list for STANDARD (no extra buttons), which is the safe default.
    // Android may silently drop extra actions that do not fit in the notification slot limit;
    // Media3 handles this gracefully by omitting buttons that cannot be shown.
    private fun buildCustomLayout(
        setting: NotificationControlsSetting,
        shuffleEnabled: Boolean,
        repeatMode: RepeatMode,
    ): List<CommandButton> {
        if (!setting.includeShuffle && !setting.includeRepeat) return emptyList()

        val buttons = mutableListOf<CommandButton>()

        if (setting.includeShuffle) {
            val icon = if (shuffleEnabled) {
                CommandButton.ICON_SHUFFLE_ON
            } else {
                CommandButton.ICON_SHUFFLE_OFF
            }
            buttons += CommandButton.Builder(icon)
                .setDisplayName(if (shuffleEnabled) "Shuffle on" else "Shuffle off")
                .setSessionCommand(SessionCommand(CMD_TOGGLE_SHUFFLE, Bundle.EMPTY))
                .build()
        }

        if (setting.includeRepeat) {
            val icon = when (repeatMode) {
                RepeatMode.OFF -> CommandButton.ICON_REPEAT_OFF
                RepeatMode.ALL -> CommandButton.ICON_REPEAT_ALL
                RepeatMode.ONE -> CommandButton.ICON_REPEAT_ONE
            }
            val name = when (repeatMode) {
                RepeatMode.OFF -> "Repeat off"
                RepeatMode.ALL -> "Repeat all"
                RepeatMode.ONE -> "Repeat one"
            }
            buttons += CommandButton.Builder(icon)
                .setDisplayName(name)
                .setSessionCommand(SessionCommand(CMD_CYCLE_REPEAT, Bundle.EMPTY))
                .build()
        }

        return buttons
    }

    private fun <T : Any> libraryFuture(block: suspend () -> LibraryResult<T>): ListenableFuture<LibraryResult<T>> {
        val future = SettableFuture.create<LibraryResult<T>>()
        serviceScope.launch {
            future.set(
                runCatching { block() }.getOrElse { error ->
                    logResume("library query failed: ${error::class.simpleName} ${error.message}")
                    LibraryResult.ofError(LibraryResult.RESULT_ERROR_IO)
                },
            )
        }
        return future
    }

    private fun logResume(message: String) {
        if (BuildConfig.DEBUG) Log.d(RESUME_TAG, message)
    }

    /**
     * CF-2L3: DEBUG-only primary state-churn evidence, emitted ONLY while a crossfade is Active (never forever, no timer). The
     * settlement line at handoff completion closes the picture. No titles, paths or ids are logged.
     */
    private fun logCrossfadePrimaryState(player: Player, event: String) {
        if (!BuildConfig.DEBUG) return
        val settlement = crossfadePreparation?.settlementSnapshot() ?: return
        if (settlement.state == CrossfadeState.Idle) return
        Log.d(
            CROSSFADE_TAG,
            "$event index=${player.currentMediaItemIndex} playWhenReady=${player.playWhenReady} isPlaying=${player.isPlaying} " +
                formatCrossfadeSettlementSummary(settlement),
        )
    }

    companion object {
        const val ACTION_AUDIO_OUTPUT_CONNECTED = "com.launchpoint.wavdrop.ACTION_AUDIO_OUTPUT_CONNECTED"
        const val EXTRA_AUDIO_OUTPUT_KIND = "com.launchpoint.wavdrop.EXTRA_AUDIO_OUTPUT_KIND"
        const val OUTPUT_BLUETOOTH = "bluetooth"
        const val OUTPUT_WIRED = "wired"
        private const val CROSSFADE_TAG = "WavdropCrossfade"
        private const val CMD_TOGGLE_SHUFFLE = "com.launchpoint.wavdrop.TOGGLE_SHUFFLE"
        private const val CMD_CYCLE_REPEAT = "com.launchpoint.wavdrop.CYCLE_REPEAT"
        @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
        internal fun withWavdropCommands(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val defaultResult = MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller).build()
            val enrichedCommands = defaultResult.availableSessionCommands.buildUpon()
                .add(SessionCommand(CMD_TOGGLE_SHUFFLE, Bundle.EMPTY))
                .add(SessionCommand(CMD_CYCLE_REPEAT, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                .setAvailableSessionCommands(enrichedCommands)
                .build()
        }

        private const val RESUME_TAG = "WavdropResume"
        private const val AUDIO_SESSION_TAG = "WavdropAudioSession"
        private const val GAPLESS_TAG = "WavdropGapless"
        private const val AUDIO_EFFECTS_TAG = "WavdropAudioEffects"
        // Debug-only ADB triggers for EQ round-trip validation — never exposed in release builds.
        private const val ACTION_DEBUG_EQ_ENABLE  = "com.launchpoint.wavdrop.DEBUG_EQ_ENABLE"
        private const val ACTION_DEBUG_EQ_DISABLE = "com.launchpoint.wavdrop.DEBUG_EQ_DISABLE"
    }
}
