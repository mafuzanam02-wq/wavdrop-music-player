package com.launchpoint.wavdrop.data.artwork

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArtworkResolverTest {

    @Test
    fun `null album id returns null`() {
        assertNull(ArtworkResolver.albumArtworkUri(null))
    }

    @Test
    fun `zero album id returns null`() {
        assertNull(ArtworkResolver.albumArtworkUri(0L))
    }

    @Test
    fun `negative album id returns null`() {
        assertNull(ArtworkResolver.albumArtworkUri(-12L))
    }

    @Test
    fun `positive album id returns media album art uri`() {
        assertEquals(
            "content://media/external/audio/albumart/42",
            ArtworkResolver.albumArtworkUri(42L),
        )
    }
}
