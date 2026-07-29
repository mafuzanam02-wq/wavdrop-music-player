package com.launchpoint.wavdrop.playback

import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackServiceStartCommandPolicyTest {

    @Test
    fun `external style private reconnect service intent is ignored`() {
        assertNull(
            PlaybackServiceStartCommandPolicy.reconnectOutputKindForStartCommand(
                action = PlaybackService.ACTION_AUDIO_OUTPUT_CONNECTED,
                outputKind = PlaybackService.OUTPUT_BLUETOOTH,
            ),
        )
    }

    @Test
    fun `ordinary media session service startup is not classified as reconnect`() {
        assertNull(
            PlaybackServiceStartCommandPolicy.reconnectOutputKindForStartCommand(
                action = "androidx.media3.session.MediaSessionService",
                outputKind = null,
            ),
        )
    }
}
