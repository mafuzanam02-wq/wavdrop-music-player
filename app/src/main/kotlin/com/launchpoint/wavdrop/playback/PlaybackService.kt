package com.launchpoint.wavdrop.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Bundle
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.launchpoint.wavdrop.BuildConfig
import com.launchpoint.wavdrop.MainActivity
import com.launchpoint.wavdrop.R
import com.launchpoint.wavdrop.data.repository.SongRepository
import com.launchpoint.wavdrop.data.settings.AppSettingsRepository
import com.launchpoint.wavdrop.data.settings.NotificationControlsSetting
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
class PlaybackService : MediaSessionService() {

    @Inject lateinit var resumeBehaviorRepository: ResumeBehaviorSettingsRepository
    @Inject lateinit var appSettingsRepository: AppSettingsRepository
    @Inject lateinit var playerController: PlayerController
    @Inject lateinit var songRepository: SongRepository
    @Inject lateinit var widgetStateStore: WidgetStateStore

    private var mediaSession: MediaSession? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

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
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus= */ true)
            .setHandleAudioBecomingNoisy(true)
            .build()

        // Authoritative widget state source: direct ExoPlayer listener fires on the
        // player thread without the MediaController → MediaSession IPC round-trip.
        // This covers notification controls, lock-screen controls, Bluetooth buttons,
        // and widget action intents — all paths that previously bypassed PlayerController.
        player.addListener(object : Player.Listener {

            private fun buildSnapshot(isPlaying: Boolean): WidgetPlaybackSnapshot {
                val item = player.currentMediaItem
                return WidgetPlaybackSnapshot(
                    title          = item?.mediaMetadata?.title?.toString()?.takeIf { it.isNotBlank() } ?: "Wavdrop",
                    artist         = item?.mediaMetadata?.artist?.toString()?.takeIf { it.isNotBlank() } ?: "",
                    albumId        = item?.mediaMetadata?.extras?.getLong("wavdrop_album_id", 0L) ?: 0L,
                    isPlaying      = isPlaying,
                    hasActiveMedia = item != null,
                    updatedAt      = System.currentTimeMillis(),
                )
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (BuildConfig.DEBUG) Log.d(WIDGET_TAG, "[service] onIsPlayingChanged=$isPlaying")
                serviceScope.launch {
                    try {
                        if (BuildConfig.DEBUG) Log.d(WIDGET_TAG, "[service] onIsPlayingChanged: store write START isPlaying=$isPlaying")
                        widgetStateStore.updateIsPlaying(isPlaying)
                        if (BuildConfig.DEBUG) Log.d(WIDGET_TAG, "[service] onIsPlayingChanged: store write COMPLETE isPlaying=$isPlaying")
                        if (BuildConfig.DEBUG) Log.d(WIDGET_TAG, "[service] onIsPlayingChanged: calling requestUpdate")
                        WavdropWidgetUpdater.requestUpdate(applicationContext)
                        if (BuildConfig.DEBUG) Log.d(WIDGET_TAG, "[service] onIsPlayingChanged: requestUpdate returned (fire-and-forget launched)")
                    } catch (e: Throwable) {
                        Log.e(WIDGET_TAG, "[service] onIsPlayingChanged: EXCEPTION ${e::class.simpleName} ${e.message}", e)
                    }
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (BuildConfig.DEBUG) Log.d(WIDGET_TAG, "[service] onPlaybackStateChanged=$playbackState")
                if (playbackState == Player.STATE_IDLE) {
                    serviceScope.launch {
                        try {
                            if (BuildConfig.DEBUG) Log.d(WIDGET_TAG, "[service] onPlaybackStateChanged IDLE: store clear START")
                            widgetStateStore.clear()
                            if (BuildConfig.DEBUG) Log.d(WIDGET_TAG, "[service] onPlaybackStateChanged IDLE: store clear COMPLETE")
                            if (BuildConfig.DEBUG) Log.d(WIDGET_TAG, "[service] onPlaybackStateChanged IDLE: calling requestUpdate")
                            WavdropWidgetUpdater.requestUpdate(applicationContext)
                            if (BuildConfig.DEBUG) Log.d(WIDGET_TAG, "[service] onPlaybackStateChanged IDLE: requestUpdate returned")
                        } catch (e: Throwable) {
                            Log.e(WIDGET_TAG, "[service] onPlaybackStateChanged: EXCEPTION ${e::class.simpleName} ${e.message}", e)
                        }
                    }
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (BuildConfig.DEBUG) Log.d(WIDGET_TAG, "[service] onMediaItemTransition reason=$reason title=${mediaItem?.mediaMetadata?.title}")
                serviceScope.launch {
                    try {
                        if (mediaItem == null) {
                            if (BuildConfig.DEBUG) Log.d(WIDGET_TAG, "[service] onMediaItemTransition: store clear START (null item)")
                            widgetStateStore.clear()
                            if (BuildConfig.DEBUG) Log.d(WIDGET_TAG, "[service] onMediaItemTransition: store clear COMPLETE")
                        } else {
                            if (BuildConfig.DEBUG) Log.d(WIDGET_TAG, "[service] onMediaItemTransition: store save START title=${mediaItem.mediaMetadata.title}")
                            widgetStateStore.save(buildSnapshot(player.isPlaying))
                            if (BuildConfig.DEBUG) Log.d(WIDGET_TAG, "[service] onMediaItemTransition: store save COMPLETE")
                        }
                        if (BuildConfig.DEBUG) Log.d(WIDGET_TAG, "[service] onMediaItemTransition: calling requestUpdate")
                        WavdropWidgetUpdater.requestUpdate(applicationContext)
                        if (BuildConfig.DEBUG) Log.d(WIDGET_TAG, "[service] onMediaItemTransition: requestUpdate returned")
                    } catch (e: Throwable) {
                        Log.e(WIDGET_TAG, "[service] onMediaItemTransition: EXCEPTION ${e::class.simpleName} ${e.message}", e)
                    }
                }
            }

        })

        // Keep ExoPlayer's noisy-audio handling in sync with the user's preference.
        // Default is true (matches the builder value above), so there is no gap on
        // the first emission even if the coroutine hasn't fired yet.
        serviceScope.launch {
            resumeBehaviorRepository.settings.collect { settings ->
                player.setHandleAudioBecomingNoisy(settings.pauseOnAudioDisconnect)
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
        mediaSession = MediaSession.Builder(this, player)
            .setCallback(WavdropSessionCallback())
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

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        logResume("onStartCommand: action=${intent?.action}")
        when (intent?.action) {
            ACTION_AUDIO_OUTPUT_CONNECTED -> {
                val outputKind = intent.getStringExtra(EXTRA_AUDIO_OUTPUT_KIND)
                logResume("onStartCommand: ACTION_AUDIO_OUTPUT_CONNECTED outputKind=$outputKind")
                if (outputKind != null) {
                    serviceScope.launch {
                        val songs = songRepository.songs.first()
                        logResume("onStartCommand: got ${songs.size} songs, dispatching resume for outputKind=$outputKind")
                        when (outputKind) {
                            OUTPUT_BLUETOOTH -> playerController.resumeForBluetooth(songs)
                            OUTPUT_WIRED -> playerController.resumeForWiredHeadphones(songs)
                        }
                    }
                }
            }
            // Widget action intents — routed directly to ExoPlayer, same path as notification controls.
            ACTION_WIDGET_PLAY_PAUSE -> {
                val p = mediaSession?.player ?: return super.onStartCommand(intent, flags, startId)
                if (BuildConfig.DEBUG) Log.d(WIDGET_TAG, "[service] ACTION_WIDGET_PLAY_PAUSE isPlaying=${p.isPlaying}")
                if (p.isPlaying) p.pause() else p.play()
            }
            ACTION_WIDGET_NEXT -> {
                if (BuildConfig.DEBUG) Log.d(WIDGET_TAG, "[service] ACTION_WIDGET_NEXT")
                mediaSession?.player?.seekToNext()
            }
            ACTION_WIDGET_PREVIOUS -> {
                if (BuildConfig.DEBUG) Log.d(WIDGET_TAG, "[service] ACTION_WIDGET_PREVIOUS")
                mediaSession?.player?.seekToPrevious()
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        // Unregister the BT listener before cancelling the scope so no callback
        // can enqueue a new coroutine after the scope is cancelled.
        (getSystemService(Context.AUDIO_SERVICE) as AudioManager)
            .unregisterAudioDeviceCallback(audioDeviceCallback)

        // Cancel the settings observer before releasing the player to avoid
        // calling setHandleAudioBecomingNoisy on a released ExoPlayer instance.
        serviceScope.cancel()
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    // Registers the custom shuffle/repeat session commands so the notification controller
    // can invoke them. Called once per controller connection (including the system notification
    // controller). The custom layout (setCustomLayout) controls visibility; this controls
    // availability.
    private inner class WavdropSessionCallback : MediaSession.Callback {
    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): MediaSession.ConnectionResult {
        val defaultResult = super.onConnect(session, controller)
        val enrichedCommands = defaultResult.availableSessionCommands.buildUpon()
            .add(SessionCommand(CMD_TOGGLE_SHUFFLE, Bundle.EMPTY))
            .add(SessionCommand(CMD_CYCLE_REPEAT, Bundle.EMPTY))
            .build()
        return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
            .setAvailableSessionCommands(enrichedCommands)
            .build()
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
            val iconRes = if (shuffleEnabled) {
                androidx.media3.session.R.drawable.media3_icon_shuffle_on
            } else {
                androidx.media3.session.R.drawable.media3_icon_shuffle_off
            }
            buttons += CommandButton.Builder()
                .setDisplayName(if (shuffleEnabled) "Shuffle on" else "Shuffle off")
                .setSessionCommand(SessionCommand(CMD_TOGGLE_SHUFFLE, Bundle.EMPTY))
                .setIconResId(iconRes)
                .build()
        }

        if (setting.includeRepeat) {
            val iconRes = when (repeatMode) {
                RepeatMode.OFF -> androidx.media3.session.R.drawable.media3_icon_repeat_off
                RepeatMode.ALL -> androidx.media3.session.R.drawable.media3_icon_repeat_all
                RepeatMode.ONE -> androidx.media3.session.R.drawable.media3_icon_repeat_one
            }
            val name = when (repeatMode) {
                RepeatMode.OFF -> "Repeat off"
                RepeatMode.ALL -> "Repeat all"
                RepeatMode.ONE -> "Repeat one"
            }
            buttons += CommandButton.Builder()
                .setDisplayName(name)
                .setSessionCommand(SessionCommand(CMD_CYCLE_REPEAT, Bundle.EMPTY))
                .setIconResId(iconRes)
                .build()
        }

        return buttons
    }

    private fun logResume(message: String) {
        if (BuildConfig.DEBUG) Log.d(RESUME_TAG, message)
    }

    companion object {
        const val ACTION_AUDIO_OUTPUT_CONNECTED = "com.launchpoint.wavdrop.ACTION_AUDIO_OUTPUT_CONNECTED"
        const val EXTRA_AUDIO_OUTPUT_KIND = "com.launchpoint.wavdrop.EXTRA_AUDIO_OUTPUT_KIND"
        const val OUTPUT_BLUETOOTH = "bluetooth"
        const val OUTPUT_WIRED = "wired"
        // Widget action intents — handled in onStartCommand, forwarded directly to ExoPlayer.
        const val ACTION_WIDGET_PLAY_PAUSE = "com.launchpoint.wavdrop.ACTION_WIDGET_PLAY_PAUSE"
        const val ACTION_WIDGET_NEXT       = "com.launchpoint.wavdrop.ACTION_WIDGET_NEXT"
        const val ACTION_WIDGET_PREVIOUS   = "com.launchpoint.wavdrop.ACTION_WIDGET_PREVIOUS"
        private const val CMD_TOGGLE_SHUFFLE = "com.launchpoint.wavdrop.TOGGLE_SHUFFLE"
        private const val CMD_CYCLE_REPEAT = "com.launchpoint.wavdrop.CYCLE_REPEAT"
        private const val RESUME_TAG = "WavdropResume"
        private const val WIDGET_TAG = "WavdropWidget"
    }
}
