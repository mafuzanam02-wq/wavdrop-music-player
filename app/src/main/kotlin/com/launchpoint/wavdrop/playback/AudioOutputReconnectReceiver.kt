package com.launchpoint.wavdrop.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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
                if (shouldStartPlaybackService(outputKind)) {
                    startPlaybackService(context)
                    resumeForOutput(outputKind)
                } else {
                    logResume("Receiver ignored $outputKind reconnect")
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun shouldStartPlaybackService(outputKind: String): Boolean {
        val settings = resumeBehaviorRepository.settings.first()
        val mode = settings.resumeMode(outputKind)
        val pendingInterrupted = hasInterruptedResumePending(outputKind)
        val hasSavedSession = sessionRepository.load() != null
        logResume(
            "Receiver eligibility outputKind=$outputKind mode=$mode " +
                "rememberLastTrack=${settings.rememberLastTrack} " +
                "pendingInterrupted=$pendingInterrupted hasSavedSession=$hasSavedSession",
        )
        if (!settings.rememberLastTrack) return false

        return when (mode) {
            HeadphoneResumeMode.OFF -> false
            HeadphoneResumeMode.RESUME_IF_INTERRUPTED -> pendingInterrupted
            HeadphoneResumeMode.ALWAYS_RESUME -> hasSavedSession
        }
    }

    private fun startPlaybackService(context: Context) {
        val serviceIntent = Intent(context, PlaybackService::class.java)
        runCatching {
            logResume("Receiver attempting PlaybackService start for reconnect")
            ContextCompat.startForegroundService(context, serviceIntent)
            logResume("Receiver started PlaybackService for reconnect")
        }.onFailure { error ->
            logResume(
                "Receiver could not start PlaybackService for reconnect: " +
                    "${error.javaClass.simpleName}: ${error.message}",
            )
        }
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

    private fun logResume(message: String) {
        if (BuildConfig.DEBUG) Log.d(RESUME_TAG, message)
    }

    private companion object {
        const val RESUME_TAG = "WavdropResume"
    }
}
