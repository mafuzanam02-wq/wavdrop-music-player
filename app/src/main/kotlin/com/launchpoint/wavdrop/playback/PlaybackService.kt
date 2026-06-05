package com.launchpoint.wavdrop.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.launchpoint.wavdrop.MainActivity
import com.launchpoint.wavdrop.data.settings.AppSettingsRepository
import com.launchpoint.wavdrop.data.settings.NotificationControlsSetting
import com.launchpoint.wavdrop.data.repository.SongRepository
import com.launchpoint.wavdrop.data.settings.ResumeBehaviorSettingsRepository
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

    private var mediaSession: MediaSession? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Fires when audio devices are added or removed. Runs on the main thread (null handler).
    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
            val hasBluetooth = addedDevices.any { it.isSink && BluetoothAudioDetector.isBluetoothAudioType(it.type) }
            val hasWired     = addedDevices.any { it.isSink && WiredHeadphoneDetector.isWiredOutputType(it.type) }
            if (!hasBluetooth && !hasWired) return
            serviceScope.launch {
                val songs = songRepository.songs.first()
                if (hasBluetooth) playerController.resumeForBluetooth(songs)
                if (hasWired)     playerController.resumeForWiredHeadphones(songs)
            }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
            if (removedDevices.any { it.isSink && BluetoothAudioDetector.isBluetoothAudioType(it.type) }) {
                playerController.onBluetoothDeviceRemoved()
            }
            if (removedDevices.any { it.isSink && WiredHeadphoneDetector.isWiredOutputType(it.type) }) {
                playerController.onWiredDeviceRemoved()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus= */ true)
            .setHandleAudioBecomingNoisy(true)
            .build()

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
                CMD_CYCLE_REPEAT   -> playerController.cycleRepeatMode()
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
            val iconRes = if (shuffleEnabled)
                androidx.media3.session.R.drawable.media3_icon_shuffle_on
            else
                androidx.media3.session.R.drawable.media3_icon_shuffle_off
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

    private companion object {
        const val CMD_TOGGLE_SHUFFLE = "com.launchpoint.wavdrop.TOGGLE_SHUFFLE"
        const val CMD_CYCLE_REPEAT   = "com.launchpoint.wavdrop.CYCLE_REPEAT"
    }
}
