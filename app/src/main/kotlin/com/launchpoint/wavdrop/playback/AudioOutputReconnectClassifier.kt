package com.launchpoint.wavdrop.playback

object AudioOutputReconnectClassifier {
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

    fun connectedOutputKind(
        action: String?,
        bluetoothProfileState: Int = UNKNOWN_STATE,
        headsetState: Int = UNKNOWN_STATE,
    ): String? =
        when (action) {
            android.content.Intent.ACTION_HEADSET_PLUG -> {
                if (headsetState == CONNECTED) PlaybackService.OUTPUT_WIRED else null
            }
            ACTION_A2DP_CONNECTION_STATE_CHANGED,
            ACTION_HEADSET_CONNECTION_STATE_CHANGED,
            ACTION_HEARING_AID_CONNECTION_STATE_CHANGED -> {
                if (bluetoothProfileState == CONNECTED) PlaybackService.OUTPUT_BLUETOOTH else null
            }
            else -> null
        }
}
