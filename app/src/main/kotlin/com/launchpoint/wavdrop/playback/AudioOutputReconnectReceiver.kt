package com.launchpoint.wavdrop.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.launchpoint.wavdrop.BuildConfig
import com.launchpoint.wavdrop.data.playback.PlaybackSessionRepository
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

    override fun onReceive(context: Context, intent: Intent) {
        logResume(
            "Receiver onReceive action=${intent.action} " +
                "btState=${intent.getIntExtra(EXTRA_BLUETOOTH_PROFILE_STATE, UNKNOWN_STATE)} " +
                "headsetState=${intent.getIntExtra(EXTRA_HEADSET_STATE, UNKNOWN_STATE)}",
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
                    startPlaybackService(context, outputKind)
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

    private fun startPlaybackService(context: Context, outputKind: String) {
        val serviceIntent = Intent(context, PlaybackService::class.java).apply {
            action = PlaybackService.ACTION_AUDIO_OUTPUT_CONNECTED
            putExtra(PlaybackService.EXTRA_AUDIO_OUTPUT_KIND, outputKind)
        }
        runCatching {
            logResume("Receiver attempting PlaybackService start outputKind=$outputKind")
            ContextCompat.startForegroundService(context, serviceIntent)
            logResume("Receiver started PlaybackService for $outputKind reconnect")
        }.onFailure { error ->
            logResume(
                "Receiver could not start PlaybackService for $outputKind reconnect: " +
                    "${error.javaClass.simpleName}: ${error.message}",
            )
        }
    }

    private fun Intent.connectedOutputKind(): String? =
        when (action) {
            Intent.ACTION_HEADSET_PLUG -> {
                if (getIntExtra(EXTRA_HEADSET_STATE, DISCONNECTED) == CONNECTED) {
                    PlaybackService.OUTPUT_WIRED
                } else {
                    null
                }
            }
            ACTION_A2DP_CONNECTION_STATE_CHANGED,
            ACTION_HEADSET_CONNECTION_STATE_CHANGED,
            ACTION_HEARING_AID_CONNECTION_STATE_CHANGED -> {
                if (getIntExtra(EXTRA_BLUETOOTH_PROFILE_STATE, DISCONNECTED) == CONNECTED) {
                    PlaybackService.OUTPUT_BLUETOOTH
                } else {
                    null
                }
            }
            else -> null
        }

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
        const val ACTION_A2DP_CONNECTION_STATE_CHANGED =
            "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED"
        const val ACTION_HEADSET_CONNECTION_STATE_CHANGED =
            "android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED"
        const val ACTION_HEARING_AID_CONNECTION_STATE_CHANGED =
            "android.bluetooth.hearingaid.profile.action.CONNECTION_STATE_CHANGED"
        const val EXTRA_BLUETOOTH_PROFILE_STATE = "android.bluetooth.profile.extra.STATE"
        const val EXTRA_HEADSET_STATE = "state"
        const val CONNECTED = 2
        const val DISCONNECTED = 0
        const val UNKNOWN_STATE = -1
        const val RESUME_TAG = "WavdropResume"
    }
}
