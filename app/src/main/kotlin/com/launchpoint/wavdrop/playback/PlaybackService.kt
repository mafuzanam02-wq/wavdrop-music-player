package com.launchpoint.wavdrop.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.launchpoint.wavdrop.MainActivity
import com.launchpoint.wavdrop.data.repository.SongRepository
import com.launchpoint.wavdrop.data.settings.ResumeBehaviorSettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Owns the ExoPlayer instance and its MediaSession.
 * The player lives here so playback survives UI navigation.
 * Clients connect via MediaController (see PlayerController).
 */
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    @Inject lateinit var resumeBehaviorRepository: ResumeBehaviorSettingsRepository
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
            .setSessionActivity(openAppIntent)
            .build()
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
}
