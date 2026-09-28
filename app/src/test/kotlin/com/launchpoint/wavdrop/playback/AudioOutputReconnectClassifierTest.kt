package com.launchpoint.wavdrop.playback

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioOutputReconnectClassifierTest {

    @Test
    fun `all connected bluetooth audio profiles map to bluetooth reconnect`() {
        listOf(
            AudioOutputReconnectClassifier.ACTION_A2DP_CONNECTION_STATE_CHANGED,
            AudioOutputReconnectClassifier.ACTION_HEADSET_CONNECTION_STATE_CHANGED,
            AudioOutputReconnectClassifier.ACTION_HEARING_AID_CONNECTION_STATE_CHANGED,
            AudioOutputReconnectClassifier.ACTION_LE_AUDIO_CONNECTION_STATE_CHANGED,
        ).forEach { action ->
            assertEquals(
                PlaybackService.OUTPUT_BLUETOOTH,
                AudioOutputReconnectClassifier.connectedOutputKind(
                    action = action,
                    bluetoothProfileState = AudioOutputReconnectClassifier.CONNECTED,
                ),
            )
        }
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
    fun `non-connected states are ignored for classic and LE profiles`() {
        listOf(0, 1, 3).forEach { state ->
            listOf(
                AudioOutputReconnectClassifier.ACTION_A2DP_CONNECTION_STATE_CHANGED,
                AudioOutputReconnectClassifier.ACTION_LE_AUDIO_CONNECTION_STATE_CHANGED,
            ).forEach { action ->
                assertNull(
                    AudioOutputReconnectClassifier.connectedOutputKind(
                        action = action,
                        bluetoothProfileState = state,
                    ),
                )
            }
        }
    }

    @Test
    fun `unrelated action is ignored`() {
        assertNull(
            AudioOutputReconnectClassifier.connectedOutputKind(
                action = "com.example.UNTRUSTED",
                bluetoothProfileState = AudioOutputReconnectClassifier.CONNECTED,
                headsetState = AudioOutputReconnectClassifier.CONNECTED,
            ),
        )
    }
}
