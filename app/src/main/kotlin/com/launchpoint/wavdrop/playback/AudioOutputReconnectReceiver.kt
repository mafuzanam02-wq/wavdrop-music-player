package com.launchpoint.wavdrop.playback

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.launchpoint.wavdrop.BuildConfig
import com.launchpoint.wavdrop.data.playback.PlaybackSessionRepository
import com.launchpoint.wavdrop.data.repository.SongRepository
import com.launchpoint.wavdrop.data.settings.HeadphoneResumeMode
import com.launchpoint.wavdrop.data.settings.ResumeBehaviorSettings
import com.launchpoint.wavdrop.data.settings.ResumeBehaviorSettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@AndroidEntryPoint
class AudioOutputReconnectReceiver : BroadcastReceiver() {

    @Inject lateinit var resumeBehaviorRepository: ResumeBehaviorSettingsRepository
    @Inject lateinit var sessionRepository: PlaybackSessionRepository
    @Inject lateinit var playerController: PlayerController
    @Inject lateinit var songRepository: SongRepository

    override fun onReceive(context: Context, intent: Intent) {
        logResume(
            "Receiver onReceive action=${intent.action} " +
                "btState=${intent.getIntExtra(AudioOutputReconnectClassifier.EXTRA_BLUETOOTH_PROFILE_STATE, AudioOutputReconnectClassifier.UNKNOWN_STATE)} " +
                "headsetState=${intent.getIntExtra(AudioOutputReconnectClassifier.EXTRA_HEADSET_STATE, AudioOutputReconnectClassifier.UNKNOWN_STATE)}",
        )
        val outputKind = intent.connectedOutputKind()
        if (outputKind == null) {
            logResume("Receiver ignored action=${intent.action}: not a connected Bluetooth/wired audio event")
            return
        }
        logResume("Receiver classified reconnect as outputKind=$outputKind")
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                if (shouldStartPlaybackService(context, outputKind)) {
                    val routeReady = outputKind != PlaybackService.OUTPUT_BLUETOOTH ||
                        BluetoothRouteReadiness.awaitOutput(
                            audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager,
                            timeoutMs = BLUETOOTH_ROUTE_TIMEOUT_MS,
                        )
                    logResume("Receiver route readiness outputKind=$outputKind ready=$routeReady")
                    if (routeReady && startPlaybackService(context)) {
                        resumeForOutput(outputKind)
                    }
                } else {
                    logResume("Receiver ignored $outputKind reconnect")
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun shouldStartPlaybackService(context: Context, outputKind: String): Boolean {
        val settings = resumeBehaviorRepository.settings.first()
        val mode = settings.resumeMode(outputKind)
        val pendingInterrupted = hasInterruptedResumePending(outputKind)
        val hasSavedSession = sessionRepository.load() != null
        val permissionGranted = outputKind != PlaybackService.OUTPUT_BLUETOOTH ||
            hasBluetoothConnectPermission(context)
        logResume(
            "Receiver eligibility outputKind=$outputKind mode=$mode " +
                "rememberLastTrack=${settings.rememberLastTrack} " +
                "pendingInterrupted=$pendingInterrupted hasSavedSession=$hasSavedSession " +
                "bluetoothConnectGranted=$permissionGranted",
        )
        return permissionGranted && ConnectionResumePolicy.shouldAttempt(
            rememberLastTrack = settings.rememberLastTrack,
            mode = mode,
            interruptedPending = pendingInterrupted,
            hasSavedSession = hasSavedSession,
        )
    }

    private fun startPlaybackService(context: Context): Boolean {
        val serviceIntent = Intent(context, PlaybackService::class.java)
        return runCatching {
            logResume("Receiver attempting PlaybackService start for reconnect")
            ContextCompat.startForegroundService(context, serviceIntent)
            logResume("Receiver PlaybackService start accepted for reconnect")
            true
        }.onFailure { error ->
            logResume(
                "Receiver PlaybackService start rejected for reconnect: " +
                    "${error.javaClass.simpleName}: ${error.message}",
            )
        }.getOrDefault(false)
    }

    private suspend fun resumeForOutput(outputKind: String) {
        val songs = songRepository.songs.first()
        logResume("Receiver got ${songs.size} songs, dispatching resume for outputKind=$outputKind")
        when (outputKind) {
            PlaybackService.OUTPUT_BLUETOOTH -> playerController.resumeForBluetooth(songs)
            PlaybackService.OUTPUT_WIRED -> playerController.resumeForWiredHeadphones(songs)
        }
    }

    private fun Intent.connectedOutputKind(): String? =
        AudioOutputReconnectClassifier.connectedOutputKind(
            action = action,
            bluetoothProfileState = getIntExtra(
                AudioOutputReconnectClassifier.EXTRA_BLUETOOTH_PROFILE_STATE,
                AudioOutputReconnectClassifier.DISCONNECTED,
            ),
            headsetState = getIntExtra(
                AudioOutputReconnectClassifier.EXTRA_HEADSET_STATE,
                AudioOutputReconnectClassifier.DISCONNECTED,
            ),
        )

    private fun ResumeBehaviorSettings.resumeMode(outputKind: String): HeadphoneResumeMode =
        when (outputKind) {
            PlaybackService.OUTPUT_BLUETOOTH -> bluetoothResumeMode
            PlaybackService.OUTPUT_WIRED -> wiredResumeMode
            else -> HeadphoneResumeMode.OFF
        }

    private suspend fun hasInterruptedResumePending(outputKind: String): Boolean =
        when (outputKind) {
            PlaybackService.OUTPUT_BLUETOOTH -> resumeBehaviorRepository.hasBluetoothInterruptedResumePending()
            PlaybackService.OUTPUT_WIRED -> resumeBehaviorRepository.hasWiredInterruptedResumePending()
            else -> false
        }

    private fun hasBluetoothConnectPermission(context: Context): Boolean =
        !BluetoothReconnectPermissionPolicy.requiresBluetoothConnect(Build.VERSION.SDK_INT) ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT,
            ) == PackageManager.PERMISSION_GRANTED

    private fun logResume(message: String) {
        if (BuildConfig.DEBUG) Log.d(RESUME_TAG, message)
    }

    private companion object {
        const val RESUME_TAG = "WavdropResume"
        const val BLUETOOTH_ROUTE_TIMEOUT_MS = 1_500L
    }
}
