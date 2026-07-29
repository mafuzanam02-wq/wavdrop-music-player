package com.launchpoint.wavdrop.playback

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioOutputReconnectClassifierTest {

    @Test
    fun `connected bluetooth profile event maps to bluetooth reconnect`() {
        assertEquals(
            PlaybackService.OUTPUT_BLUETOOTH,
            AudioOutputReconnectClassifier.connectedOutputKind(
                action = AudioOutputReconnectClassifier.ACTION_A2DP_CONNECTION_STATE_CHANGED,
                bluetoothProfileState = AudioOutputReconnectClassifier.CONNECTED,
            ),
        )
    }

    @Test
    fun `connected wired headset event maps to wired reconnect`() {
        assertEquals(
            PlaybackService.OUTPUT_WIRED,
            AudioOutputReconnectClassifier.connectedOutputKind(
                action = Intent.ACTION_HEADSET_PLUG,
                headsetState = AudioOutputReconnectClassifier.CONNECTED,
            ),
        )
    }

    @Test
    fun `disconnected and unknown events are ignored`() {
        assertNull(
            AudioOutputReconnectClassifier.connectedOutputKind(
                action = AudioOutputReconnectClassifier.ACTION_HEADSET_CONNECTION_STATE_CHANGED,
                bluetoothProfileState = AudioOutputReconnectClassifier.DISCONNECTED,
            ),
        )
        assertNull(
            AudioOutputReconnectClassifier.connectedOutputKind(
                action = "com.example.UNTRUSTED",
                bluetoothProfileState = AudioOutputReconnectClassifier.CONNECTED,
                headsetState = AudioOutputReconnectClassifier.CONNECTED,
            ),
        )
    }
}
