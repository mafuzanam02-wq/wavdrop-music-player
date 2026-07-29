package com.launchpoint.wavdrop

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalAudioIntentValidatorTest {

    @Test
    fun `valid content audio intent is accepted`() {
        assertTrue(
            ExternalAudioIntentValidator.isAllowedAudioViewIntent(
                action = ACTION_VIEW,
                uriString = "content://media/external/audio/media/42",
                intentMimeType = "audio/mpeg",
            ),
        )
    }

    @Test
    fun `valid supported file audio intent is accepted`() {
        assertTrue(
            ExternalAudioIntentValidator.isAllowedAudioViewIntent(
                action = ACTION_VIEW,
                uriString = "file:///storage/emulated/0/Music/song.flac",
                intentMimeType = "audio/flac",
            ),
        )
    }

    @Test
    fun `supported file audio extension is accepted when type lookup is absent`() {
        assertTrue(
            ExternalAudioIntentValidator.isAllowedAudioViewIntent(
                action = ACTION_VIEW,
                uriString = "file:///storage/emulated/0/Music/song.wav",
                intentMimeType = null,
            ),
        )
    }

    @Test
    fun `unsupported schemes are rejected even with audio type`() {
        listOf(
            "https://example.test/song.mp3",
            "http://example.test/song.mp3",
            "wavdrop://open/song.mp3",
        ).forEach { uri ->
            assertFalse(
                ExternalAudioIntentValidator.isAllowedAudioViewIntent(
                    action = ACTION_VIEW,
                    uriString = uri,
                    intentMimeType = "audio/mpeg",
                ),
            )
        }
    }

    @Test
    fun `non audio mime type is rejected`() {
        assertFalse(
            ExternalAudioIntentValidator.isAllowedAudioViewIntent(
                action = ACTION_VIEW,
                uriString = "content://provider/document/1",
                intentMimeType = "text/plain",
                resolvedMimeTypeProvider = { "application/pdf" },
            ),
        )
    }

    @Test
    fun `missing uri is rejected`() {
        assertFalse(
            ExternalAudioIntentValidator.isAllowedAudioViewIntent(
                action = ACTION_VIEW,
                uriString = null,
                intentMimeType = "audio/mpeg",
            ),
        )
    }

    @Test
    fun `explicit malicious style bypass attempt is rejected by scheme validation`() {
        assertFalse(
            ExternalAudioIntentValidator.isAllowedAudioViewIntent(
                action = ACTION_VIEW,
                uriString = "javascript:alert(1)",
                intentMimeType = "audio/mpeg",
            ),
        )
    }

    @Test
    fun `failing resolver lookup does not crash or allow playback`() {
        assertFalse(
            ExternalAudioIntentValidator.isAllowedAudioViewIntent(
                action = ACTION_VIEW,
                uriString = "content://provider/document/1",
                intentMimeType = null,
                resolvedMimeTypeProvider = { throw SecurityException("denied") },
            ),
        )
    }

    @Test
    fun `resolver audio type can allow valid content intent when intent type is absent`() {
        assertTrue(
            ExternalAudioIntentValidator.isAllowedAudioViewIntent(
                action = ACTION_VIEW,
                uriString = "content://media/external/audio/media/42",
                intentMimeType = null,
                resolvedMimeTypeProvider = { "audio/ogg" },
            ),
        )
    }

    private companion object {
        const val ACTION_VIEW = "android.intent.action.VIEW"
    }
}
